const crypto = require("crypto");
const { LRUCache } = require("lru-cache");
const security = require("./security");

const activeRelays = new Map();
const exportCache = new LRUCache({ max: 16, ttl: 60 * 1000, maxSize: 32 * 1024 * 1024, sizeCalculation: (v) => Buffer.byteLength(v, "utf8") + 64 });
const MAX_PROXY_RELAYS = Math.max(1, Number(process.env.MAX_PROXY_RELAYS || 20));
const MAX_CLIENTS_PER_RELAY = Math.max(1, Number(process.env.MAX_CLIENTS_PER_RELAY || 10));
const HDHR_ENABLED = String(process.env.ENABLE_HDHR || "false").toLowerCase() === "true";
const PROXY_ENABLED = String(process.env.ENABLE_STREAM_PROXY || "false").toLowerCase() === "true";
const HDHR_DEVICE_ID = String(process.env.HDHR_DEVICE_ID || "CC202601").replace(/[^0-9A-Fa-f]/g, "").slice(0, 8) || "CC202601";
const HDHR_TUNER_COUNT = Math.max(1, Math.min(16, Number(process.env.HDHR_TUNER_COUNT || 4)));

function normalizeStem(name) {
  return String(name || "")
    .toLowerCase()
    .replace(/\[.*?]|[({].*?[)}]/g, " ")
    .replace(/\b(fhd|uhd|hd|sd|4k|backup|feed|east|west|us|usa|uk|ca)\b/g, " ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
}

function escapeXml(str) {
  return String(str || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function formatXmltvDate(ms) {
  const d = new Date(Number(ms));
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}${pad(d.getUTCSeconds())} +0000`;
}

function formatXtreamStart(ms) {
  const d = new Date(Number(ms));
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}:${pad(d.getUTCHours())}-${pad(d.getUTCMinutes())}`;
}

async function ensureDispatchSchema(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS provider_sources (
      id TEXT PRIMARY KEY,
      account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
      device_code TEXT NOT NULL DEFAULT '__shared__',
      label TEXT NOT NULL DEFAULT 'Backup Provider',
      priority INTEGER NOT NULL DEFAULT 10,
      enabled BOOLEAN NOT NULL DEFAULT TRUE,
      login JSONB NOT NULL,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS idx_provider_sources_acct ON provider_sources(account_id, device_code, priority);

    CREATE TABLE IF NOT EXISTS dvr_series_rules (
      id TEXT PRIMARY KEY,
      account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
      device_code TEXT NOT NULL DEFAULT '__shared__',
      title_pattern TEXT NOT NULL,
      channel_id TEXT,
      channel_name TEXT,
      lead_minutes INTEGER NOT NULL DEFAULT 2,
      tail_minutes INTEGER NOT NULL DEFAULT 5,
      keep_last INTEGER NOT NULL DEFAULT 5,
      enabled BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS idx_dvr_series_rules_acct ON dvr_series_rules(account_id, device_code);

    CREATE TABLE IF NOT EXISTS stream_telemetry (
      id BIGSERIAL PRIMARY KEY,
      account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
      device_code TEXT NOT NULL DEFAULT '__shared__',
      channel_id TEXT NOT NULL,
      channel_name TEXT,
      bitrate_kbps INTEGER NOT NULL DEFAULT 0,
      buffer_count INTEGER NOT NULL DEFAULT 0,
      rebuffer_ms INTEGER NOT NULL DEFAULT 0,
      failed BOOLEAN NOT NULL DEFAULT FALSE,
      status TEXT NOT NULL DEFAULT 'playing',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS idx_stream_telemetry_recent ON stream_telemetry(account_id, updated_at DESC);
  `);

  const addCol = async (col, def) => {
    const { rows } = await pool.query(
      `SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'cloud_channels' AND column_name = $1`,
      [col]
    );
    if (!rows.length) {
      await pool.query(`ALTER TABLE cloud_channels ADD COLUMN IF NOT EXISTS ${col} ${def}`);
    }
  };
  await addCol("source_id", "TEXT NOT NULL DEFAULT 'primary'");
  await addCol("tv_archive", "BOOLEAN NOT NULL DEFAULT FALSE");
  await addCol("tv_archive_duration", "INTEGER NOT NULL DEFAULT 0");
}

async function loadFilteredLiveChannels(db, accountId, deviceId, liveEventWindowBounds) {
  const nowMs = Date.now();
  const { preWindowMs, postGraceCutoffMs } = liveEventWindowBounds(nowMs);
  const [lineup, channelsRes] = await Promise.all([
    db.getLineup(accountId, deviceId).catch(() => ({})),
    db.pool.query(
      `SELECT c.id, c.name, c.stream_url, c.logo_url,
              COALESCE(NULLIF(c.group_title, ''), cat.name, 'Live TV') AS group_title,
              c.category_id, c.epg_channel_id, c.tv_archive, c.tv_archive_duration, c.source_id
       FROM cloud_channels c
       LEFT JOIN cloud_categories cat
         ON cat.account_id = c.account_id AND cat.device_code = c.device_code AND cat.kind = c.kind AND cat.id = c.category_id
       WHERE c.account_id = $1 AND (c.device_code = $2 OR c.device_code = '__shared__') AND c.kind = 'live'
         AND (c.is_event_slot = FALSE OR c.event_start_ms < 0 OR (c.event_start_ms > 0 AND $3::bigint >= c.event_start_ms AND $4::bigint < c.event_end_ms))
       ORDER BY c.group_title, c.name`,
      [accountId, deviceId, preWindowMs, postGraceCutoffMs]
    )
  ]);

  const hiddenGroups = new Set(lineup?.hiddenGroups || []);
  const hiddenChannels = new Set(lineup?.hiddenChannels || []);
  const groupNames = lineup?.groupNames || {};
  const channelNames = lineup?.channelNames || {};

  return channelsRes.rows
    .filter((ch) => !hiddenChannels.has(ch.id) && !hiddenGroups.has(ch.category_id) && !hiddenGroups.has(ch.group_title))
    .map((ch) => ({
      ...ch,
      displayName: channelNames[ch.id] || ch.name,
      displayGroup: groupNames[ch.category_id] || groupNames[ch.group_title] || ch.group_title
    }));
}

function registerDispatchRoutes(app, { db, requireDeviceAuth, liveEventWindowBounds, log }) {
  ensureDispatchSchema(db.pool).catch((err) => {
    log.error({ err: err.message }, "dispatch schema ensure failed");
  });

  app.get("/api/providers", requireDeviceAuth, async (req, res) => {
    try {
      const { rows } = await db.pool.query(
        `SELECT id, label, priority, enabled, login, updated_at
         FROM provider_sources
         WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__')
         ORDER BY priority ASC, updated_at ASC`,
        [req.auth.account.id, req.auth.deviceId]
      );
      const safe = rows.map((r) => {
        const dec = security.decryptLogin(r.login || {});
        return {
          id: r.id,
          label: r.label,
          priority: r.priority,
          enabled: r.enabled,
          mode: dec.mode || "xtream",
          server: dec.server || dec.xtreamHost || "",
          username: dec.username || dec.xtreamUser || "",
          updatedAt: r.updated_at
        };
      });
      res.setHeader("Cache-Control", "no-store");
      res.json({ ok: true, providers: safe });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.post("/api/providers", requireDeviceAuth, async (req, res) => {
    try {
      const { id, label, priority = 10, enabled = true, mode = "xtream", server, username, password, url } = req.body || {};
      const targetUrl = mode === "m3u" ? url : server;
      if (!targetUrl) return res.status(400).json({ error: "Provider server or M3U url required" });
      await security.assertSafeProviderUrl(targetUrl);
      const sourceId = String(id || ("src_" + crypto.randomBytes(6).toString("hex"))).slice(0, 64);
      const encrypted = security.encryptLogin({ mode, server, username, password, url });
      await db.pool.query(
        `INSERT INTO provider_sources (id, account_id, device_code, label, priority, enabled, login, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, NOW())
         ON CONFLICT (id) DO UPDATE SET
           label = EXCLUDED.label,
           priority = EXCLUDED.priority,
           enabled = EXCLUDED.enabled,
           login = EXCLUDED.login,
           updated_at = NOW()`,
        [sourceId, req.auth.account.id, req.auth.deviceId, String(label || "Backup Provider").slice(0, 80), Number(priority) || 10, Boolean(enabled), JSON.stringify(encrypted)]
      );
      await db.pool.query(
        `SELECT pg_notify('olympus_sync', json_build_object('table', 'provider_logins', 'account_id', $1::text, 'device_code', $2::text)::text)`,
        [req.auth.account.id, req.auth.deviceId]
      ).catch(() => {});
      res.json({ ok: true, id: sourceId });
    } catch (err) {
      res.status(400).json({ error: err.message });
    }
  });

  app.delete("/api/providers/:id", requireDeviceAuth, async (req, res) => {
    try {
      await db.pool.query(
        `DELETE FROM provider_sources WHERE id = $1 AND account_id = $2`,
        [req.params.id, req.auth.account.id]
      );
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.get("/api/channels/:id/alternates", requireDeviceAuth, async (req, res) => {
    try {
      const channelId = String(req.params.id || "").trim();
      const { rows: cur } = await db.pool.query(
        `SELECT id, name, stream_url, source_id FROM cloud_channels
         WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__') AND kind = 'live' AND id = $3
         LIMIT 1`,
        [req.auth.account.id, req.auth.deviceId, channelId]
      );
      if (!cur.length) return res.json({ ok: true, alternates: [] });
      const stem = normalizeStem(cur[0].name);
      if (stem.length < 3) return res.json({ ok: true, alternates: [] });
      const { rows: candidates } = await db.pool.query(
        `SELECT id, name, stream_url AS "streamUrl", logo_url AS "logoUrl", group_title AS "groupTitle", source_id AS "sourceId"
         FROM cloud_channels
         WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__') AND kind = 'live'
           AND id <> $3 AND stream_url <> $4
           AND to_tsvector('simple', name) @@ plainto_tsquery('simple', $5)
         LIMIT 25`,
        [req.auth.account.id, req.auth.deviceId, channelId, cur[0].stream_url, stem]
      );
      const alternates = candidates.filter((c) => normalizeStem(c.name) === stem);
      res.json({ ok: true, alternates });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.get("/api/export/playlist.m3u", requireDeviceAuth, async (req, res) => {
    try {
      const useProxy = PROXY_ENABLED && String(req.query.proxy || "") === "1";
      const cacheKey = `m3u:${req.auth.account.id}:${req.auth.deviceId}:${useProxy ? 1 : 0}`;
      let body = exportCache.get(cacheKey);
      if (!body) {
        const channels = await loadFilteredLiveChannels(db, req.auth.account.id, req.auth.deviceId, liveEventWindowBounds);
        const base = `${req.protocol}://${req.get("host")}`;
        const lines = ["#EXTM3U"];
        for (const ch of channels) {
          const attrs = [
            `tvg-id="${String(ch.epg_channel_id || ch.id).replace(/"/g, "")}"`,
            `tvg-name="${String(ch.displayName).replace(/"/g, "")}"`,
            ch.logo_url ? `tvg-logo="${String(ch.logo_url).replace(/"/g, "")}"` : "",
            `group-title="${String(ch.displayGroup).replace(/"/g, "")}"`,
            ch.tv_archive ? `catchup="xc" catchup-days="${ch.tv_archive_duration || 3}"` : ""
          ].filter(Boolean).join(" ");
          lines.push(`#EXTINF:-1 ${attrs},${ch.displayName}`);
          if (useProxy) {
            lines.push(`${base}/api/proxy/stream/${encodeURIComponent(ch.id)}?email=${encodeURIComponent(req.auth.account.email)}&deviceId=${encodeURIComponent(req.auth.deviceId)}`);
          } else {
            lines.push(ch.stream_url);
          }
        }
        body = lines.join("\n") + "\n";
        exportCache.set(cacheKey, body);
      }
      res.setHeader("Content-Type", "audio/x-mpegurl; charset=utf-8");
      res.setHeader("Content-Disposition", 'inline; filename="clearcast-playlist.m3u"');
      res.setHeader("Cache-Control", "private, max-age=60");
      res.send(body);
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.get("/api/export/epg.xml", requireDeviceAuth, async (req, res) => {
    try {
      const cacheKey = `xmltv:${req.auth.account.id}:${req.auth.deviceId}`;
      let xml = exportCache.get(cacheKey);
      if (!xml) {
        const nowMs = Date.now();
        const [channels, epgRes] = await Promise.all([
          loadFilteredLiveChannels(db, req.auth.account.id, req.auth.deviceId, liveEventWindowBounds),
          db.pool.query(
            `SELECT channel_id, title, start_millis, end_millis, description
             FROM cloud_epg
             WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__')
               AND end_millis > $3 AND start_millis < $4
             ORDER BY channel_id, start_millis`,
            [req.auth.account.id, req.auth.deviceId, nowMs - 24 * 3600 * 1000, nowMs + 48 * 3600 * 1000]
          )
        ]);
        const parts = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="ClearCast-Tuner">'];
        for (const ch of channels) {
          const cid = escapeXml(ch.epg_channel_id || ch.id);
          parts.push(`  <channel id="${cid}"><display-name>${escapeXml(ch.displayName)}</display-name>${ch.logo_url ? `<icon src="${escapeXml(ch.logo_url)}"/>` : ""}</channel>`);
        }
        for (const p of epgRes.rows) {
          parts.push(`  <programme start="${formatXmltvDate(p.start_millis)}" stop="${formatXmltvDate(p.end_millis)}" channel="${escapeXml(p.channel_id)}"><title>${escapeXml(p.title)}</title>${p.description ? `<desc>${escapeXml(p.description)}</desc>` : ""}</programme>`);
        }
        parts.push("</tv>");
        xml = parts.join("\n");
        exportCache.set(cacheKey, xml);
      }
      res.setHeader("Content-Type", "application/xml; charset=utf-8");
      res.setHeader("Cache-Control", "private, max-age=60");
      res.send(xml);
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.get("/api/catchup/url", requireDeviceAuth, async (req, res) => {
    try {
      const channelId = String(req.query.channelId || "").trim();
      const startMs = Number(req.query.startMs || 0);
      const durationMin = Math.max(1, Math.min(480, Number(req.query.durationMin || 60)));
      if (!channelId || !startMs) return res.status(400).json({ error: "channelId and startMs required" });
      const login = await db.getProviderLogin(req.auth.deviceId);
      if (!login || login.mode === "m3u") return res.status(404).json({ error: "Xtream provider login required for catch-up" });
      const host = String(login.server || login.xtreamHost || "").replace(/\/+$/, "");
      const user = encodeURIComponent(login.username || login.xtreamUser || "");
      const pass = encodeURIComponent(login.password || login.xtreamPassword || "");
      const startStr = formatXtreamStart(startMs);
      const url = `${host}/timeshift/${user}/${pass}/${durationMin}/${startStr}/${encodeURIComponent(channelId)}.ts`;
      res.setHeader("Cache-Control", "no-store");
      res.json({ ok: true, url, start: startStr, durationMin });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  if (HDHR_ENABLED) {
    const hdhrEmail = String(process.env.HDHR_ACCOUNT_EMAIL || "").trim().toLowerCase();
    const hdhrDevice = String(process.env.HDHR_DEVICE_CODE || "__shared__").trim();

    app.get("/discover.json", (req, res) => {
      const base = `${req.protocol}://${req.get("host")}`;
      res.json({
        FriendlyName: "ClearCast Tuner",
        ModelNumber: "HDHR5-4US",
        FirmwareName: "clearcast_tuner",
        FirmwareVersion: "20261125",
        DeviceID: HDHR_DEVICE_ID,
        DeviceAuth: "clearcast",
        BaseURL: base,
        LineupURL: `${base}/lineup.json`,
        TunerCount: HDHR_TUNER_COUNT
      });
    });

    app.get("/lineup_status.json", (_req, res) => {
      res.json({ ScanInProgress: 0, ScanPossible: 1, Source: "Cable", SourceList: ["Cable"] });
    });

    app.get("/lineup.json", async (req, res) => {
      try {
        const email = String(req.query.email || hdhrEmail).trim().toLowerCase();
        const deviceId = String(req.query.deviceId || hdhrDevice).trim();
        if (!email) return res.status(400).json({ error: "Set HDHR_ACCOUNT_EMAIL or pass ?email=" });
        const account = await db.ensureAccount(email);
        const channels = await loadFilteredLiveChannels(db, account.id, deviceId, liveEventWindowBounds);
        const base = `${req.protocol}://${req.get("host")}`;
        const lineup = channels.map((ch, idx) => ({
          GuideNumber: String(idx + 1),
          GuideName: ch.displayName,
          URL: PROXY_ENABLED
            ? `${base}/api/proxy/stream/${encodeURIComponent(ch.id)}?email=${encodeURIComponent(email)}&deviceId=${encodeURIComponent(deviceId)}`
            : ch.stream_url
        }));
        res.json(lineup);
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });
  }

  app.get("/api/proxy/sessions", requireDeviceAuth, (_req, res) => {
    const sessions = [];
    for (const [key, relay] of activeRelays.entries()) {
      sessions.push({
        key,
        channelId: relay.channelId,
        clients: relay.clients.size,
        bytesOut: relay.bytesOut,
        startedAt: relay.startedAt,
        uptimeSec: Math.round((Date.now() - relay.startedAt) / 1000)
      });
    }
    res.json({ ok: true, enabled: PROXY_ENABLED, sessions });
  });

  app.get("/api/proxy/stream/:channelId", requireDeviceAuth, async (req, res) => {
    if (!PROXY_ENABLED) return res.status(403).json({ error: "Stream proxy disabled (set ENABLE_STREAM_PROXY=true)" });
    try {
      const channelId = String(req.params.channelId || "").trim();
      const { rows } = await db.pool.query(
        `SELECT stream_url, name FROM cloud_channels
         WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__') AND id = $3
         LIMIT 1`,
        [req.auth.account.id, req.auth.deviceId, channelId]
      );
      if (!rows.length || !rows[0].stream_url) return res.status(404).json({ error: "Channel not found" });
      const streamUrl = rows[0].stream_url;
      await security.assertSafeProviderUrl(streamUrl);

      const relayKey = `${req.auth.account.id}:${channelId}`;
      let relay = activeRelays.get(relayKey);
      if (!relay && activeRelays.size >= MAX_PROXY_RELAYS) {
        return res.status(503).json({ error: "Maximum concurrent proxy relays reached" });
      }
      if (relay && relay.clients.size >= MAX_CLIENTS_PER_RELAY) {
        return res.status(503).json({ error: "Maximum clients reached for this relay" });
      }

      res.setHeader("Content-Type", "video/mp2t");
      res.setHeader("Cache-Control", "no-store");
      res.flushHeaders?.();

      if (!relay) {
        const controller = new AbortController();
        relay = {
          channelId,
          streamUrl,
          clients: new Set(),
          controller,
          bytesOut: 0,
          startedAt: Date.now()
        };
        activeRelays.set(relayKey, relay);

        (async () => {
          try {
            const upstream = await fetch(streamUrl, {
              signal: controller.signal,
              headers: { "User-Agent": "ClearCastTuner/1.0" }
            });
            if (!upstream.ok || !upstream.body) throw new Error(`HTTP ${upstream.status}`);
            const reader = upstream.body.getReader();
            while (true) {
              const { done, value } = await reader.read();
              if (done || relay.clients.size === 0) break;
              const buf = Buffer.from(value);
              for (const clientRes of relay.clients) {
                if (!clientRes.writableEnded && clientRes.writableLength < 2 * 1024 * 1024) {
                  clientRes.write(buf);
                  relay.bytesOut += buf.length;
                }
              }
            }
          } catch (_) {
          } finally {
            for (const clientRes of relay.clients) {
              if (!clientRes.writableEnded) clientRes.end();
            }
            relay.clients.clear();
            activeRelays.delete(relayKey);
          }
        })();
      }

      relay.clients.add(res);
      req.on("close", () => {
        relay.clients.delete(res);
        if (relay.clients.size === 0) {
          relay.controller.abort();
          activeRelays.delete(relayKey);
        }
      });
    } catch (err) {
      if (!res.headersSent) res.status(502).json({ error: err.message });
    }
  });

  app.get("/api/dvr/rules", requireDeviceAuth, async (req, res) => {
    try {
      const { rows } = await db.pool.query(
        `SELECT id, title_pattern AS "titlePattern", channel_id AS "channelId", channel_name AS "channelName",
                lead_minutes AS "leadMinutes", tail_minutes AS "tailMinutes", keep_last AS "keepLast",
                enabled, updated_at AS "updatedAt"
         FROM dvr_series_rules
         WHERE account_id = $1 AND (device_code = $2 OR device_code = '__shared__')
         ORDER BY updated_at DESC`,
        [req.auth.account.id, req.auth.deviceId]
      );
      res.json({ ok: true, rules: rows });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.post("/api/dvr/rules", requireDeviceAuth, async (req, res) => {
    try {
      const { id, titlePattern, channelId = "", channelName = "", leadMinutes = 2, tailMinutes = 5, keepLast = 5, enabled = true } = req.body || {};
      const cleanTitle = String(titlePattern || "").trim();
      if (!cleanTitle) return res.status(400).json({ error: "titlePattern required" });
      const ruleId = String(id || ("rule_" + crypto.randomBytes(6).toString("hex"))).slice(0, 64);
      await db.pool.query(
        `INSERT INTO dvr_series_rules (id, account_id, device_code, title_pattern, channel_id, channel_name, lead_minutes, tail_minutes, keep_last, enabled, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW())
         ON CONFLICT (id) DO UPDATE SET
           title_pattern = EXCLUDED.title_pattern,
           channel_id = EXCLUDED.channel_id,
           channel_name = EXCLUDED.channel_name,
           lead_minutes = EXCLUDED.lead_minutes,
           tail_minutes = EXCLUDED.tail_minutes,
           keep_last = EXCLUDED.keep_last,
           enabled = EXCLUDED.enabled,
           updated_at = NOW()`,
        [ruleId, req.auth.account.id, req.auth.deviceId, cleanTitle.slice(0, 160), String(channelId).slice(0, 128), String(channelName).slice(0, 160), Number(leadMinutes) || 2, Number(tailMinutes) || 5, Math.max(1, Number(keepLast) || 5), Boolean(enabled)]
      );
      res.json({ ok: true, id: ruleId });
    } catch (err) {
      res.status(400).json({ error: err.message });
    }
  });

  app.delete("/api/dvr/rules/:id", requireDeviceAuth, async (req, res) => {
    try {
      await db.pool.query(
        `DELETE FROM dvr_series_rules WHERE id = $1 AND account_id = $2`,
        [req.params.id, req.auth.account.id]
      );
      res.json({ ok: true });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });

  app.post("/api/telemetry/heartbeat", requireDeviceAuth, async (req, res) => {
    try {
      const { channelId, channelName = "", bitrateKbps = 0, bufferCount = 0, rebufferMs = 0, failed = false, status = "playing" } = req.body || {};
      if (!channelId) return res.status(400).json({ error: "channelId required" });
      await db.pool.query(
        `INSERT INTO stream_telemetry (account_id, device_code, channel_id, channel_name, bitrate_kbps, buffer_count, rebuffer_ms, failed, status, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, NOW())`,
        [req.auth.account.id, req.auth.deviceId, String(channelId).slice(0, 128), String(channelName).slice(0, 160), Math.max(0, Number(bitrateKbps) || 0), Math.max(0, Number(bufferCount) || 0), Math.max(0, Number(rebufferMs) || 0), Boolean(failed), String(status || "playing").slice(0, 32)]
      );
      res.json({ ok: true });
    } catch (err) {
      res.status(400).json({ error: err.message });
    }
  });

  app.get("/api/telemetry/summary", requireDeviceAuth, async (req, res) => {
    try {
      const [activeRes, flakyRes] = await Promise.all([
        db.pool.query(
          `SELECT DISTINCT ON (device_code) device_code AS "deviceId", channel_id AS "channelId", channel_name AS "channelName",
                  bitrate_kbps AS "bitrateKbps", buffer_count AS "bufferCount", status, updated_at AS "updatedAt"
           FROM stream_telemetry
           WHERE account_id = $1 AND updated_at > NOW() - INTERVAL '3 minutes' AND status = 'playing'
           ORDER BY device_code, updated_at DESC`,
          [req.auth.account.id]
        ),
        db.pool.query(
          `SELECT channel_id AS "channelId", MAX(channel_name) AS "channelName",
                  COUNT(*)::int AS samples,
                  SUM(CASE WHEN failed THEN 1 ELSE 0 END)::int AS failures,
                  SUM(buffer_count)::int AS "totalBuffers",
                  ROUND(AVG(NULLIF(bitrate_kbps, 0)))::int AS "avgBitrateKbps"
           FROM stream_telemetry
           WHERE account_id = $1 AND updated_at > NOW() - INTERVAL '24 hours'
           GROUP BY channel_id
           HAVING SUM(CASE WHEN failed THEN 1 ELSE 0 END) > 0 OR SUM(buffer_count) >= 3
           ORDER BY failures DESC, "totalBuffers" DESC
           LIMIT 25`,
          [req.auth.account.id]
        )
      ]);
      res.json({
        ok: true,
        activeSessions: activeRes.rows,
        flakyChannels: flakyRes.rows,
        proxyRelays: activeRelays.size
      });
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  });
}

module.exports = { registerDispatchRoutes, ensureDispatchSchema, normalizeStem };
