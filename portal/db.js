require("dotenv").config();
const crypto = require("crypto");
const { Pool, Client } = require("pg");

const pgConfig = {
  host: process.env.PGHOST || "postgresql-211700-0.cloudclusters.net",
  port: Number(process.env.PGPORT || 10020),
  database: process.env.PGDATABASE || "Olympus-IPTV",
  user: process.env.PGUSER || "olympus",
  password: process.env.PGPASSWORD || "",
  ssl: process.env.PGSSLMODE === "disable" ? false : { rejectUnauthorized: false },
  max: 15,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 8000,
  statement_timeout: 5000,
  query_timeout: 6000
};

const pool = new Pool(pgConfig);

pool.on("error", (err) => {
  console.error("[pg-pool] unexpected error:", err.message);
});

function sha256(raw) {
  return crypto.createHash("sha256").update(String(raw)).digest("hex");
}

function generateDeviceToken() {
  return "oly_" + crypto.randomBytes(32).toString("hex");
}

function generatePairingCode() {
  return String(crypto.randomInt(100000, 999999));
}

function timingSafeEqualHex(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a, "utf8"), Buffer.from(b, "utf8"));
}

async function ensureAccount(email) {
  const norm = String(email || "").trim().toLowerCase();
  if (!norm || !norm.includes("@") || norm.length > 254) {
    throw new Error("Valid email required");
  }
  const { rows } = await pool.query(
    `INSERT INTO accounts (email, updated_at)
     VALUES ($1, NOW())
     ON CONFLICT (email) DO UPDATE SET updated_at = NOW()
     RETURNING id, email`,
    [norm]
  );
  return rows[0];
}

async function registerDevice(accountId, deviceCode, platform = "android", deviceName = "") {
  const code = String(deviceCode || "").trim().slice(0, 128);
  if (!code) throw new Error("deviceId required");

  const existing = await pool.query(
    `SELECT token_hash FROM devices WHERE account_id = $1 AND device_code = $2`,
    [accountId, code]
  );

  let plainToken = null;
  let tokenHash = existing.rows[0]?.token_hash || null;
  if (!tokenHash) {
    plainToken = generateDeviceToken();
    tokenHash = sha256(plainToken);
  }

  const pairingCode = generatePairingCode();
  await pool.query(
    `INSERT INTO devices (
       account_id, device_code, device_name, platform, token_hash, pairing_code, pairing_expires_at, last_seen_at
     )
     VALUES ($1, $2, $3, $4, $5, $6, NOW() + INTERVAL '15 minutes', NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       last_seen_at = NOW(),
       platform = COALESCE(NULLIF(EXCLUDED.platform, ''), devices.platform),
       device_name = COALESCE(NULLIF(EXCLUDED.device_name, ''), devices.device_name),
       token_hash = COALESCE(devices.token_hash, EXCLUDED.token_hash),
       pairing_code = EXCLUDED.pairing_code,
       pairing_expires_at = EXCLUDED.pairing_expires_at`,
    [accountId, code, String(deviceName || "").slice(0, 120), String(platform || "android").slice(0, 32), tokenHash, pairingCode]
  );

  return { deviceCode: code, deviceToken: plainToken, pairingCode };
}

async function rotateDeviceToken(accountId, deviceCode) {
  const plainToken = generateDeviceToken();
  const tokenHash = sha256(plainToken);
  await pool.query(
    `UPDATE devices SET token_hash = $3, last_seen_at = NOW()
     WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode, tokenHash]
  );
  return plainToken;
}

async function verifyDeviceToken(accountId, deviceCode, presentedToken) {
  if (!presentedToken) return false;
  const { rows } = await pool.query(
    `SELECT token_hash FROM devices WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode]
  );
  if (!rows.length || !rows[0].token_hash) return false;
  return timingSafeEqualHex(rows[0].token_hash, sha256(presentedToken));
}

async function claimPairingCode(pairingCode) {
  const code = String(pairingCode || "").trim();
  if (!/^[0-9]{6}$/.test(code)) throw new Error("6-digit pairing code required");
  const { rows } = await pool.query(
    `SELECT d.account_id, d.device_code, a.email
     FROM devices d
     JOIN accounts a ON a.id = d.account_id
     WHERE d.pairing_code = $1 AND d.pairing_expires_at > NOW()
     LIMIT 1`,
    [code]
  );
  if (!rows.length) throw new Error("Invalid or expired pairing code");
  const row = rows[0];
  const webDeviceCode = "web_" + crypto.randomBytes(6).toString("hex");
  const reg = await registerDevice(row.account_id, webDeviceCode, "web", "Web Dashboard");
  await pool.query(
    `UPDATE devices SET pairing_code = NULL, pairing_expires_at = NULL
     WHERE account_id = $1 AND device_code = $2`,
    [row.account_id, row.device_code]
  );
  return {
    email: row.email,
    targetDeviceId: row.device_code,
    webDeviceId: webDeviceCode,
    deviceToken: reg.deviceToken
  };
}

async function resolveEffectiveDeviceCode(accountId, deviceCode) {
  if (!deviceCode || deviceCode === "__shared__") return "__shared__";
  const { rows } = await pool.query(
    `SELECT shared_mode FROM devices WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode]
  );
  if (rows.length && rows[0].shared_mode === true) return "__shared__";
  return deviceCode;
}

async function setSharedMode(accountId, deviceCode, enabled) {
  await pool.query(
    `UPDATE devices SET shared_mode = $3 WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode, Boolean(enabled)]
  );
}

async function getLineup(accountId, deviceCode) {
  const effective = await resolveEffectiveDeviceCode(accountId, deviceCode);
  const { rows } = await pool.query(
    `SELECT revision, config FROM lineup_configs WHERE account_id = $1 AND device_code = $2`,
    [accountId, effective]
  );
  if (rows.length) return Object.assign({}, rows[0].config, { revision: Number(rows[0].revision) || 0 });
  if (effective !== "__shared__") {
    const shared = await pool.query(
      `SELECT revision, config FROM lineup_configs WHERE account_id = $1 AND device_code = '__shared__'`,
      [accountId]
    );
    if (shared.rows.length) {
      return Object.assign({}, shared.rows[0].config, { revision: Number(shared.rows[0].revision) || 0 });
    }
  }
  return { revision: 0 };
}

async function upsertLineup(accountId, deviceCode, lineup) {
  const effective = await resolveEffectiveDeviceCode(accountId, deviceCode);
  const rev = Number(lineup?.revision) || Date.now();
  const payload = Object.assign({}, lineup, { revision: rev });
  const { rows } = await pool.query(
    `INSERT INTO lineup_configs (account_id, device_code, revision, config, updated_at)
     VALUES ($1, $2, $3, $4::jsonb, NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       revision = EXCLUDED.revision,
       config = EXCLUDED.config,
       updated_at = NOW()
     WHERE EXCLUDED.revision >= lineup_configs.revision
     RETURNING revision, config`,
    [accountId, effective, rev, JSON.stringify(payload)]
  );
  if (rows.length) return Object.assign({}, rows[0].config, { revision: Number(rows[0].revision) });
  return getLineup(accountId, deviceCode);
}

async function listLineupHistory(accountId, deviceCode) {
  const effective = await resolveEffectiveDeviceCode(accountId, deviceCode);
  const { rows } = await pool.query(
    `SELECT id, revision, saved_at,
            jsonb_array_length(COALESCE(config->'hiddenGroups', '[]'::jsonb)) AS hidden_groups,
            jsonb_array_length(COALESCE(config->'hiddenCountries', '[]'::jsonb)) AS hidden_countries
     FROM lineup_history
     WHERE account_id = $1 AND device_code = $2
     ORDER BY saved_at DESC
     LIMIT 5`,
    [accountId, effective]
  );
  return rows;
}

async function restoreLineupHistory(accountId, deviceCode, historyId) {
  const effective = await resolveEffectiveDeviceCode(accountId, deviceCode);
  const { rows } = await pool.query(
    `SELECT config FROM lineup_history
     WHERE id = $1 AND account_id = $2 AND device_code = $3`,
    [Number(historyId), accountId, effective]
  );
  if (!rows.length) throw new Error("Snapshot not found");
  const restored = Object.assign({}, rows[0].config, { revision: Date.now() });
  return upsertLineup(accountId, effective, restored);
}

async function cloneDeviceProfile(accountId, sourceDeviceCode, targetDeviceCode) {
  const srcLineup = await getLineup(accountId, sourceDeviceCode);
  const cloned = Object.assign({}, srcLineup, { revision: Date.now() });
  const saved = await upsertLineup(accountId, targetDeviceCode, cloned);
  const srcSnap = await getSnapshot(accountId, sourceDeviceCode);
  if (srcSnap.groups.length || srcSnap.countries.length) {
    await upsertSnapshot(accountId, targetDeviceCode, srcSnap);
  }
  return saved;
}

async function getFavorites(accountId) {
  const { rows } = await pool.query(
    `SELECT revision, ids, removed, labels, logos FROM favorites WHERE account_id = $1 AND device_code = '__shared__'`,
    [accountId]
  );
  if (!rows.length) return { ids: [], removed: [], labels: {}, logos: {}, revision: 0 };
  const r = rows[0];
  return {
    ids: Array.isArray(r.ids) ? r.ids : [],
    removed: Array.isArray(r.removed) ? r.removed : [],
    labels: r.labels || {},
    logos: r.logos || {},
    revision: Number(r.revision) || 0
  };
}

async function upsertFavorites(accountId, next) {
  const rev = Number(next.revision) || Date.now();
  const { rows } = await pool.query(
    `INSERT INTO favorites (account_id, device_code, revision, ids, removed, labels, logos, updated_at)
     VALUES ($1, '__shared__', $2, $3::jsonb, $4::jsonb, $5::jsonb, $6::jsonb, NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       revision = EXCLUDED.revision,
       ids = EXCLUDED.ids,
       removed = EXCLUDED.removed,
       labels = EXCLUDED.labels,
       logos = EXCLUDED.logos,
       updated_at = NOW()
     WHERE EXCLUDED.revision >= favorites.revision
     RETURNING revision, ids, removed, labels, logos`,
    [
      accountId,
      rev,
      JSON.stringify(next.ids || []),
      JSON.stringify(next.removed || []),
      JSON.stringify(next.labels || {}),
      JSON.stringify(next.logos || {})
    ]
  );
  if (!rows.length) return getFavorites(accountId);
  const r = rows[0];
  return {
    ids: r.ids || [],
    removed: r.removed || [],
    labels: r.labels || {},
    logos: r.logos || {},
    revision: Number(r.revision) || rev
  };
}

async function getWatchHistory(accountId, limit = 40) {
  const { rows } = await pool.query(
    `SELECT channel_id AS "channelId", series_id AS "seriesId", season, name, section,
            position_ms AS "positionMs", duration_ms AS "durationMs",
            EXTRACT(EPOCH FROM updated_at) * 1000 AS "updatedAt"
     FROM watch_history
     WHERE account_id = $1
     ORDER BY updated_at DESC
     LIMIT $2`,
    [accountId, Math.min(Math.max(Number(limit) || 40, 1), 200)]
  );
  return rows;
}

async function upsertWatchEntry(accountId, entry) {
  const channelId = String(entry.channelId || "").trim();
  if (!channelId) throw new Error("channelId required");
  await pool.query(
    `INSERT INTO watch_history (
       account_id, channel_id, series_id, season, name, section, position_ms, duration_ms, updated_at
     )
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())
     ON CONFLICT (account_id, channel_id) DO UPDATE SET
       series_id = COALESCE(EXCLUDED.series_id, watch_history.series_id),
       season = COALESCE(EXCLUDED.season, watch_history.season),
       name = COALESCE(NULLIF(EXCLUDED.name, ''), watch_history.name),
       section = COALESCE(NULLIF(EXCLUDED.section, ''), watch_history.section),
       position_ms = EXCLUDED.position_ms,
       duration_ms = EXCLUDED.duration_ms,
       updated_at = NOW()`,
    [
      accountId,
      channelId,
      entry.seriesId || null,
      entry.season != null ? Number(entry.season) : null,
      String(entry.name || "").slice(0, 240),
      String(entry.section || "live").slice(0, 24),
      Math.max(0, Number(entry.positionMs || 0)),
      Math.max(0, Number(entry.durationMs || 0))
    ]
  );
}

async function getRecordings(accountId, deviceCode) {
  const { rows } = await pool.query(
    `SELECT revision, book FROM recording_books WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode]
  );
  if (!rows.length) return { items: [], smb: {}, revision: 0 };
  return Object.assign({ items: [], smb: {} }, rows[0].book, { revision: Number(rows[0].revision) || 0 });
}

async function upsertRecordings(accountId, deviceCode, book) {
  const rev = Number(book?.revision) || Date.now();
  const payload = Object.assign({}, book, { revision: rev });
  const { rows } = await pool.query(
    `INSERT INTO recording_books (account_id, device_code, revision, book, updated_at)
     VALUES ($1, $2, $3, $4::jsonb, NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       revision = EXCLUDED.revision,
       book = EXCLUDED.book,
       updated_at = NOW()
     WHERE EXCLUDED.revision >= recording_books.revision
     RETURNING revision, book`,
    [accountId, deviceCode, rev, JSON.stringify(payload)]
  );
  if (!rows.length) return getRecordings(accountId, deviceCode);
  return Object.assign({ items: [], smb: {} }, rows[0].book, { revision: Number(rows[0].revision) });
}

async function getReminders(accountId, deviceCode) {
  const { rows } = await pool.query(
    `SELECT id, channel_id AS "channelId", channel_number AS "channelNumber",
            channel_name AS "channelName", title, start_millis AS "startMillis",
            lead_minutes AS "leadMinutes"
     FROM reminders
     WHERE account_id = $1 AND device_code = $2 AND start_millis >= $3
     ORDER BY start_millis ASC`,
    [accountId, deviceCode, Date.now() - 3600000]
  );
  return rows;
}

async function upsertReminder(accountId, deviceCode, r) {
  const id = String(r.id || crypto.randomUUID());
  await pool.query(
    `INSERT INTO reminders (
       id, account_id, device_code, channel_id, channel_number, channel_name, title, start_millis, lead_minutes
     )
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
     ON CONFLICT (account_id, device_code, id) DO UPDATE SET
       channel_id = EXCLUDED.channel_id,
       channel_number = EXCLUDED.channel_number,
       channel_name = EXCLUDED.channel_name,
       title = EXCLUDED.title,
       start_millis = EXCLUDED.start_millis,
       lead_minutes = EXCLUDED.lead_minutes`,
    [
      id,
      accountId,
      deviceCode,
      String(r.channelId || ""),
      Number(r.channelNumber || 0),
      String(r.channelName || "").slice(0, 200),
      String(r.title || "").slice(0, 240),
      Number(r.startMillis || 0),
      Number(r.leadMinutes || 15)
    ]
  );
  return id;
}

async function deleteReminder(accountId, deviceCode, id) {
  await pool.query(
    `DELETE FROM reminders WHERE account_id = $1 AND device_code = $2 AND id = $3`,
    [accountId, deviceCode, String(id)]
  );
}

let providerTableReady = null;
function ensureProviderTable() {
  if (!providerTableReady) {
    providerTableReady = pool.query(`
      CREATE TABLE IF NOT EXISTS provider_logins (
        account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
        device_code     TEXT NOT NULL,
        revision        BIGINT NOT NULL DEFAULT 0,
        login           JSONB NOT NULL DEFAULT '{}'::jsonb,
        updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (account_id, device_code)
      )
    `).then(() => pool.query(
      `CREATE INDEX IF NOT EXISTS idx_provider_logins_device ON provider_logins(device_code)`
    )).catch((err) => {
      providerTableReady = null;
      throw err;
    });
  }
  return providerTableReady;
}

async function upsertProviderLogin(accountId, deviceCode, login) {
  await ensureProviderTable();
  const rev = Date.now();
  const payload = Object.assign({}, login, { revision: rev });
  const { rows } = await pool.query(
    `INSERT INTO provider_logins (account_id, device_code, revision, login, updated_at)
     VALUES ($1, $2, $3, $4::jsonb, NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       revision = EXCLUDED.revision,
       login = EXCLUDED.login,
       updated_at = NOW()
     RETURNING revision`,
    [accountId, deviceCode, rev, JSON.stringify(payload)]
  );
  return { revision: Number(rows[0]?.revision) || rev };
}

async function getProviderLogin(deviceCode) {
  await ensureProviderTable();
  const { rows } = await pool.query(
    `SELECT a.email, p.device_code, p.revision, p.login
     FROM provider_logins p
     JOIN accounts a ON a.id = p.account_id
     WHERE p.device_code = $1
     ORDER BY p.updated_at DESC
     LIMIT 1`,
    [deviceCode]
  );
  if (!rows.length) return null;
  const login = rows[0].login || {};
  return Object.assign({}, login, {
    email: rows[0].email,
    deviceId: rows[0].device_code,
    revision: Number(rows[0].revision) || Number(login.revision) || 0
  });
}

async function getSyncStamp(deviceCode) {
  await ensureProviderTable();
  const login = await pool.query(
    `SELECT revision FROM provider_logins WHERE device_code = $1 ORDER BY updated_at DESC LIMIT 1`,
    [deviceCode]
  );
  const loginRev = Number(login.rows[0]?.revision) || 0;
  const acct = await pool.query(
    `SELECT account_id FROM provider_logins WHERE device_code = $1
     UNION
     SELECT account_id FROM devices WHERE device_code = $1
     LIMIT 1`,
    [deviceCode]
  );
  const accountId = acct.rows[0]?.account_id;
  if (!accountId) {
    return { login: loginRev, lineup: 0, favorites: 0, recordings: 0, reminders: 0, watch: 0 };
  }
  const { rows } = await pool.query(
    `SELECT
       COALESCE((SELECT revision FROM lineup_configs WHERE account_id = $1 AND device_code = $2), 0) AS lineup,
       COALESCE((SELECT revision FROM favorites WHERE account_id = $1 AND device_code = '__shared__'), 0) AS favorites,
       COALESCE((SELECT revision FROM recording_books WHERE account_id = $1 AND device_code = $2), 0) AS recordings,
       COALESCE((
         SELECT (EXTRACT(EPOCH FROM MAX(created_at)) * 1000)::bigint + COUNT(*)::bigint
         FROM reminders WHERE account_id = $1 AND device_code = $2
       ), 0) AS reminders,
       COALESCE((
         SELECT (EXTRACT(EPOCH FROM MAX(updated_at)) * 1000)::bigint
         FROM watch_history WHERE account_id = $1
       ), 0) AS watch`,
    [accountId, deviceCode]
  );
  const row = rows[0] || {};
  return {
    login: loginRev,
    lineup: Number(row.lineup) || 0,
    favorites: Number(row.favorites) || 0,
    recordings: Number(row.recordings) || 0,
    reminders: Number(row.reminders) || 0,
    watch: Number(row.watch) || 0
  };
}

async function getSnapshot(accountId, deviceCode) {
  const { rows } = await pool.query(
    `SELECT library_stamp, channel_count, groups, countries
     FROM catalog_snapshots
     WHERE account_id = $1 AND device_code = $2`,
    [accountId, deviceCode]
  );
  if (!rows.length) return { groups: [], countries: [], channelCount: 0 };
  return {
    groups: rows[0].groups || [],
    countries: rows[0].countries || [],
    channelCount: Number(rows[0].channel_count) || 0
  };
}

async function upsertSnapshot(accountId, deviceCode, snapshot) {
  await pool.query(
    `INSERT INTO catalog_snapshots (account_id, device_code, library_stamp, channel_count, groups, countries, updated_at)
     VALUES ($1, $2, $3, $4, $5::jsonb, $6::jsonb, NOW())
     ON CONFLICT (account_id, device_code) DO UPDATE SET
       library_stamp = EXCLUDED.library_stamp,
       channel_count = EXCLUDED.channel_count,
       groups = EXCLUDED.groups,
       countries = EXCLUDED.countries,
       updated_at = NOW()`,
    [
      accountId,
      deviceCode,
      Number(snapshot.libraryStamp || 0),
      Number(snapshot.channelCount || 0),
      JSON.stringify(snapshot.groups || []),
      JSON.stringify(snapshot.countries || [])
    ]
  );
}

// Dedicated auto-reconnecting LISTEN/NOTIFY client for real-time SSE push
function startNotificationListener(onEvent) {
  let active = true;
  async function connect() {
    if (!active) return;
    const client = new Client(pgConfig);
    client.on("error", (err) => {
      console.error("[pg-notify] listener error:", err.message);
    });
    client.on("end", () => {
      if (active) setTimeout(connect, 3000);
    });
    client.on("notification", (msg) => {
      if (msg.channel !== "olympus_sync" || !msg.payload) return;
      try {
        onEvent(JSON.parse(msg.payload));
      } catch (_) {}
    });
    try {
      await client.connect();
      await client.query("LISTEN olympus_sync");
      console.log("[pg-notify] listening on channel olympus_sync");
    } catch (err) {
      console.error("[pg-notify] connect failed, retrying in 5s:", err.message);
      try { await client.end(); } catch (_) {}
      if (active) setTimeout(connect, 5000);
    }
  }
  connect();
  return () => { active = false; };
}

module.exports = {
  pool,
  ensureAccount,
  registerDevice,
  rotateDeviceToken,
  verifyDeviceToken,
  claimPairingCode,
  setSharedMode,
  getLineup,
  upsertLineup,
  listLineupHistory,
  restoreLineupHistory,
  cloneDeviceProfile,
  getFavorites,
  upsertFavorites,
  getWatchHistory,
  upsertWatchEntry,
  getRecordings,
  upsertRecordings,
  getReminders,
  upsertReminder,
  deleteReminder,
  getSnapshot,
  upsertSnapshot,
  upsertProviderLogin,
  getProviderLogin,
  getSyncStamp,
  startNotificationListener
};
