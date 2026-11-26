require("dotenv").config();
const crypto = require("crypto");
const path = require("path");
const zlib = require("zlib");
const util = require("util");
const gzipAsync = util.promisify(zlib.gzip);
const brotliAsync = util.promisify(zlib.brotliCompress);
const POST_EVENT_GRACE_MS = 30 * 60 * 1000;

function liveEventWindowBounds(nowMs) {
  return {
    preWindowMs: nowMs + eventParser.PRE_EVENT_WINDOW_MS,
    postGraceCutoffMs: nowMs - POST_EVENT_GRACE_MS
  };
}

function sendCompressedJson(req, res, entry) {
  res.setHeader("ETag", entry.etag);
  res.setHeader("Cache-Control", "private, max-age=30");
  if (req.headers["if-none-match"] === entry.etag) {
    return res.sendStatus(304);
  }
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  const acceptEnc = String(req.headers["accept-encoding"] || "");
  if (acceptEnc.includes("br") && entry.br) {
    res.setHeader("Content-Encoding", "br");
    res.setHeader("Vary", "Accept-Encoding");
    return res.send(entry.br);
  }
  if (acceptEnc.includes("gzip")) {
    res.setHeader("Content-Encoding", "gzip");
    res.setHeader("Vary", "Accept-Encoding");
    return res.send(entry.gzip);
  }
  return res.send(zlib.gunzipSync(entry.gzip));
}
const express = require("express");
const compression = require("compression");
const helmet = require("helmet");
const rateLimit = require("express-rate-limit");
const pinoHttp = require("pino-http");
const { LRUCache } = require("lru-cache");
const db = require("./db");
const eventParser = require("./eventParser");
const autoFavorites = require("./auto-favorites");
const security = require("./security");
const { registerDispatchRoutes } = require("./dispatch");
const log = security.logger.child({ service: "olympus-api" });

const catalogCache = new LRUCache({
  max: 48,
  ttl: 90 * 1000,
  maxSize: 96 * 1024 * 1024,
  sizeCalculation: (val) => (val.br?.length || 0) + (val.gzip?.length || 0) + 256
});
const catalogInFlight = new Map();

function invalidateCatalogCache(accountId, deviceCode) {
  for (const kind of ["live", "vod", "series"]) {
    catalogCache.delete(`${accountId}:${deviceCode}:${kind}`);
    catalogCache.delete(`${accountId}:__shared__:${kind}`);
  }
}

const app = express();
app.disable("x-powered-by");
app.set("etag", false);
app.use(
  pinoHttp({
    logger: log,
    autoLogging: {
      ignore: (req) => req.url === "/api/health" || req.url.startsWith("/api/sync/stream")
    }
  })
);

const trustProxy = String(process.env.TRUST_PROXY ?? "2").trim();
if (trustProxy === "true") app.set("trust proxy", true);
else if (trustProxy !== "false" && trustProxy !== "0") {
  app.set("trust proxy", Number(trustProxy) || 2);
}

app.use(
  helmet({
    contentSecurityPolicy: false,
    crossOriginEmbedderPolicy: false
  })
);

app.use(
  compression({
    filter(req, res) {
      if (String(req.headers.accept || "").includes("text/event-stream")) return false;
      if (req.path === "/api/catalog/sync" || req.path.startsWith("/api/proxy/stream/")) return false;
      return compression.filter(req, res);
    }
  })
);
app.use(express.json({ limit: "2mb" }));

const allowedOrigin = (process.env.CORS_ORIGIN || "*").trim();
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type,X-Device-Token,X-Device-Id,X-Olympus-Api-Key");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 180,
  standardHeaders: true,
  legacyHeaders: false
});
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 30,
  message: { error: "Too many authentication attempts, try again later." }
});
app.use("/api/", apiLimiter);

const API_SECRET_KEY = String(process.env.API_SECRET_KEY || "").trim();
function timingSafeMatch(a, b) {
  const bufA = Buffer.from(String(a || ""), "utf8");
  const bufB = Buffer.from(String(b || ""), "utf8");
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}
app.use("/api/", (req, res, next) => {
  if (!API_SECRET_KEY) return next();
  if (req.path === "/health") return next();
  const provided = String(req.headers["x-olympus-api-key"] || "").trim();
  if (!provided || !timingSafeMatch(provided, API_SECRET_KEY)) {
    return res.status(403).json({ error: "Forbidden: invalid or missing API key" });
  }
  next();
});

if (String(process.env.ENABLE_WEB_DASHBOARD || "false").toLowerCase() === "true") {
  app.use(express.static(path.join(__dirname, "public")));
}

const STRICT_TOKENS = String(process.env.STRICT_DEVICE_TOKENS || "false").toLowerCase() === "true";

async function requireDeviceAuth(req, res, next) {
  try {
    const email = req.body?.email || req.query?.email;
    const deviceId = String(
      req.headers["x-device-id"] || req.body?.deviceId || req.query?.deviceId || "__shared__"
    ).trim();
    const token = String(req.headers["x-device-token"] || "").trim();

    const account = await db.ensureAccount(email);
    if (token) {
      const ok = await db.verifyDeviceToken(account.id, deviceId, token);
      if (!ok) return res.status(401).json({ error: "Invalid device token" });
    } else if (STRICT_TOKENS) {
      return res.status(401).json({ error: "Missing X-Device-Token header" });
    }

    req.auth = { account, deviceId };
    next();
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
}

app.get("/api/health", async (_req, res) => {
  try {
    await db.pool.query("SELECT 1");
    res.json({
      ok: true,
      db: "postgresql",
      strictTokens: STRICT_TOKENS,
      encryption: security.encryptionEnabled,
      uptimeSec: Math.floor(process.uptime()),
      pool: {
        total: db.pool.totalCount,
        idle: db.pool.idleCount,
        waiting: db.pool.waitingCount
      },
      cache: {
        entries: catalogCache.size,
        bytes: catalogCache.calculatedSize
      }
    });
  } catch (err) {
    res.status(500).json({ ok: false, error: "Database unavailable" });
  }
});

app.get("/api/setup", async (req, res) => {
  try {
    const deviceId = String(req.query.device || req.query.deviceId || "").trim();
    if (!deviceId) return res.json({ ready: false, cloud: true, strictTokens: STRICT_TOKENS });
    const login = await db.getProviderLogin(deviceId);
    res.json({ ready: Boolean(login), cloud: true, strictTokens: STRICT_TOKENS });
  } catch (err) {
    res.status(500).json({ ready: false, error: err.message });
  }
});

app.post("/api/setup", authLimiter, async (req, res) => {
  try {
    const raw = req.body || {};
    const parsed = security.setupSchema.safeParse({
      email: raw.email,
      deviceId: raw.deviceId || raw.device || "",
      zip: String(raw.zip || ""),
      mode: raw.mode === "m3u" ? "m3u" : "xtream",
      host: raw.host || "",
      url: raw.url || "",
      username: raw.username || "",
      password: raw.password || "",
      includeVod: raw.includeVod !== false,
      includeInternational: Boolean(raw.includeInternational)
    });
    if (!parsed.success) {
      const msg = parsed.error.issues[0]?.message || "Invalid setup parameters";
      return res.status(400).json({ error: msg });
    }
    const { email, deviceId, zip, mode, host, url, username, password, includeVod, includeInternational } = parsed.data;
    if (mode === "m3u") {
      if (!url.startsWith("http")) {
        return res.status(400).json({ error: "M3U URL must start with http:// or https://" });
      }
      await security.assertSafeProviderUrl(url);
    } else {
      if (!host.startsWith("http") || !username || !password) {
        return res.status(400).json({ error: "Host, username, and password are required" });
      }
      await security.assertSafeProviderUrl(host);
    }
    const account = await db.ensureAccount(email.toLowerCase());
    await db.registerDevice(account.id, deviceId, "android", "");
    const saved = await db.upsertProviderLogin(account.id, deviceId, {
      mode,
      host,
      url,
      username,
      password,
      zip,
      includeVod,
      includeInternational
    });
    res.json({ ok: true, email: email.toLowerCase(), deviceId, revision: saved.revision });
  } catch (err) {
    res.status(400).json({ error: err.message || "Could not save the playlist" });
  }
});

app.get("/api/sync/stamp", async (req, res) => {
  try {
    const deviceId = String(req.query.deviceId || req.headers["x-device-id"] || "").trim();
    if (!deviceId) return res.status(400).json({ error: "Device ID required" });
    res.json({ ok: true, ...(await db.getSyncStamp(deviceId)) });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/provider-login", authLimiter, async (req, res) => {
  try {
    const deviceId = String(req.query.deviceId || req.headers["x-device-id"] || "").trim();
    if (!deviceId) return res.status(400).json({ error: "Device ID required" });
    const login = await db.getProviderLogin(deviceId);
    if (!login) return res.status(404).json({ error: "No playlist saved for this device" });
    const token = String(req.headers["x-device-token"] || "").trim();
    if (STRICT_TOKENS) {
      if (!token) return res.status(401).json({ error: "Missing X-Device-Token header" });
      const account = await db.ensureAccount(login.email);
      const ok = await db.verifyDeviceToken(account.id, deviceId, token);
      if (!ok) return res.status(401).json({ error: "Invalid device token" });
    } else if (token) {
      const account = await db.ensureAccount(login.email);
      const ok = await db.verifyDeviceToken(account.id, deviceId, token);
      if (!ok) return res.status(401).json({ error: "Invalid device token" });
    }
    res.json({ ok: true, ...login });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post("/api/register", authLimiter, async (req, res) => {
  try {
    const { email, deviceId, platform, deviceName } = req.body || {};
    const account = await db.ensureAccount(email);
    const reg = await db.registerDevice(account.id, deviceId, platform || "android", deviceName || "");
    const { rows } = await db.pool.query(
      "SELECT device_code FROM devices WHERE account_id = $1 ORDER BY last_seen_at DESC",
      [account.id]
    );
    res.json({
      ok: true,
      deviceId: reg.deviceCode,
      deviceToken: reg.deviceToken,
      pairingCode: reg.pairingCode,
      devices: rows.map((r) => r.device_code)
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/pair/claim", authLimiter, async (req, res) => {
  try {
    const claimed = await db.claimPairingCode(req.body?.pairingCode);
    res.json({ ok: true, ...claimed });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/devices/rotate-token", requireDeviceAuth, async (req, res) => {
  try {
    const newToken = await db.rotateDeviceToken(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, deviceToken: newToken });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

const sseClients = new Map();

db.startNotificationListener((evt) => {
  const listeners = sseClients.get(evt.account_id);
  if (!listeners || !listeners.size) return;
  const frame = `event: sync
data: ${JSON.stringify(evt)}

`;
  for (const clientRes of listeners) {
    try {
      clientRes.write(frame);
    } catch (_) {}
  }
});

app.get("/api/events", requireDeviceAuth, (req, res) => {
  const accountId = req.auth.account.id;
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.flushHeaders?.();
  res.write(`event: connected
data: {"ok":true}

`);

  if (!sseClients.has(accountId)) sseClients.set(accountId, new Set());
  sseClients.get(accountId).add(res);

  const keepAlive = setInterval(() => {
    try { res.write(": ping\n\n"); } catch (_) {}
  }, 25000);

  req.on("close", () => {
    clearInterval(keepAlive);
    const set = sseClients.get(accountId);
    if (set) {
      set.delete(res);
      if (!set.size) sseClients.delete(accountId);
    }
  });
});

app.post("/api/devices", requireDeviceAuth, async (req, res) => {
  try {
    const account = req.auth.account;
    const { rows } = await db.pool.query(
      `SELECT d.device_code, d.device_name, d.platform, d.shared_mode, l.config
       FROM devices d
       LEFT JOIN lineup_configs l
         ON l.account_id = d.account_id AND l.device_code = d.device_code
       WHERE d.account_id = $1
       ORDER BY d.last_seen_at DESC`,
      [account.id]
    );
    const devices = rows.map((r) => {
      const cfg = r.config || {};
      return {
        id: r.device_code,
        name: r.device_name || r.device_code,
        platform: r.platform,
        sharedMode: Boolean(r.shared_mode),
        hiddenCountries: Array.isArray(cfg.hiddenCountries) ? cfg.hiddenCountries.length : 0,
        hiddenGroups: Array.isArray(cfg.hiddenGroups) ? cfg.hiddenGroups.length : 0,
        hiddenChannels: Array.isArray(cfg.hiddenChannels) ? cfg.hiddenChannels.length : 0
      };
    });
    res.json({ ok: true, email: account.email, devices });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/devices/clone", requireDeviceAuth, async (req, res) => {
  try {
    const { sourceDeviceId, targetDeviceId } = req.body || {};
    if (!sourceDeviceId || !targetDeviceId) throw new Error("sourceDeviceId and targetDeviceId required");
    const lineup = await db.cloneDeviceProfile(req.auth.account.id, sourceDeviceId, targetDeviceId);
    res.json({ ok: true, lineup });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/devices/shared-mode", requireDeviceAuth, async (req, res) => {
  try {
    const { targetDeviceId, enabled } = req.body || {};
    await db.setSharedMode(req.auth.account.id, targetDeviceId || req.auth.deviceId, enabled);
    res.json({ ok: true, sharedMode: Boolean(enabled) });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/devices/delete", requireDeviceAuth, async (req, res) => {
  try {
    const target = String(req.body?.targetDeviceId || "").trim();
    if (!target || target === "__shared__") {
      return res.status(400).json({ error: "Valid targetDeviceId required" });
    }
    const accountId = req.auth.account.id;
    const childTables = [
      "lineup_configs",
      "favorites",
      "recordings",
      "reminders",
      "provider_logins",
      "cloud_channels",
      "cloud_categories",
      "cloud_epg",
      "watch_history"
    ];
    await Promise.all(
      childTables.map((tbl) =>
        db.pool.query(
          `DELETE FROM ${tbl} WHERE account_id = $1 AND device_code = $2`,
          [accountId, target]
        ).catch(() => {})
      )
    );
    await db.pool.query(
      `DELETE FROM devices WHERE account_id = $1 AND device_code = $2`,
      [accountId, target]
    );
    db.evictDeviceToken(accountId, target);
    invalidateCatalogCache(accountId, target);
    res.json({ ok: true, deleted: target });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/login", authLimiter, async (req, res) => {
  try {
    const { email, deviceId } = req.body || {};
    const account = await db.ensureAccount(email);
    let reg = null;
    if (deviceId) reg = await db.registerDevice(account.id, deviceId, "web");
    const code = deviceId || "__shared__";
    const [lineup, favorites, catalog, recordings, reminders, watchHistory] = await Promise.all([
      db.getLineup(account.id, code),
      db.getFavorites(account.id),
      db.getSnapshot(account.id, code),
      db.getRecordings(account.id, code),
      db.getReminders(account.id, code),
      db.getWatchHistory(account.id, 30)
    ]);
    res.json({
      ok: true,
      email: account.email,
      deviceId: code,
      deviceToken: reg?.deviceToken || null,
      devices: deviceId ? [{ id: deviceId }] : [],
      catalog,
      lineup,
      favorites,
      recordings,
      reminders,
      watchHistory
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/lineup", requireDeviceAuth, async (req, res) => {
  try {
    const lineup = await db.getLineup(req.auth.account.id, req.auth.deviceId);
    res.json({ lineup });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/lineup", requireDeviceAuth, async (req, res) => {
  try {
    await db.registerDevice(req.auth.account.id, req.auth.deviceId);
    const saved = await db.upsertLineup(req.auth.account.id, req.auth.deviceId, req.body?.lineup || {});
    res.json({ ok: true, lineup: saved });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/lineup/history", requireDeviceAuth, async (req, res) => {
  try {
    const history = await db.listLineupHistory(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, history });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/lineup/undo", requireDeviceAuth, async (req, res) => {
  try {
    const lineup = await db.restoreLineupHistory(req.auth.account.id, req.auth.deviceId, req.body?.historyId);
    res.json({ ok: true, lineup });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/sanitize/channels", requireDeviceAuth, async (req, res) => {
  try {
    const { channelStrip, channelKept } = req.body || {};
    const result = await db.sanitizeChannelNames(
      req.auth.account.id,
      req.auth.deviceId,
      Array.isArray(channelStrip) ? channelStrip : [],
      Array.isArray(channelKept) ? channelKept : []
    );
    invalidateCatalogCache(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, ...result });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/sanitize/groups", requireDeviceAuth, async (req, res) => {
  try {
    const { groupStrip } = req.body || {};
    const result = await db.sanitizeGroupNames(
      req.auth.account.id,
      req.auth.deviceId,
      Array.isArray(groupStrip) ? groupStrip : []
    );
    invalidateCatalogCache(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, ...result });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/sanitize", requireDeviceAuth, async (req, res) => {
  try {
    const { channelStrip, channelKept, groupStrip } = req.body || {};
    const result = await db.sanitizeCatalog(
      req.auth.account.id,
      req.auth.deviceId,
      Array.isArray(channelStrip) ? channelStrip : [],
      Array.isArray(channelKept) ? channelKept : [],
      Array.isArray(groupStrip) ? groupStrip : []
    );
    invalidateCatalogCache(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, ...result });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/groups/rename", requireDeviceAuth, async (req, res) => {
  try {
    const body = req.body || {};
    const finds = Array.isArray(body.finds) && body.finds.length
      ? body.finds
      : (body.find ? [body.find] : []);
    if (!finds.length) return res.status(400).json({ error: "Type the text to find first." });
    const result = await db.renameGroups(req.auth.account.id, req.auth.deviceId, finds, body.replace || "");
    res.json({ ok: true, ...result });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/playlist/remove", requireDeviceAuth, async (req, res) => {
  try {
    const result = await db.removeDevicePlaylist(req.auth.account.id, req.auth.deviceId);
    res.json({ ok: true, ...result });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/epg/parse", requireDeviceAuth, async (req, res) => {
  try {
    const enabled = req.body?.enabled !== false;
    const current = (await db.getLineup(req.auth.account.id, req.auth.deviceId)) || {};
    const saved = await db.upsertLineup(req.auth.account.id, req.auth.deviceId, {
      ...current,
      nameEpg: enabled,
      revision: Date.now()
    });
    res.json({ ok: true, nameEpg: enabled, lineup: saved });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/favorites", requireDeviceAuth, async (req, res) => {
  try {
    res.json(await db.getFavorites(req.auth.account.id));
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/favorites", requireDeviceAuth, async (req, res) => {
  try {
    const { ids, removed, add, remove, order, baseRevision } = req.body || {};
    const account = req.auth.account;
    const current = await db.getFavorites(account.id);
    let nextIds = Array.isArray(current.ids) ? current.ids.slice() : [];
    const dropped = new Set(Array.isArray(current.removed) ? current.removed : []);

    if (add) {
      dropped.delete(add);
      if (!nextIds.includes(add)) nextIds.push(add);
    } else if (remove) {
      nextIds = nextIds.filter((x) => x !== remove);
      dropped.add(remove);
    } else if (Array.isArray(order)) {
      const seen = new Set();
      const ordered = [];
      for (const id of order) {
        if (nextIds.includes(id) && !seen.has(id)) {
          seen.add(id);
          ordered.push(id);
        }
      }
      for (const id of nextIds) if (!seen.has(id)) ordered.push(id);
      nextIds = ordered;
    } else if (Array.isArray(ids)) {
      if (Array.isArray(removed)) removed.forEach((r) => dropped.add(r));
      if (Number(baseRevision || 0) < current.revision) {
        const merged = ids.filter((id) => !dropped.has(id));
        for (const id of nextIds) if (!merged.includes(id)) merged.push(id);
        nextIds = merged;
      } else {
        ids.forEach((id) => dropped.delete(id));
        nextIds = ids;
      }
    }

    const saved = await db.upsertFavorites(account.id, {
      ids: nextIds,
      removed: [...dropped],
      labels: current.labels,
      logos: current.logos,
      revision: Date.now()
    });
    res.json({ ok: true, favorites: saved });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/favorites/auto", requireDeviceAuth, async (req, res) => {
  try {
    const { account, deviceId } = req.auth;
    const mode = String(req.body?.mode || "").trim();
    const login = await db.getProviderLogin(deviceId);
    const zip = String(login?.zip || req.body?.zip || "").replace(/\D/g, "").slice(0, 5);
    const place = zip.length === 5 ? await lookupZip(zip) : null;
    const found = await db.pool.query(
      `SELECT id, name, COALESCE(group_title, '') AS "group"
       FROM cloud_channels
       WHERE account_id = $1 AND kind = 'live'
         AND (device_code = $2 OR device_code = '__shared__')
         AND (name LIKE '%|%' OR COALESCE(group_title, '') LIKE 'USA |%')
       LIMIT 20000`,
      [account.id, deviceId]
    );
    const channels = found.rows.map((row) => ({
      id: String(row.id),
      name: row.name || "",
      group: row.group || ""
    }));
    if (mode === "teams") {
      const teams = autoFavorites.teamsFor(String(req.body?.sport || ""), channels).map((team) => team.name);
      return res.json({ ok: true, teams });
    }
    const lat = place ? place.lat : null;
    const lon = place ? place.lon : null;
    let result = { market: "", picks: [] };
    if (mode === "local") {
      result = lat != null
        ? autoFavorites.localPicks(channels, lat, lon)
        : { market: "", picks: autoFavorites.nationalPicks(channels) };
    } else if (mode === "sports") {
      result = { market: "", picks: autoFavorites.sportsPicks(channels) };
    } else if (["filo", "utoob", "fubo", "directv", "frndly", "hulu"].includes(mode)) {
      result = autoFavorites.packagePicks(mode, channels, lat, lon);
    } else if (mode === "sling-orange" || mode === "sling-blue") {
      result = autoFavorites.slingPicks(mode === "sling-orange" ? "orange" : "blue", channels, lat, lon);
    } else if (mode === "add-teams") {
      const names = Array.isArray(req.body?.teams) ? req.body.teams : [];
      result = { market: "", picks: autoFavorites.picksForTeams(String(req.body?.sport || ""), channels, names) };
    } else {
      return res.status(400).json({ error: "Unknown favorites request" });
    }
    const current = await db.getFavorites(account.id);
    const ids = Array.isArray(current.ids) ? current.ids.slice() : [];
    const dropped = new Set(Array.isArray(current.removed) ? current.removed : []);
    const labels = Object.assign({}, current.labels || {});
    let added = 0;
    let already = 0;
    for (const pick of result.picks) {
      const key = `live:${pick.id}`;
      if (ids.includes(key)) already += 1;
      else {
        ids.push(key);
        added += 1;
      }
      dropped.delete(key);
      if (pick.label) labels[pick.id] = pick.label;
    }
    const saved = added > 0 || Object.keys(labels).length
      ? await db.upsertFavorites(account.id, {
        ids,
        removed: [...dropped],
        labels,
        logos: current.logos || {},
        revision: Date.now()
      })
      : current;
    const reason = channels.length
      ? ""
      : "The live channel list is not on the server yet. Leave the app open for a minute so the catalog can sync, then try again.";
    res.json({
      ok: true,
      market: result.market || "",
      added,
      already,
      reason,
      favorites: saved
    });
  } catch (err) {
    res.status(400).json({ error: err.message || "Could not add those channels" });
  }
});

app.post("/api/logs/error", async (req, res) => {
  try {
    const { email, deviceId, platform, tag, message, stackTrace, appVersion, detail } = req.body || {};
    let accountId = null;
    if (email && email.includes("@")) {
      const acc = await db.ensureAccount(email).catch(() => null);
      if (acc) accountId = acc.id;
    }
    await db.pool.query(
      `INSERT INTO error_logs (account_id, device_code, platform, tag, message, stack_trace, app_version, detail, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())`,
      [
        accountId,
        String(deviceId || "__shared__").slice(0, 128),
        String(platform || "android").slice(0, 32),
        String(tag || "ERROR").slice(0, 64),
        String(message || "").slice(0, 2000),
        String(stackTrace || "").slice(0, 8000),
        String(appVersion || "").slice(0, 32),
        detail ? String(detail).slice(0, 4000) : null
      ]
    );
    res.json({ ok: true });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

app.get("/api/logs/error", async (req, res) => {
  try {
    const { rows } = await db.pool.query(
      `SELECT e.id, a.email, e.device_code, e.platform, e.tag, e.message, e.stack_trace, e.detail, e.app_version, e.created_at
       FROM error_logs e
       LEFT JOIN accounts a ON a.id = e.account_id
       ORDER BY e.created_at DESC
       LIMIT 100`
    );
    res.json({ ok: true, logs: rows });
  } catch (err) {
    res.status(500).json({ ok: false, error: err.message });
  }
});

async function lookupZip(zip) {
  try {
    const response = await fetch(`https://api.zippopotam.us/us/${zip}`);
    if (!response.ok) return null;
    const json = await response.json();
    const place = json.places && json.places[0];
    if (!place) return null;
    const lat = Number(place.latitude);
    const lon = Number(place.longitude);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    return { lat, lon, city: place["place name"] || "" };
  } catch (err) {
    return null;
  }
}

app.get("/api/watch-history", requireDeviceAuth, async (req, res) => {
  try {
    const items = await db.getWatchHistory(req.auth.account.id, req.query.limit);
    res.json({ ok: true, items });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/watch-history", requireDeviceAuth, async (req, res) => {
  try {
    await db.upsertWatchEntry(req.auth.account.id, req.body || {});
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/recordings", requireDeviceAuth, async (req, res) => {
  try {
    res.json({ ok: true, recordings: await db.getRecordings(req.auth.account.id, req.auth.deviceId) });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/recordings", requireDeviceAuth, async (req, res) => {
  try {
    const saved = await db.upsertRecordings(req.auth.account.id, req.auth.deviceId, req.body?.recordings || {});
    res.json({ ok: true, recordings: saved });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/reminders", requireDeviceAuth, async (req, res) => {
  try {
    res.json({ ok: true, reminders: await db.getReminders(req.auth.account.id, req.auth.deviceId) });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/reminders", requireDeviceAuth, async (req, res) => {
  try {
    const id = await db.upsertReminder(req.auth.account.id, req.auth.deviceId, req.body || {});
    res.json({ ok: true, id });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/reminders/update", requireDeviceAuth, async (req, res) => {
  try {
    const id = String(req.body?.id || "").trim();
    const minutes = Number(req.body?.minutes ?? req.body?.leadMinutes ?? 15);
    if (!id) return res.status(400).json({ error: "Reminder id required" });
    const saved = await db.pool.query(
      `UPDATE reminders
       SET lead_minutes = $4, created_at = NOW()
       WHERE account_id = $1 AND device_code = $2 AND id = $3
       RETURNING id`,
      [req.auth.account.id, req.auth.deviceId, id, minutes]
    );
    if (!saved.rowCount) return res.status(404).json({ error: "Reminder not found" });
    res.json({ ok: true, id });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/reminders/delete", requireDeviceAuth, async (req, res) => {
  try {
    const id = String(req.body?.id || "").trim();
    if (!id) return res.status(400).json({ error: "Reminder id required" });
    await db.deleteReminder(req.auth.account.id, req.auth.deviceId, id);
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.delete("/api/reminders/:id", requireDeviceAuth, async (req, res) => {
  try {
    await db.deleteReminder(req.auth.account.id, req.auth.deviceId, req.params.id);
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/catalog/sync", requireDeviceAuth, async (req, res) => {
  try {
    const { account, deviceId } = req.auth;
    const kind = String(req.query.kind || "live").toLowerCase();
    if (!["live", "vod", "series"].includes(kind)) {
      return res.status(400).json({ error: "Invalid catalog kind" });
    }
    const sinceMs = Number(req.query.since || 0);
    if (Number.isFinite(sinceMs) && sinceMs > 0) {
      const sinceIso = new Date(sinceMs).toISOString();
      const nowMs = Date.now();
      const preWindowMs = nowMs + eventParser.PRE_EVENT_WINDOW_MS;
      const [categories, channels] = await Promise.all([
        db.pool.query(
          `SELECT id, name, kind FROM cloud_categories
           WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__') AND kind = $3 AND updated_at > $4::timestamptz`,
          [account.id, deviceId, kind, sinceIso]
        ),
        kind === "live"
          ? db.pool.query(
              `SELECT c.id, c.name, c.stream_url AS "streamUrl", c.logo_url AS "logoUrl",
                      COALESCE(NULLIF(c.group_title, ''), cat.name) AS "groupTitle",
                      c.category_id AS "categoryId", c.kind, c.series_id AS "seriesId", c.season,
                      c.tv_archive AS "tvArchive", c.tv_archive_duration AS "tvArchiveDuration"
               FROM cloud_channels c
               LEFT JOIN cloud_categories cat
                 ON cat.account_id = c.account_id AND cat.device_code = c.device_code AND cat.kind = c.kind AND cat.id = c.category_id
               WHERE c.account_id = $1 AND (c.device_code = $2 OR c.device_code = '__shared__') AND c.kind = 'live'
                 AND COALESCE(c.source_id, 'primary') = 'primary'
                 AND c.updated_at > $5::timestamptz
                 AND (c.is_event_slot = FALSE OR c.event_start_ms < 0 OR (c.event_start_ms > 0 AND $3::bigint >= c.event_start_ms AND $4::bigint < c.event_end_ms))`,
              [account.id, deviceId, preWindowMs, nowMs - 30 * 60 * 1000, sinceIso]
            )
          : db.pool.query(
              `SELECT c.id, c.name, c.stream_url AS "streamUrl", c.logo_url AS "logoUrl",
                      COALESCE(NULLIF(c.group_title, ''), cat.name) AS "groupTitle",
                      c.category_id AS "categoryId", c.kind, c.series_id AS "seriesId", c.season
               FROM cloud_channels c
               LEFT JOIN cloud_categories cat
                 ON cat.account_id = c.account_id AND cat.device_code = c.device_code AND cat.kind = c.kind AND cat.id = c.category_id
               WHERE c.account_id = $1 AND (c.device_code = $2 OR c.device_code = '__shared__') AND c.kind = $3
                 AND c.updated_at > $4::timestamptz`,
              [account.id, deviceId, kind, sinceIso]
            )
      ]);
      return res.json({ categories: categories.rows, channels: channels.rows, stamp: nowMs, delta: true });
    }

    const cacheKey = `${account.id}:${deviceId}:${kind}`;
    let entry = catalogCache.get(cacheKey);
    if (!entry) {
      let pending = catalogInFlight.get(cacheKey);
      if (!pending) {
        pending = (async () => {
          const categories = await db.pool.query(
            `SELECT id, name, kind FROM cloud_categories
             WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__') AND kind = $3`,
            [account.id, deviceId, kind]
          );

          const nowMs = Date.now();
          const preWindowMs = nowMs + eventParser.PRE_EVENT_WINDOW_MS;
          const channels = kind === "live"
            ? await db.pool.query(
                `SELECT c.id, c.name, c.stream_url AS "streamUrl", c.logo_url AS "logoUrl",
                        COALESCE(NULLIF(c.group_title, ''), cat.name) AS "groupTitle",
                        c.category_id AS "categoryId", c.kind, c.series_id AS "seriesId", c.season,
                        c.tv_archive AS "tvArchive", c.tv_archive_duration AS "tvArchiveDuration"
                 FROM cloud_channels c
                 LEFT JOIN cloud_categories cat
                   ON cat.account_id = c.account_id
                  AND cat.device_code = c.device_code
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE c.account_id = $1 AND (c.device_code = $2 OR c.device_code = '__shared__') AND c.kind = 'live'
                   AND COALESCE(c.source_id, 'primary') = 'primary'
                   AND (
                     c.is_event_slot = FALSE
                     OR c.event_start_ms < 0
                     OR (c.event_start_ms > 0 AND $3::bigint >= c.event_start_ms AND $4::bigint < c.event_end_ms)
                   )`,
                [account.id, deviceId, preWindowMs, nowMs - 30 * 60 * 1000]
              )
            : await db.pool.query(
                `SELECT c.id, c.name, c.stream_url AS "streamUrl", c.logo_url AS "logoUrl",
                        COALESCE(NULLIF(c.group_title, ''), cat.name) AS "groupTitle",
                        c.category_id AS "categoryId", c.kind, c.series_id AS "seriesId", c.season
                 FROM cloud_channels c
                 LEFT JOIN cloud_categories cat
                   ON cat.account_id = c.account_id
                  AND cat.device_code = c.device_code
                  AND cat.kind = c.kind
                  AND cat.id = c.category_id
                 WHERE c.account_id = $1 AND (c.device_code = $2 OR c.device_code = '__shared__') AND c.kind = $3`,
                [account.id, deviceId, kind]
              );

          const filteredChannels = channels.rows;
          if (filteredChannels.length === 0) {
            await noteEmptyCatalog(account, deviceId, kind, categories.rows.length).catch((err) => {
              log.error({ err: err.message }, "empty-catalog log failed");
            });
            return { empty: true, categories: categories.rows, stamp: nowMs };
          }

          const raw = Buffer.from(JSON.stringify({
            categories: categories.rows,
            channels: filteredChannels,
            stamp: nowMs
          }), "utf8");
          const [gzip, br] = await Promise.all([
            gzipAsync(raw, { level: 6 }),
            brotliAsync(raw, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 4 } })
          ]);
          const etag = `"${crypto.createHash("sha1").update(gzip).digest("hex")}"`;
          const built = { gzip, br, etag };
          catalogCache.set(cacheKey, built, { ttl: kind === "live" ? 60 * 1000 : 5 * 60 * 1000 });
          return built;
        })().finally(() => {
          catalogInFlight.delete(cacheKey);
        });
        catalogInFlight.set(cacheKey, pending);
      }
      entry = await pending;
      if (entry.empty) {
        return res.json({ categories: entry.categories, channels: [], stamp: entry.stamp });
      }
    }

    return sendCompressedJson(req, res, entry);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/catalog/search", requireDeviceAuth, async (req, res) => {
  try {
    const q = String(req.query.q || "").trim();
    const kind = String(req.query.kind || "").trim().toLowerCase();
    const limit = Math.min(200, Math.max(1, parseInt(req.query.limit || "60", 10) || 60));
    const snap = await db.getSnapshot(req.auth.account.id, req.auth.deviceId);
    const ql = q.toLowerCase();
    const groups = (snap.groups || []).filter((g) => !ql || String(g.name || "").toLowerCase().includes(ql));
    let channels = [];
    if (q.length >= 2) {
      const nowMs = Date.now();
      const { preWindowMs, postGraceCutoffMs } = liveEventWindowBounds(nowMs);
      const params = [req.auth.account.id, req.auth.deviceId, q, preWindowMs, postGraceCutoffMs, limit];
      const kindClause = kind && ["live", "vod", "series"].includes(kind) ? ` AND kind = $7` : "";
      if (kindClause) params.push(kind);
      const { rows } = await db.pool.query(
        `SELECT id, kind, name, group_title, category_id, logo_url, stream_url, epg_channel_id, series_id, season
         FROM cloud_channels
         WHERE account_id = $1 AND device_code = $2
           AND to_tsvector('simple', name) @@ plainto_tsquery('simple', $3)
           AND (kind <> 'live' OR is_event_slot = FALSE OR event_start_ms < 0 OR (event_start_ms > 0 AND event_start_ms <= $4 AND event_end_ms > $5))
           ${kindClause}
         LIMIT $6`,
        params
      );
      channels = rows;
    }
    res.json({ channels, groups });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/catalog/group", requireDeviceAuth, async (req, res) => {
  try {

    res.json({ channels: [], total: 0, offlineHint: "Connect on home Wi-Fi to browse individual channels in this group." });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/catalog", requireDeviceAuth, async (req, res) => {
  try {
    const { groups, countries, channelCount, libraryStamp } = req.body || {};
    await db.registerDevice(req.auth.account.id, req.auth.deviceId);
    await db.upsertSnapshot(req.auth.account.id, req.auth.deviceId, { groups, countries, channelCount, libraryStamp });
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/profile", requireDeviceAuth, async (req, res) => {
  try {
    const [lineup, favorites] = await Promise.all([
      db.getLineup(req.auth.account.id, req.auth.deviceId),
      db.getFavorites(req.auth.account.id)
    ]);
    res.json({
      "olympus-iptv": "profile",
      deviceId: req.auth.deviceId,
      exportedAt: String(Date.now()),
      lineup,
      favorites: favorites.ids || []
    });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/profile", requireDeviceAuth, async (req, res) => {
  try {
    const { lineup, favorites } = req.body || {};
    await db.registerDevice(req.auth.account.id, req.auth.deviceId);
    if (lineup && typeof lineup === "object") {
      await db.upsertLineup(req.auth.account.id, req.auth.deviceId, Object.assign({}, lineup, { revision: Date.now() }));
    }
    if (Array.isArray(favorites)) {
      const cur = await db.getFavorites(req.auth.account.id);
      await db.upsertFavorites(req.auth.account.id, {
        ids: favorites,
        removed: [],
        labels: cur.labels,
        logos: cur.logos,
        revision: Date.now()
      });
    }
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

async function noteEmptyCatalog(account, deviceId, kind, categoryCount) {
  const recent = await db.pool.query(
    `SELECT 1 FROM error_logs
     WHERE account_id = $1 AND device_code = $2 AND tag = 'Catalog' AND created_at > NOW() - INTERVAL '15 minutes'
     LIMIT 1`,
    [account.id, deviceId]
  );
  if (recent.rows.length) return;
  const loginRow = await db.pool.query(
    `SELECT login FROM provider_logins WHERE account_id = $1 AND device_code = $2`,
    [account.id, deviceId]
  );
  const syncRow = await db.pool.query(
    `SELECT status, live_count, vod_count, series_count, detail, ran_at
     FROM cloud_sync_logs
     WHERE account_id = $1 AND device_code = $2
     ORDER BY ran_at DESC
     LIMIT 1`,
    [account.id, deviceId]
  );
  const login = loginRow.rows[0]?.login || null;
  const sync = syncRow.rows[0] || null;
  const host = db.providerHostOnly(login);
  const message = kind === "live"
    ? `Live channels did not load. Cloud catalog is empty for this device.`
    : `${kind} catalog is empty for this device.`;
  const detail = JSON.stringify({
    kind,
    categories: categoryCount,
    channels: 0,
    providerHost: host || "missing",
    loginSaved: Boolean(login),
    lastSync: sync ? {
      status: sync.status,
      live: sync.live_count,
      vod: sync.vod_count,
      series: sync.series_count,
      at: sync.ran_at,
      detail: sync.detail || ""
    } : null
  });
  await db.writeDiagnostic(account.id, deviceId, "Catalog", message, detail);
  console.warn(`[catalog] ${account.email || account.id} ${deviceId} ${message} host=${host || "missing"} last=${sync ? sync.status : "never"}`);
}

let apiListenClient = null;
async function startCacheInvalidator() {
  if (apiShuttingDown) return;
  try {
    const client = await db.pool.connect();
    apiListenClient = client;
    await client.query("LISTEN olympus_sync");
    client.on("notification", (msg) => {
      try {
        const payload = JSON.parse(msg.payload || "{}");
        if (payload.account_id && payload.device_code && (payload.table === "cloud_channels" || payload.table === "cloud_categories")) {
          invalidateCatalogCache(payload.account_id, payload.device_code);
        }
      } catch (_) {}
    });
    client.on("error", () => {
      try { client.release(true); } catch (_) {}
      apiListenClient = null;
      if (!apiShuttingDown) setTimeout(startCacheInvalidator, 5000);
    });
  } catch (_) {
    if (!apiShuttingDown) setTimeout(startCacheInvalidator, 10000);
  }
}

registerDispatchRoutes(app, { db, requireDeviceAuth, liveEventWindowBounds, log });

const port = Number(process.env.PORT || 8443);
const httpServer = app.listen(port, "0.0.0.0", () => {
  log.info({ port, strictTokens: STRICT_TOKENS, encryption: security.encryptionEnabled }, "Olympus-IPTV Hardened Sync Server v2 listening");
  db.ensureDiagnostics().catch((err) => {
    log.error({ err: err.message }, "diagnostic columns ensure failed");
  });
  startCacheInvalidator();
});
httpServer.keepAliveTimeout = 65000;
httpServer.headersTimeout = 66000;

let apiShuttingDown = false;
async function shutdownApi(signal) {
  if (apiShuttingDown) return;
  apiShuttingDown = true;
  log.info({ signal }, "API server shutting down gracefully");
  if (apiListenClient) {
    try { await apiListenClient.query("UNLISTEN *"); } catch (_) {}
    try { apiListenClient.release(true); } catch (_) {}
    apiListenClient = null;
  }
  httpServer.close(async () => {
    try { await db.pool.end(); } catch (_) {}
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGTERM", () => shutdownApi("SIGTERM"));
process.on("SIGINT", () => shutdownApi("SIGINT"));

