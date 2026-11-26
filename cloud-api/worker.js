require("dotenv").config();
const { Pool } = require("pg");
const eventParser = require("./eventParser");
const security = require("./security");
const { ensureDispatchSchema } = require("./dispatch");
const log = security.logger.child({ service: "olympus-worker" });

const sslConfig = process.env.PGSSLMODE === "disable"
  ? false
  : process.env.PGSSLCA
    ? { ca: process.env.PGSSLCA.replace(/\\n/g, "\n"), rejectUnauthorized: true }
    : { rejectUnauthorized: false };

const basePoolOpts = {
  ssl: sslConfig,
  max: 10,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 10000,
  keepAlive: true,
  keepAliveInitialDelayMillis: 10000,
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
const SYNC_INTERVAL_MS = 15 * 60 * 1000;

log.info({ encryption: security.encryptionEnabled }, "Starting Olympus-IPTV Centralized Ingestion Service");

async function fetchJson(url, timeoutMs = 25000, retries = 2) {
  let lastError = "";
  let lastStatus = 0;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        headers: { "User-Agent": "OlympusIPTV/2.0", Accept: "application/json" },
        signal: AbortSignal.timeout(timeoutMs)
      });
      lastStatus = res.status;
      if (res.ok) {
        const data = await res.json();
        return { ok: true, status: res.status, data, error: "" };
      }
      lastError = `HTTP ${res.status} ${res.statusText || ""}`.trim();
      if (res.status < 500 && res.status !== 429) {
        return { ok: false, status: res.status, data: null, error: lastError.slice(0, 400) };
      }
    } catch (err) {
      const code = err.code || err.name || "FETCH";
      const message = String(err.message || "request failed").replace(/https?:\/\/\S+/g, "[url]");
      lastError = `${code} ${message}`.trim();
    }
    if (attempt < retries) {
      await new Promise((r) => setTimeout(r, 750 * (attempt + 1)));
    }
  }
  return { ok: false, status: lastStatus, data: null, error: lastError.slice(0, 400) };
}

function asList(result, label) {
  if (!result || !result.ok) {
    return { list: null, error: `${label} request failed: ${(result && result.error) || "no response"}` };
  }
  if (Array.isArray(result.data)) return { list: result.data, error: "" };
  const info = result.data && result.data.user_info;
  const auth = info && info.auth != null ? String(info.auth) : "?";
  const note = (info && (info.message || info.status)) || (result.data && result.data.message) || "not a list";
  return { list: null, error: `${label} was not a channel list (auth=${auth} ${String(note).slice(0, 160)})` };
}

async function writeSyncLog(accountId, deviceCode, status, counts, duration, detail) {
  const live = counts.live || 0;
  const vod = counts.vod || 0;
  const series = counts.series || 0;
  await pool.query(
    `INSERT INTO cloud_sync_logs (account_id, device_code, status, live_count, vod_count, series_count, duration_ms, detail, ran_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())`,
    [accountId, deviceCode, status, live, vod, series, duration, detail ? String(detail).slice(0, 2000) : null]
  );
  if (status === "SUCCESS") return;
  await pool.query(
    `INSERT INTO error_logs (account_id, device_code, platform, tag, message, detail, app_version, created_at)
     VALUES ($1, $2, 'worker', 'CatalogSync', $3, $4, 'worker', NOW())`,
    [
      accountId,
      deviceCode,
      String(detail || status).slice(0, 2000),
      JSON.stringify({ status, live, vod, series, durationMs: duration })
    ]
  );
}

const BATCH_SIZE = 2500;

function dedupeByKey(items, keyFn) {
  const map = new Map();
  for (const item of items) {
    const k = keyFn(item);
    if (k) map.set(k, item);
  }
  return Array.from(map.values());
}

async function bulkUpsertCategories(client, accountId, deviceCode, kind, cats) {
  const unique = dedupeByKey(
    cats.filter((c) => c && c.category_id),
    (c) => String(c.category_id)
  );
  for (let i = 0; i < unique.length; i += BATCH_SIZE) {
    const slice = unique.slice(i, i + BATCH_SIZE);
    const ids = slice.map((c) => String(c.category_id));
    const names = slice.map((c) => String(c.category_name || ""));
    await client.query(
      `INSERT INTO cloud_categories (account_id, device_code, id, name, kind, updated_at)
       SELECT $1::uuid, $2::text, u.id, u.name, $3::text, NOW()
       FROM UNNEST($4::text[], $5::text[]) AS u(id, name)
       ON CONFLICT (account_id, device_code, kind, id) DO UPDATE SET
         name = EXCLUDED.name,
         updated_at = NOW()`,
      [accountId, deviceCode, kind, ids, names]
    );
  }
}

async function bulkUpsertLiveChannels(client, accountId, deviceCode, rows) {
  const unique = dedupeByKey(rows, (r) => r.id);
  for (let i = 0; i < unique.length; i += BATCH_SIZE) {
    const slice = unique.slice(i, i + BATCH_SIZE);
    await client.query(
      `INSERT INTO cloud_channels (
         account_id, device_code, id, name, stream_url, logo_url,
         group_title, category_id, kind, epg_channel_id,
         is_event_slot, event_start_ms, event_end_ms,
         source_id, tv_archive, tv_archive_duration, updated_at
       )
       SELECT $1::uuid, $2::text, u.id, u.name, u.stream_url, u.logo_url,
              u.group_title, u.category_id, 'live', u.epg_channel_id,
              u.is_event_slot, u.event_start_ms, u.event_end_ms,
              u.source_id, u.tv_archive, u.tv_archive_duration, NOW()
       FROM UNNEST(
         $3::text[], $4::text[], $5::text[], $6::text[],
         $7::text[], $8::text[], $9::text[],
         $10::boolean[], $11::bigint[], $12::bigint[],
         $13::text[], $14::boolean[], $15::integer[]
       ) AS u(id, name, stream_url, logo_url, group_title, category_id, epg_channel_id, is_event_slot, event_start_ms, event_end_ms, source_id, tv_archive, tv_archive_duration)
       ON CONFLICT (account_id, device_code, kind, id) DO UPDATE SET
         name = EXCLUDED.name,
         stream_url = EXCLUDED.stream_url,
         logo_url = EXCLUDED.logo_url,
         group_title = EXCLUDED.group_title,
         category_id = EXCLUDED.category_id,
         epg_channel_id = EXCLUDED.epg_channel_id,
         is_event_slot = EXCLUDED.is_event_slot,
         event_start_ms = EXCLUDED.event_start_ms,
         event_end_ms = EXCLUDED.event_end_ms,
         source_id = EXCLUDED.source_id,
         tv_archive = EXCLUDED.tv_archive,
         tv_archive_duration = EXCLUDED.tv_archive_duration,
         updated_at = NOW()`,
      [
        accountId,
        deviceCode,
        slice.map((r) => r.id),
        slice.map((r) => r.name),
        slice.map((r) => r.streamUrl),
        slice.map((r) => r.logoUrl),
        slice.map((r) => r.groupTitle),
        slice.map((r) => r.categoryId),
        slice.map((r) => r.epgChannelId),
        slice.map((r) => r.isEventSlot),
        slice.map((r) => r.eventStartMs),
        slice.map((r) => r.eventEndMs),
        slice.map((r) => r.sourceId || "primary"),
        slice.map((r) => Boolean(r.tvArchive)),
        slice.map((r) => Number(r.tvArchiveDuration || 0))
      ]
    );
  }
}

async function bulkInsertAutoEpg(client, accountId, deviceCode, rows) {
  await client.query(
    `DELETE FROM cloud_epg WHERE account_id = $1 AND device_code = $2 AND description = 'auto-parsed'`,
    [accountId, deviceCode]
  );
  for (let i = 0; i < rows.length; i += BATCH_SIZE) {
    const slice = rows.slice(i, i + BATCH_SIZE);
    await client.query(
      `INSERT INTO cloud_epg (account_id, device_code, channel_id, title, start_millis, end_millis, description, updated_at)
       SELECT $1::uuid, $2::text, u.channel_id, u.title, u.start_millis, u.end_millis, 'auto-parsed', NOW()
       FROM UNNEST($3::text[], $4::text[], $5::bigint[], $6::bigint[])
         AS u(channel_id, title, start_millis, end_millis)`,
      [
        accountId,
        deviceCode,
        slice.map((r) => r.channelId),
        slice.map((r) => r.title),
        slice.map((r) => r.startMs),
        slice.map((r) => r.endMs)
      ]
    );
  }
}

async function bulkUpsertVodChannels(client, accountId, deviceCode, rows) {
  const unique = dedupeByKey(rows, (r) => r.id);
  for (let i = 0; i < unique.length; i += BATCH_SIZE) {
    const slice = unique.slice(i, i + BATCH_SIZE);
    await client.query(
      `INSERT INTO cloud_channels (
         account_id, device_code, id, name, stream_url, logo_url,
         group_title, category_id, kind, updated_at
       )
       SELECT $1::uuid, $2::text, u.id, u.name, u.stream_url, u.logo_url,
              u.group_title, u.category_id, 'vod', NOW()
       FROM UNNEST($3::text[], $4::text[], $5::text[], $6::text[], $7::text[], $8::text[])
         AS u(id, name, stream_url, logo_url, group_title, category_id)
       ON CONFLICT (account_id, device_code, kind, id) DO UPDATE SET
         name = EXCLUDED.name,
         stream_url = EXCLUDED.stream_url,
         logo_url = EXCLUDED.logo_url,
         group_title = EXCLUDED.group_title,
         category_id = EXCLUDED.category_id,
         updated_at = NOW()`,
      [
        accountId,
        deviceCode,
        slice.map((r) => r.id),
        slice.map((r) => r.name),
        slice.map((r) => r.streamUrl),
        slice.map((r) => r.logoUrl),
        slice.map((r) => r.groupTitle),
        slice.map((r) => r.categoryId)
      ]
    );
  }
}

async function bulkUpsertSeries(client, accountId, deviceCode, rows) {
  const unique = dedupeByKey(rows, (r) => r.id);
  for (let i = 0; i < unique.length; i += BATCH_SIZE) {
    const slice = unique.slice(i, i + BATCH_SIZE);
    await client.query(
      `INSERT INTO cloud_channels (
         account_id, device_code, id, name, stream_url, logo_url,
         group_title, category_id, kind, series_id, updated_at
       )
       SELECT $1::uuid, $2::text, u.id, u.name, '', u.logo_url,
              u.group_title, u.category_id, 'series', u.id, NOW()
       FROM UNNEST($3::text[], $4::text[], $5::text[], $6::text[], $7::text[])
         AS u(id, name, logo_url, group_title, category_id)
       ON CONFLICT (account_id, device_code, kind, id) DO UPDATE SET
         name = EXCLUDED.name,
         logo_url = EXCLUDED.logo_url,
         group_title = EXCLUDED.group_title,
         category_id = EXCLUDED.category_id,
         updated_at = NOW()`,
      [
        accountId,
        deviceCode,
        slice.map((r) => r.id),
        slice.map((r) => r.name),
        slice.map((r) => r.logoUrl),
        slice.map((r) => r.groupTitle),
        slice.map((r) => r.categoryId)
      ]
    );
  }
}

async function syncAccountLogin(row) {
  const accountId = row.account_id;
  const deviceCode = row.device_code;
  const login = security.decryptLogin(row.login || {});

  const rawHost = login.xtreamHost || login.host || "";
  const providerHost = (login.providerHost || rawHost).trim().replace(/\/+$/, "");
  const username = login.xtreamUsername || login.username || "";
  const password = login.xtreamPassword || login.password || "";

  const hostOnly = (() => {
    try { return new URL(providerHost).host; } catch (_) { return ""; }
  })();

  if (!providerHost || !username || !password) {
    const detail = `Provider login is incomplete for ${deviceCode}: host=${hostOnly || "missing"} user=${username ? "set" : "missing"} password=${password ? "set" : "missing"}`;
    log.warn({ deviceCode, host: hostOnly }, detail);
    await writeSyncLog(accountId, deviceCode, "FAILED", {}, 0, detail).catch((err) => {
      log.error({ err: err.message }, "Could not write sync log");
    });
    return;
  }

  try {
    await security.assertSafeProviderUrl(providerHost);
  } catch (ssrfErr) {
    const detail = `Blocked unsafe provider URL for ${deviceCode}: ${ssrfErr.message}`;
    log.warn({ deviceCode, err: ssrfErr.message }, "SSRF guard blocked provider sync");
    await writeSyncLog(accountId, deviceCode, "FAILED", {}, 0, detail).catch(() => {});
    return;
  }

  const authQuery = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
  const startTime = Date.now();

  console.log(`[Worker] Syncing Xtream catalog for Account=${accountId}, Device=${deviceCode}, host=${hostOnly}...`);

  const [
    liveCatRaw,
    liveStreamRaw,
    vodCatRaw,
    vodStreamRaw,
    seriesCatRaw,
    seriesRaw
  ] = await Promise.all([
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_live_categories`),
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_live_streams`),
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_vod_categories`),
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_vod_streams`),
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_series_categories`),
    fetchJson(`${providerHost}/player_api.php?${authQuery}&action=get_series`)
  ]);

  const liveCatResult = asList(liveCatRaw, "Live categories");
  const liveStreamResult = asList(liveStreamRaw, "Live channels");
  const vodCatResult = asList(vodCatRaw, "Movie categories");
  const vodStreamResult = asList(vodStreamRaw, "Movies");
  const seriesCatResult = asList(seriesCatRaw, "Show categories");
  const seriesResult = asList(seriesRaw, "Shows");

  const problems = [liveCatResult, liveStreamResult, vodCatResult, vodStreamResult, seriesCatResult, seriesResult]
    .map((item) => item.error)
    .filter(Boolean);
  if (liveStreamResult.list == null) {
    const detail = `Live channels did not load from ${hostOnly || "provider"}. ${problems.join(" | ")}`;
    console.error(`[Worker] ${detail}`);
    await writeSyncLog(accountId, deviceCode, "FAILED", {}, Date.now() - startTime, detail).catch((err) => {
      console.error("[Worker] Could not write sync log:", err.message);
    });
    return;
  }

  const liveCats = liveCatResult.list || [];
  const liveStreams = liveStreamResult.list;
  const vodCats = vodCatResult.list || [];
  const vodStreams = vodStreamResult.list || [];
  const seriesCats = seriesCatResult.list || [];
  const seriesList = seriesResult.list || [];

  const liveCatMap = new Map(liveCats.filter((c) => c && c.category_id).map((c) => [String(c.category_id), String(c.category_name || "")]));
  const vodCatMap = new Map(vodCats.filter((c) => c && c.category_id).map((c) => [String(c.category_id), String(c.category_name || "")]));
  const seriesCatMap = new Map(seriesCats.filter((c) => c && c.category_id).map((c) => [String(c.category_id), String(c.category_name || "")]));

  const encUser = encodeURIComponent(username);
  const encPass = encodeURIComponent(password);
  const syncNow = Date.now();
  const parsedEvents = [];
  const liveRows = [];
  const liveIds = [];

  for (const stream of liveStreams) {
    if (!stream || !stream.stream_id) continue;
    const streamId = String(stream.stream_id);
    liveIds.push(streamId);
    const streamName = String(stream.name || "");
    const ev = eventParser.inspectEvent(streamName, syncNow);
    const catId = String(stream.category_id || "");
    liveRows.push({
      id: streamId,
      name: streamName,
      streamUrl: `${providerHost}/live/${encUser}/${encPass}/${streamId}.ts`,
      logoUrl: stream.stream_icon || null,
      groupTitle: stream.category_name || liveCatMap.get(catId) || null,
      categoryId: catId,
      epgChannelId: stream.epg_channel_id || null,
      isEventSlot: Boolean(ev.isEventSlot),
      eventStartMs: Number(ev.eventStartMs || 0),
      eventEndMs: Number(ev.eventEndMs || 0),
      sourceId: "primary",
      tvArchive: Number(stream.tv_archive || 0) === 1,
      tvArchiveDuration: Number(stream.tv_archive_duration || 0)
    });
    if (ev.isEventSlot && ev.eventStartMs > 0 && ev.eventTitle) {
      parsedEvents.push({
        channelId: stream.epg_channel_id || streamId,
        title: ev.eventTitle,
        startMs: ev.eventStartMs,
        endMs: ev.eventEndMs
      });
    }
  }

  const vodRows = [];
  for (const stream of vodStreams) {
    if (!stream || !stream.stream_id) continue;
    const streamId = String(stream.stream_id);
    const ext = stream.container_extension || "mp4";
    const catId = String(stream.category_id || "");
    vodRows.push({
      id: streamId,
      name: String(stream.name || ""),
      streamUrl: `${providerHost}/movie/${encUser}/${encPass}/${streamId}.${ext}`,
      logoUrl: stream.stream_icon || null,
      groupTitle: stream.category_name || vodCatMap.get(catId) || null,
      categoryId: catId
    });
  }

  const seriesRows = [];
  for (const series of seriesList) {
    if (!series || !series.series_id) continue;
    const catId = String(series.category_id || "");
    seriesRows.push({
      id: String(series.series_id),
      name: String(series.name || ""),
      logoUrl: series.cover || null,
      groupTitle: series.category_name || seriesCatMap.get(catId) || null,
      categoryId: catId
    });
  }

  const client = await pool.connect();
  try {

    await client.query("BEGIN");
    await bulkUpsertCategories(client, accountId, deviceCode, "live", liveCats);
    await bulkUpsertCategories(client, accountId, deviceCode, "vod", vodCats);
    await bulkUpsertCategories(client, accountId, deviceCode, "series", seriesCats);
    await bulkUpsertLiveChannels(client, accountId, deviceCode, liveRows);
    if (liveIds.length >= 100) {
      await client.query(
        `DELETE FROM cloud_channels
         WHERE account_id = $1 AND device_code = $2 AND kind = 'live' AND NOT (id = ANY($3::text[]))`,
        [accountId, deviceCode, liveIds]
      );
    }
    if (parsedEvents.length > 0) {
      await bulkInsertAutoEpg(client, accountId, deviceCode, parsedEvents);
    }
    await client.query(
      `SELECT pg_notify('olympus_sync', json_build_object('table', 'cloud_channels', 'account_id', $1::text, 'device_code', $2::text)::text)`,
      [accountId, deviceCode]
    );
    await client.query("COMMIT");
    console.log(`[Worker] Stage 1 committed for ${deviceCode}: Live=${liveRows.length}, EventsEPG=${parsedEvents.length} (${Date.now() - startTime}ms)`);

    if (vodRows.length > 0) {
      await client.query("BEGIN");
      await bulkUpsertVodChannels(client, accountId, deviceCode, vodRows);
      if (vodRows.length >= 100) {
        await client.query(
          `DELETE FROM cloud_channels
           WHERE account_id = $1 AND device_code = $2 AND kind = 'vod' AND NOT (id = ANY($3::text[]))`,
          [accountId, deviceCode, vodRows.map((r) => r.id)]
        );
      }
      if (vodCats.length > 0) {
        await client.query(
          `DELETE FROM cloud_categories
           WHERE account_id = $1 AND device_code = $2 AND kind = 'vod' AND NOT (id = ANY($3::text[]))`,
          [accountId, deviceCode, vodCats.filter((c) => c && c.category_id).map((c) => String(c.category_id))]
        );
      }
      await client.query("COMMIT");
      log.info({ deviceCode, vod: vodRows.length, elapsedMs: Date.now() - startTime }, "Stage 2 committed");
    }

    await client.query("BEGIN");
    if (seriesRows.length > 0) {
      await bulkUpsertSeries(client, accountId, deviceCode, seriesRows);
      if (seriesRows.length >= 50) {
        await client.query(
          `DELETE FROM cloud_channels
           WHERE account_id = $1 AND device_code = $2 AND kind = 'series' AND NOT (id = ANY($3::text[]))`,
          [accountId, deviceCode, seriesRows.map((r) => r.id)]
        );
      }
      if (seriesCats.length > 0) {
        await client.query(
          `DELETE FROM cloud_categories
           WHERE account_id = $1 AND device_code = $2 AND kind = 'series' AND NOT (id = ANY($3::text[]))`,
          [accountId, deviceCode, seriesCats.filter((c) => c && c.category_id).map((c) => String(c.category_id))]
        );
      }
    }
    if (liveCats.length > 0) {
      await client.query(
        `DELETE FROM cloud_categories
         WHERE account_id = $1 AND device_code = $2 AND kind = 'live' AND NOT (id = ANY($3::text[]))`,
        [accountId, deviceCode, liveCats.filter((c) => c && c.category_id).map((c) => String(c.category_id))]
      );
    }
    await client.query(`DELETE FROM cloud_sync_logs WHERE ran_at < NOW() - INTERVAL '30 days'`);
    await client.query(`DELETE FROM error_logs WHERE created_at < NOW() - INTERVAL '30 days'`);

    const duration = Date.now() - startTime;
    const status = liveRows.length === 0 ? "EMPTY" : "SUCCESS";
    const detail = liveRows.length === 0
      ? `Provider ${hostOnly || "host"} returned 0 live channels. ${problems.join(" | ")}`.trim()
      : (problems.length ? `Live loaded. Other sections: ${problems.join(" | ")}` : "");
    await client.query(
      `INSERT INTO cloud_sync_logs (account_id, device_code, status, live_count, vod_count, series_count, duration_ms, detail, ran_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, NOW())`,
      [accountId, deviceCode, status, liveRows.length, vodRows.length, seriesRows.length, duration, detail || null]
    );
    if (status !== "SUCCESS") {
      await client.query(
        `INSERT INTO error_logs (account_id, device_code, platform, tag, message, detail, app_version, created_at)
         VALUES ($1, $2, 'worker', 'CatalogSync', $3, $4, 'worker', NOW())`,
        [accountId, deviceCode, detail, JSON.stringify({ status, live: liveRows.length, vod: vodRows.length, series: seriesRows.length, host: hostOnly })]
      );
    }

    await client.query(
      `SELECT pg_notify('olympus_sync', json_build_object('table', 'cloud_channels', 'account_id', $1::text, 'device_code', $2::text)::text)`,
      [accountId, deviceCode]
    );
    await client.query("COMMIT");
    console.log(`[Worker] Sync complete for ${deviceCode}: Live=${liveRows.length}, VOD=${vodRows.length}, Series=${seriesRows.length} (${duration}ms)`);
  } catch (err) {
    await client.query("ROLLBACK").catch(() => {});
    const detail = `Catalog sync crashed for ${hostOnly || deviceCode}: ${String(err.message || err).slice(0, 500)}`;
    console.error(`[Worker] ${detail}`);
    await writeSyncLog(accountId, deviceCode, "FAILED", {
      live: Array.isArray(liveStreams) ? liveStreams.length : 0,
      vod: Array.isArray(vodStreams) ? vodStreams.length : 0,
      series: Array.isArray(seriesList) ? seriesList.length : 0
    }, Date.now() - startTime, detail).catch(() => {});
  } finally {
    client.release();
  }
}

const activeSyncs = new Set();

async function withSyncAdvisoryLock(accountId, deviceCode, fn) {
  const lockKey = `olympus_sync:${accountId}:${deviceCode}`;
  const lockClient = await pool.connect();
  let acquired = false;
  try {
    const { rows } = await lockClient.query(
      `SELECT pg_try_advisory_lock(hashtext($1)) AS locked`,
      [lockKey]
    );
    acquired = Boolean(rows[0] && rows[0].locked);
    if (!acquired) {
      log.info({ accountId, deviceCode }, "Skipping sync; another worker instance already holds the advisory lock");
      return;
    }
    await fn();
  } finally {
    if (acquired) {
      await lockClient.query(`SELECT pg_advisory_unlock(hashtext($1))`, [lockKey]).catch(() => {});
    }
    lockClient.release();
  }
}

async function syncBackupSources(accountId, deviceCode) {
  const { rows } = await pool.query(
    `SELECT id, label, login FROM provider_sources
     WHERE account_id = $1 AND device_code = $2 AND enabled = TRUE
     ORDER BY priority ASC`,
    [accountId, deviceCode]
  );
  for (const src of rows) {
    try {
      const login = security.decryptLogin(src.login || {});
      if (login.mode === "m3u") continue;
      const host = String(login.server || login.xtreamHost || "").replace(/\/+$/, "");
      const user = login.username || login.xtreamUser || "";
      const pass = login.password || login.xtreamPassword || "";
      if (!host || !user || !pass) continue;
      await security.assertSafeProviderUrl(host);
      const authQuery = `username=${encodeURIComponent(user)}&password=${encodeURIComponent(pass)}`;
      const liveRaw = await fetchJson(`${host}/player_api.php?${authQuery}&action=get_live_streams`);
      const liveList = asList(liveRaw, "Backup live channels").list || [];
      if (!liveList.length) continue;
      const syncNow = Date.now();
      const encUser = encodeURIComponent(user);
      const encPass = encodeURIComponent(pass);
      const backupRows = [];
      for (const stream of liveList) {
        if (!stream || !stream.stream_id) continue;
        const rawId = String(stream.stream_id);
        const streamName = String(stream.name || "");
        const ev = eventParser.inspectEvent(streamName, syncNow);
        backupRows.push({
          id: `${src.id}:${rawId}`,
          name: streamName,
          streamUrl: `${host}/live/${encUser}/${encPass}/${rawId}.ts`,
          logoUrl: stream.stream_icon || null,
          groupTitle: stream.category_name || src.label || "Backup",
          categoryId: `${src.id}:${String(stream.category_id || "")}`,
          epgChannelId: stream.epg_channel_id || null,
          isEventSlot: Boolean(ev.isEventSlot),
          eventStartMs: Number(ev.eventStartMs || 0),
          eventEndMs: Number(ev.eventEndMs || 0),
          sourceId: src.id,
          tvArchive: Number(stream.tv_archive || 0) === 1,
          tvArchiveDuration: Number(stream.tv_archive_duration || 0)
        });
      }
      const client = await pool.connect();
      try {
        await bulkUpsertLiveChannels(client, accountId, deviceCode, backupRows);
      } finally {
        client.release();
      }
    } catch (err) {
      log.warn({ sourceId: src.id, err: err.message }, "backup provider sync failed");
    }
  }
}

async function syncDeviceByKey(accountId, deviceCode) {
  const key = `${accountId}:${deviceCode}`;
  if (activeSyncs.has(key)) return;
  activeSyncs.add(key);
  try {
    const { rows } = await pool.query(
      `SELECT account_id, device_code, login FROM provider_logins WHERE account_id = $1 AND device_code = $2`,
      [accountId, deviceCode]
    );
    for (const row of rows) {
      await withSyncAdvisoryLock(row.account_id, row.device_code, async () => {
        await syncAccountLogin(row);
        await syncBackupSources(row.account_id, row.device_code);
      });
    }
  } finally {
    activeSyncs.delete(key);
  }
}

async function runWorkerCycle() {
  console.log(`[Worker] Cycle started at ${new Date().toISOString()}`);
  try {
    await ensureDispatchSchema(pool).catch(() => {});
    await pool.query(`DELETE FROM stream_telemetry WHERE updated_at < NOW() - INTERVAL '7 days'`).catch(() => {});
    const { rows } = await pool.query(`SELECT account_id, device_code, login FROM provider_logins`);
    for (const row of rows) {
      const key = `${row.account_id}:${row.device_code}`;
      if (activeSyncs.has(key)) continue;
      activeSyncs.add(key);
      try {
        await withSyncAdvisoryLock(row.account_id, row.device_code, async () => {
          await syncAccountLogin(row);
          await syncBackupSources(row.account_id, row.device_code);
        });
      } finally {
        activeSyncs.delete(key);
      }
    }
  } catch (err) {
    console.error("[Worker] Error in worker cycle:", err.message);
  }
}

let listenClient = null;
let cycleTimer = null;
let shuttingDown = false;

async function listenForNewLogins() {
  if (shuttingDown) return;
  try {
    const listener = await pool.connect();
    listenClient = listener;
    await listener.query("LISTEN olympus_sync");
    listener.on("notification", (msg) => {
      try {
        const payload = JSON.parse(msg.payload || "{}");
        if (payload.table === "provider_logins" && payload.account_id && payload.device_code) {
          log.info({ deviceCode: payload.device_code }, "New provider login detected; syncing immediately");
          syncDeviceByKey(payload.account_id, payload.device_code).catch((err) => {
            log.error({ err: err.message, deviceCode: payload.device_code }, "Immediate sync error");
          });
        }
      } catch (_) {}
    });
    listener.on("error", (err) => {
      if (shuttingDown) return;
      log.error({ err: err.message }, "LISTEN connection error, reconnecting in 5s");
      try { listener.release(true); } catch (_) {}
      listenClient = null;
      setTimeout(listenForNewLogins, 5000);
    });
    log.info("Listening on PostgreSQL channel olympus_sync for instant provider_logins ingestion");
  } catch (err) {
    if (shuttingDown) return;
    log.error({ err: err.message }, "LISTEN setup failed, retrying in 10s");
    setTimeout(listenForNewLogins, 10000);
  }
}

async function ensureColumn(table, column, definition) {
  const { rows } = await pool.query(
    `SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = $1 AND column_name = $2`,
    [table, column]
  );
  if (rows.length === 0) {
    await pool.query(`ALTER TABLE ${table} ADD COLUMN IF NOT EXISTS ${column} ${definition}`);
  }
}

async function migratePlaintextLogins() {
  if (!security.encryptionEnabled) return;
  try {
    const { rows } = await pool.query(`SELECT account_id, device_code, login FROM provider_logins`);
    let migrated = 0;
    for (const row of rows) {
      const login = row.login || {};
      const needsEncrypt =
        (login.password && !String(login.password).startsWith("enc:v1:")) ||
        (login.xtreamPassword && !String(login.xtreamPassword).startsWith("enc:v1:")) ||
        (login.mode === "m3u" && login.url && !String(login.url).startsWith("enc:v1:"));
      if (!needsEncrypt) continue;
      const encrypted = security.encryptLogin(login);
      await pool.query(
        `UPDATE provider_logins SET login = $1::jsonb, updated_at = NOW() WHERE account_id = $2 AND device_code = $3`,
        [JSON.stringify(encrypted), row.account_id, row.device_code]
      );
      migrated++;
    }
    if (migrated > 0) {
      log.info({ migrated }, "Encrypted legacy plaintext provider_logins with AES-256-GCM");
    }
  } catch (err) {
    log.error({ err: err.message }, "Plaintext provider_logins migration error");
  }
}

async function startWorker() {
  await ensureColumn("cloud_sync_logs", "detail", "TEXT");
  await ensureColumn("error_logs", "detail", "TEXT");
  await ensureColumn("cloud_channels", "is_event_slot", "BOOLEAN NOT NULL DEFAULT FALSE");
  await ensureColumn("cloud_channels", "event_start_ms", "BIGINT NOT NULL DEFAULT 0");
  await ensureColumn("cloud_channels", "event_end_ms", "BIGINT NOT NULL DEFAULT 0");
  await migratePlaintextLogins();
  await listenForNewLogins();
  await runWorkerCycle();
  cycleTimer = setInterval(runWorkerCycle, SYNC_INTERVAL_MS);
}

async function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  log.info({ signal }, "Worker shutting down gracefully");
  if (cycleTimer) clearInterval(cycleTimer);
  if (listenClient) {
    try { await listenClient.query("UNLISTEN *"); } catch (_) {}
    try { listenClient.release(true); } catch (_) {}
    listenClient = null;
  }
  try { await pool.end(); } catch (_) {}
  process.exit(0);
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

startWorker().catch((err) => {
  log.error({ err: err.message }, "Worker startup failed");
});

