require("dotenv").config();
const crypto = require("crypto");
const { Pool, Client } = require("pg");
const { LRUCache } = require("lru-cache");
const security = require("./security");

const sslConfig = process.env.PGSSLMODE === "disable"
  ? false
  : process.env.PGSSLCA
    ? { ca: process.env.PGSSLCA.replace(/\\n/g, "\n"), rejectUnauthorized: true }
    : { rejectUnauthorized: false };

const basePoolOpts = {
  ssl: sslConfig,
  max: Number(process.env.PG_POOL_MAX || 15),
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 8000,
  statement_timeout: 25000,
  query_timeout: 30000,
  keepAlive: true,
  keepAliveInitialDelayMillis: 10000,
  allowExitOnIdle: false,
  options: "-c idle_in_transaction_session_timeout=30000"
};

const pgConfig = process.env.DATABASE_URL
  ? Object.assign({ connectionString: process.env.DATABASE_URL }, basePoolOpts)
  : Object.assign({
      host: process.env.PGHOST || "127.0.0.1",
      port: Number(process.env.PGPORT || 5432),
      database: process.env.PGDATABASE || "clearcast_iptv",
      user: process.env.PGUSER || "postgres",
      password: process.env.PGPASSWORD || ""
    }, basePoolOpts);

const pool = new Pool(pgConfig);
const accountCache = new LRUCache({ max: 500, ttl: 10 * 60 * 1000 });
const tokenHashCache = new LRUCache({ max: 1000, ttl: 5 * 60 * 1000 });

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
  const cached = accountCache.get(norm);
  if (cached) return cached;
  const { rows } = await pool.query(
    `INSERT INTO accounts (email, updated_at)
     VALUES ($1, NOW())
     ON CONFLICT (email) DO UPDATE SET updated_at = NOW()
     RETURNING id, email`,
    [norm]
  );
  if (rows[0]) accountCache.set(norm, rows[0]);
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
  tokenHashCache.set(`${accountId}:${deviceCode}`, tokenHash);
  return plainToken;
}

async function verifyDeviceToken(accountId, deviceCode, presentedToken) {
  if (!presentedToken) return false;
  const key = `${accountId}:${deviceCode}`;
  let storedHash = tokenHashCache.get(key);
  if (!storedHash) {
    const { rows } = await pool.query(
      `SELECT token_hash FROM devices WHERE account_id = $1 AND device_code = $2`,
      [accountId, deviceCode]
    );
    if (!rows.length || !rows[0].token_hash) return false;
    storedHash = rows[0].token_hash;
    tokenHashCache.set(key, storedHash);
  }
  return timingSafeEqualHex(storedHash, sha256(presentedToken));
}

function evictDeviceToken(accountId, deviceCode) {
  tokenHashCache.delete(`${accountId}:${deviceCode}`);
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

const ADULT_GROUP_RE = /(?:^|[\s|:\-\[\(])(?:18\+|21\+|xxx|porn|erotic|nutflix|for\s+adults?|adult\s+time)(?:$|[\s|:\-\]\)])|🔞/i;

function isProtectedAdultGroupId(id, groupNameMap) {
  const s = String(id || "").trim();
  if (!s) return false;
  if (s === "__adult__") return true;
  if (ADULT_GROUP_RE.test(s) && !/adult\s+swim/i.test(s)) return true;
  const label = groupNameMap && groupNameMap[s] ? String(groupNameMap[s]) : "";
  return Boolean(label && ADULT_GROUP_RE.test(label) && !/adult\s+swim/i.test(label));
}

async function cloneDeviceProfile(accountId, sourceDeviceCode, targetDeviceCodes, inlineLineup = null) {
  const targets = (Array.isArray(targetDeviceCodes) ? targetDeviceCodes : [targetDeviceCodes])
    .map((id) => String(id || "").trim())
    .filter((id) => id && id !== sourceDeviceCode && id !== "__shared__");
  if (!targets.length) throw new Error("At least one target device is required");

  if (inlineLineup && typeof inlineLineup === "object") {
    const bumpedSrc = Object.assign({}, inlineLineup, { revision: Math.max(Number(inlineLineup.revision || 0), Date.now()) });
    await upsertLineup(accountId, sourceDeviceCode, bumpedSrc);
  }

  const [srcLineup, srcSnap, catRes] = await Promise.all([
    getLineup(accountId, sourceDeviceCode),
    getSnapshot(accountId, sourceDeviceCode),
    pool.query(
      `SELECT id, name FROM cloud_categories WHERE account_id = $1 AND kind = 'live'`,
      [accountId]
    ).catch(() => ({ rows: [] }))
  ]);
  const catNames = {};
  for (const r of catRes.rows) catNames[r.id] = r.name;

  const rev = Date.now();
  const copiedTo = [];
  await Promise.all(
    targets.map(async (targetCode) => {
      const targetLineup = await getLineup(accountId, targetCode).catch(() => ({}));
      const targetHidden = Array.isArray(targetLineup.hiddenGroups) ? targetLineup.hiddenGroups : [];
      const protectedAdultHidden = targetHidden.filter((gid) => isProtectedAdultGroupId(gid, catNames));
      const mergedHiddenGroups = Array.from(
        new Set([...(Array.isArray(srcLineup.hiddenGroups) ? srcLineup.hiddenGroups : []), ...protectedAdultHidden])
      );
      const mergedGroupPins = Object.assign(
        {},
        srcLineup.groupPins && typeof srcLineup.groupPins === "object" ? srcLineup.groupPins : {},
        targetLineup.groupPins && typeof targetLineup.groupPins === "object" ? targetLineup.groupPins : {}
      );
      const cloned = Object.assign({}, srcLineup, {
        hiddenGroups: mergedHiddenGroups,
        groupPins: mergedGroupPins,
        revision: Math.max(rev, Number(targetLineup.revision || 0) + 1)
      });
      await upsertLineup(accountId, targetCode, cloned);
      if (srcSnap.groups.length || srcSnap.countries.length) {
        await upsertSnapshot(accountId, targetCode, srcSnap);
      }
      await pool.query(
        `SELECT pg_notify('olympus_sync', json_build_object('table', 'lineup_configs', 'account_id', $1::text, 'device_code', $2::text)::text)`,
        [accountId, targetCode]
      ).catch(() => {});
      copiedTo.push(targetCode);
    })
  );
  return { copiedTo, revision: rev, lineup: srcLineup };
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
  const payload = security.encryptLogin(Object.assign({}, login, { revision: rev }));
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
  const login = security.decryptLogin(rows[0].login || {});
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
         SELECT (EXTRACT(EPOCH FROM MAX(created_at)) * 1000)::bigint
              + COUNT(*)::bigint
              + COALESCE(SUM(lead_minutes), 0)
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

async function ensureDiagnostics() {
  await pool.query(`ALTER TABLE cloud_sync_logs ADD COLUMN IF NOT EXISTS detail TEXT`);
  await pool.query(`ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS detail TEXT`);
}

function providerHostOnly(login) {
  const raw = String((login && (login.providerHost || login.xtreamHost || login.host)) || "").trim();
  if (!raw) return "";
  try {
    return new URL(raw).host;
  } catch (_) {
    return raw.replace(/\/.*$/, "").slice(0, 120);
  }
}

async function writeDiagnostic(accountId, deviceCode, tag, message, detail) {
  await pool.query(
    `INSERT INTO error_logs (account_id, device_code, platform, tag, message, detail, app_version, created_at)
     VALUES ($1, $2, 'server', $3, $4, $5, 'api', NOW())`,
    [
      accountId || null,
      String(deviceCode || "__shared__").slice(0, 128),
      String(tag || "Catalog").slice(0, 64),
      String(message || "").slice(0, 2000),
      detail ? String(detail).slice(0, 4000) : null
    ]
  );
}

function escapePgRegex(str) {
  return String(str || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function buildCombinedPgRegex(tokens) {
  return [...tokens]
    .sort((a, b) => b.length - a.length)
    .map(escapePgRegex)
    .join("|");
}

function stripTokensInText(raw, tokens, replacement = " ") {
  if (!tokens.length) return String(raw || "").trim();
  const rx = new RegExp(buildCombinedPgRegex(tokens), "gi");
  return String(raw || "").replace(rx, replacement).replace(/\s{2,}/g, " ").trim();
}

async function sanitizeChannelNames(accountId, deviceCode, channelStrip = [], channelKept = []) {
  const cleanChannels = [...new Set((channelStrip || []).map((t) => String(t || "").trim()).filter(Boolean))];
  const keptSet = new Set((channelKept || []).map((t) => String(t || "").trim().toLowerCase()).filter(Boolean));

  let channelsChanged = 0;
  if (cleanChannels.length) {
    const pattern = buildCombinedPgRegex(cleanChannels);
    const chRes = await pool.query(
      `UPDATE cloud_channels
       SET name = BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')),
           updated_at = NOW()
       WHERE account_id = $1
         AND name ~* $2
         AND BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')) <> ''`,
      [accountId, pattern]
    );
    channelsChanged = chRes.rowCount || 0;
  }

  const current = (await getLineup(accountId, deviceCode)) || {};
  const mergeTokens = (existing) => {
    const out = [];
    const seen = new Set();
    for (const t of Array.isArray(existing) ? existing : []) {
      const k = String(t).toLowerCase();
      if (keptSet.has(k) || seen.has(k)) continue;
      seen.add(k);
      out.push(t);
    }
    for (const t of cleanChannels) {
      const k = t.toLowerCase();
      if (seen.has(k)) continue;
      seen.add(k);
      out.push(t);
    }
    return out;
  };
  const savedLineup = await upsertLineup(accountId, deviceCode, {
    ...current,
    namePrefixes: mergeTokens(current.namePrefixes),
    nameSuffixes: mergeTokens(current.nameSuffixes),
    revision: Date.now()
  });

  return { channelsChanged, lineup: savedLineup };
}

async function sanitizeGroupNames(accountId, deviceCode, groupStrip = []) {
  const cleanGroups = [...new Set((groupStrip || []).map((t) => String(t || "").trim()).filter(Boolean))];
  if (!cleanGroups.length) {
    return { groupsChanged: 0, lineup: await getLineup(accountId, deviceCode) };
  }

  const pattern = buildCombinedPgRegex(cleanGroups);
  let groupsChanged = 0;
  const updatedGroupNames = {};
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const catRes = await client.query(
      `UPDATE cloud_categories
       SET name = BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')),
           updated_at = NOW()
       WHERE account_id = $1
         AND name ~* $2
         AND BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')) <> ''
       RETURNING id, name`,
      [accountId, pattern]
    );
    groupsChanged = catRes.rowCount || 0;
    for (const row of catRes.rows || []) {
      if (row.id && row.name) updatedGroupNames[row.id] = row.name;
    }
    await client.query(
      `UPDATE cloud_channels
       SET group_title = BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(group_title, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')),
           updated_at = NOW()
       WHERE account_id = $1
         AND group_title IS NOT NULL
         AND group_title ~* $2
         AND BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(group_title, $2, ' ', 'gi'), '\\s{2,}', ' ', 'g')) <> ''`,
      [accountId, pattern]
    );
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }

  const snap = await getSnapshot(accountId, deviceCode);
  let snapshotChanged = 0;
  if (snap && Array.isArray(snap.groups) && snap.groups.length) {
    const nextGroups = snap.groups.map((g) => {
      const orig = String(g.name || "");
      const next = stripTokensInText(orig, cleanGroups);
      if (next && next !== orig) {
        snapshotChanged += 1;
        if (g.id) updatedGroupNames[g.id] = next;
        return { ...g, name: next };
      }
      return g;
    });
    if (snapshotChanged > 0) {
      await upsertSnapshot(accountId, deviceCode, { ...snap, groups: nextGroups });
    }
  }
  if (groupsChanged === 0 && snapshotChanged > 0) groupsChanged = snapshotChanged;

  const current = (await getLineup(accountId, deviceCode)) || {};
  const nextGroupNames = {};
  for (const [id, raw] of Object.entries(current.groupNames || {})) {
    const val = stripTokensInText(raw, cleanGroups);
    if (val) nextGroupNames[id] = val;
  }
  for (const [id, name] of Object.entries(updatedGroupNames)) {
    if (name) nextGroupNames[id] = name;
  }
  const savedLineup = await upsertLineup(accountId, deviceCode, {
    ...current,
    groupNames: nextGroupNames,
    revision: Date.now()
  });

  return { groupsChanged, lineup: savedLineup };
}

async function sanitizeCatalog(accountId, deviceCode, channelStrip = [], channelKept = [], groupStrip = []) {
  const ch = await sanitizeChannelNames(accountId, deviceCode, channelStrip, channelKept);
  const gr = (groupStrip && groupStrip.length)
    ? await sanitizeGroupNames(accountId, deviceCode, groupStrip)
    : { groupsChanged: 0, lineup: ch.lineup };
  return {
    channelsChanged: ch.channelsChanged || 0,
    groupsChanged: gr.groupsChanged || 0,
    lineup: gr.lineup || ch.lineup
  };
}

async function renameGroups(accountId, deviceCode, finds = [], replacement = "") {
  const needles = [...new Set((finds || []).map((t) => String(t || "").trim()).filter(Boolean))];
  if (!needles.length) return { changed: 0, lineup: await getLineup(accountId, deviceCode) };
  const rep = String(replacement ?? "");
  const pattern = buildCombinedPgRegex(needles);
  let changed = 0;
  const updatedGroupNames = {};
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    const catRes = await client.query(
      `UPDATE cloud_categories
       SET name = BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, $3, 'gi'), '\\s{2,}', ' ', 'g')),
           updated_at = NOW()
       WHERE account_id = $1
         AND name ~* $2
         AND BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(name, $2, $3, 'gi'), '\\s{2,}', ' ', 'g')) <> ''
       RETURNING id, name`,
      [accountId, pattern, rep]
    );
    changed = catRes.rowCount || 0;
    for (const row of catRes.rows || []) {
      if (row.id && row.name) updatedGroupNames[row.id] = row.name;
    }
    await client.query(
      `UPDATE cloud_channels
       SET group_title = BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(group_title, $2, $3, 'gi'), '\\s{2,}', ' ', 'g')),
           updated_at = NOW()
       WHERE account_id = $1
         AND group_title IS NOT NULL
         AND group_title ~* $2
         AND BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(group_title, $2, $3, 'gi'), '\\s{2,}', ' ', 'g')) <> ''`,
      [accountId, pattern, rep]
    );
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }

  const snap = await getSnapshot(accountId, deviceCode);
  let snapshotChanged = 0;
  if (snap && Array.isArray(snap.groups) && snap.groups.length) {
    const nextGroups = snap.groups.map((g) => {
      const orig = String(g.name || "");
      const next = stripTokensInText(orig, needles, rep);
      if (next && next !== orig) {
        snapshotChanged += 1;
        if (g.id) updatedGroupNames[g.id] = next;
        return { ...g, name: next };
      }
      return g;
    });
    if (snapshotChanged > 0) {
      await upsertSnapshot(accountId, deviceCode, { ...snap, groups: nextGroups });
    }
  }
  if (changed === 0 && snapshotChanged > 0) changed = snapshotChanged;

  const current = (await getLineup(accountId, deviceCode)) || {};
  const nextGroupNames = {};
  for (const [id, raw] of Object.entries(current.groupNames || {})) {
    const val = stripTokensInText(raw, needles, rep);
    if (val) nextGroupNames[id] = val;
  }
  for (const [id, name] of Object.entries(updatedGroupNames)) {
    if (name) nextGroupNames[id] = name;
  }
  const savedLineup = await upsertLineup(accountId, deviceCode, {
    ...current,
    groupNames: nextGroupNames,
    revision: Date.now()
  });
  return { changed, lineup: savedLineup };
}

async function removeDevicePlaylist(accountId, deviceCode) {
  const code = String(deviceCode || "").trim();
  if (!accountId || !code) throw new Error("email and deviceId are required");
  const client = await pool.connect();
  let deleted = 0;
  try {
    await client.query("BEGIN");
    const tables = [
      "provider_logins",
      "lineup_configs",
      "lineup_history",
      "catalog_snapshots",
      "cloud_categories",
      "cloud_channels",
      "recording_books",
      "reminders",
      "favorites",
      "devices"
    ];
    for (const table of tables) {
      const r = await client.query(
        `DELETE FROM ${table} WHERE account_id = $1 AND device_code = $2`,
        [accountId, code]
      );
      deleted += r.rowCount || 0;
    }
    await client.query(
      `SELECT pg_notify('olympus_sync', $1)`,
      [JSON.stringify({ table: "playlist_removed", account_id: accountId, device_code: code, revision: Date.now() })]
    );
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
  return { deleted };
}

module.exports = {
  pool,
  ensureDiagnostics,
  providerHostOnly,
  writeDiagnostic,
  ensureAccount,
  registerDevice,
  rotateDeviceToken,
  verifyDeviceToken,
  evictDeviceToken,
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
  startNotificationListener,
  sanitizeChannelNames,
  sanitizeGroupNames,
  sanitizeCatalog,
  renameGroups,
  removeDevicePlaylist
};

