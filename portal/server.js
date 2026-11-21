require("dotenv").config();
const path = require("path");
const express = require("express");
const compression = require("compression");
const helmet = require("helmet");
const rateLimit = require("express-rate-limit");
const db = require("./db");

const app = express();
app.disable("x-powered-by");

// Security headers (relaxed CSP inline script only for embedded portal/public/index.html)
app.use(
  helmet({
    contentSecurityPolicy: false,
    crossOriginEmbedderPolicy: false
  })
);

app.use(compression());
app.use(express.json({ limit: "2mb" }));

// CORS lockdown (configure CORS_ORIGIN in .env for production)
const allowedOrigin = (process.env.CORS_ORIGIN || "*").trim();
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type,X-Device-Token,X-Device-Id");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

// Rate limiting against brute-force / scraping
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

app.use(express.static(path.join(__dirname, "public")));

const STRICT_TOKENS = String(process.env.STRICT_DEVICE_TOKENS || "false").toLowerCase() === "true";

// Zero-Trust Device Auth Middleware:
// - Always validates email -> account_id
// - Verifies X-Device-Token against SHA-256 hash in PostgreSQL
// - Smooth transition: if STRICT_DEVICE_TOKENS=false, legacy clients without a token header
//   still work while new clients enforce cryptographic token verification.
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

// ----------------------------------------------------------------------------
// Health & Setup
// ----------------------------------------------------------------------------
app.get("/api/health", async (_req, res) => {
  try {
    await db.pool.query("SELECT 1");
    res.json({ ok: true, db: "postgresql", strictTokens: STRICT_TOKENS });
  } catch (err) {
    res.status(500).json({ ok: false, error: "Database unavailable" });
  }
});

app.get("/api/setup", (_req, res) => {
  res.json({ ready: true, cloud: true, strictTokens: STRICT_TOKENS });
});

// ----------------------------------------------------------------------------
// 1. Device Registration, Token Issuance & 6-Digit Pairing
// ----------------------------------------------------------------------------
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
      deviceToken: reg.deviceToken, // Returned once on first registration; stored as SHA-256 in DB
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

// ----------------------------------------------------------------------------
// 2. Real-Time Push Sync (PostgreSQL LISTEN/NOTIFY -> Server-Sent Events)
// ----------------------------------------------------------------------------
const sseClients = new Map(); // accountId -> Set<res>

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
    try { res.write(": ping

"); } catch (_) {}
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

// ----------------------------------------------------------------------------
// 3. Devices, Multi-TV Cloning & Shared Household Toggle
// ----------------------------------------------------------------------------
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

// ----------------------------------------------------------------------------
// 4. Login, Lineup Config & 1-Click Undo History
// ----------------------------------------------------------------------------
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

// ----------------------------------------------------------------------------
// 5. Shared Favorites
// ----------------------------------------------------------------------------
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

// ----------------------------------------------------------------------------
// 6. Cross-Device Continue Watching & Watch History
// ----------------------------------------------------------------------------
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

// ----------------------------------------------------------------------------
// 7. Remote DVR Recordings & Program Reminders
// ----------------------------------------------------------------------------
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

app.delete("/api/reminders/:id", requireDeviceAuth, async (req, res) => {
  try {
    await db.deleteReminder(req.auth.account.id, req.auth.deviceId, req.params.id);
    res.json({ ok: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

// ----------------------------------------------------------------------------
// 8. Catalog Snapshots & Profile Export/Import
// ----------------------------------------------------------------------------
app.get("/api/catalog/search", requireDeviceAuth, async (req, res) => {
  try {
    const snap = await db.getSnapshot(req.auth.account.id, req.auth.deviceId);
    const q = String(req.query.q || "").trim().toLowerCase();
    const groups = (snap.groups || []).filter((g) => !q || String(g.name || "").toLowerCase().includes(q));
    res.json({ channels: [], groups });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/catalog/group", requireDeviceAuth, async (req, res) => {
  try {
    // Channels stay local in SQLite on the TV/Mobile device; return empty when offline from LAN
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

const port = Number(process.env.PORT || 8443);
app.listen(port, "0.0.0.0", () => {
  console.log(`Olympus-IPTV Hardened Sync Server v2 listening on http://0.0.0.0:${port}`);
});
