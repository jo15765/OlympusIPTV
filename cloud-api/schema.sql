-- ============================================================================
-- Olympus-IPTV Cloud Sync Schema v2 (PostgreSQL 14+ - Security Hardened)
-- Includes:
--   1) Zero-Trust Device Token Hashes + 6-Digit Pairing Codes
--   2) Real-Time LISTEN/NOTIFY Triggers (olympus_sync)
--   3) Automatic Revision History / Undo Snapshots (last 5 revisions per device)
--   4) Cross-Device Watch History, Remote DVR & Program Reminders
--   5) Multi-TV Profile Cloning & Shared Household Profiles
--   6) Centralized Ingestion Cloud Catalog (Automated 30m Xtream Worker Sync)
--   7) End-User Error & Crash Logs Uploaded from Devices
-- ============================================================================

-- 1. User Accounts
CREATE TABLE IF NOT EXISTS accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_accounts_email_lower CHECK (email = LOWER(TRIM(email)))
);

-- 2. Registered Devices with SHA-256 Token Auth & 6-Digit TV Pairing Codes
CREATE TABLE IF NOT EXISTS devices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id          UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code         TEXT NOT NULL,
    device_name         TEXT NOT NULL DEFAULT '',
    platform            TEXT NOT NULL DEFAULT 'android',
    token_hash          TEXT,
    pairing_code        TEXT,
    pairing_expires_at  TIMESTAMPTZ,
    shared_mode         BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (account_id, device_code)
);

ALTER TABLE devices ADD COLUMN IF NOT EXISTS token_hash TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS pairing_code TEXT;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS pairing_expires_at TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS shared_mode BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_devices_account ON devices(account_id);
CREATE INDEX IF NOT EXISTS idx_devices_pairing ON devices(pairing_code) WHERE pairing_code IS NOT NULL;

-- 3. Lineup Configuration
CREATE TABLE IF NOT EXISTS lineup_configs (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    revision        BIGINT NOT NULL DEFAULT 0,
    config          JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code),
    CONSTRAINT chk_lineup_config_object CHECK (jsonb_typeof(config) = 'object')
);

-- 4. Automatic Lineup Revision History (Keeps last 5 snapshots per device for 1-click Undo)
CREATE TABLE IF NOT EXISTS lineup_history (
    id              BIGSERIAL PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    revision        BIGINT NOT NULL,
    config          JSONB NOT NULL,
    saved_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lineup_history_lookup
    ON lineup_history(account_id, device_code, saved_at DESC);

CREATE OR REPLACE FUNCTION archive_lineup_snapshot() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.revision IS DISTINCT FROM NEW.revision THEN
        INSERT INTO lineup_history (account_id, device_code, revision, config, saved_at)
        VALUES (OLD.account_id, OLD.device_code, OLD.revision, OLD.config, NOW());

        DELETE FROM lineup_history
        WHERE id IN (
            SELECT id FROM lineup_history
            WHERE account_id = OLD.account_id AND device_code = OLD.device_code
            ORDER BY saved_at DESC
            OFFSET 5
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_lineup_archive ON lineup_configs;
CREATE TRIGGER trg_lineup_archive
    BEFORE UPDATE ON lineup_configs
    FOR EACH ROW EXECUTE FUNCTION archive_lineup_snapshot();

-- 5. Ordered Favorites & Custom Labels
CREATE TABLE IF NOT EXISTS favorites (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL DEFAULT '__shared__',
    revision        BIGINT NOT NULL DEFAULT 0,
    ids             JSONB NOT NULL DEFAULT '[]'::jsonb,
    removed         JSONB NOT NULL DEFAULT '[]'::jsonb,
    labels          JSONB NOT NULL DEFAULT '{}'::jsonb,
    logos           JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code),
    CONSTRAINT chk_favorites_ids_array CHECK (jsonb_typeof(ids) = 'array'),
    CONSTRAINT chk_favorites_removed_array CHECK (jsonb_typeof(removed) = 'array')
);

-- 6. Cross-Device Continue Watching & Watch History
CREATE TABLE IF NOT EXISTS watch_history (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    channel_id      TEXT NOT NULL,
    series_id       TEXT,
    season          INTEGER,
    name            TEXT NOT NULL,
    section         TEXT NOT NULL DEFAULT 'live',
    position_ms     BIGINT NOT NULL DEFAULT 0,
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_history_recent
    ON watch_history(account_id, updated_at DESC);

-- 7. Remote DVR Scheduled Recordings & SMB Settings
CREATE TABLE IF NOT EXISTS recording_books (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    revision        BIGINT NOT NULL DEFAULT 0,
    book            JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code),
    CONSTRAINT chk_recording_book_object CHECK (jsonb_typeof(book) = 'object')
);

-- 8. Remote Program Reminders
CREATE TABLE IF NOT EXISTS reminders (
    id              TEXT NOT NULL,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    channel_id      TEXT NOT NULL,
    channel_number  INTEGER NOT NULL DEFAULT 0,
    channel_name    TEXT NOT NULL DEFAULT '',
    title           TEXT NOT NULL DEFAULT '',
    start_millis    BIGINT NOT NULL,
    lead_minutes    INTEGER NOT NULL DEFAULT 15,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code, id)
);

CREATE INDEX IF NOT EXISTS idx_reminders_upcoming
    ON reminders(account_id, device_code, start_millis);

-- 9. Lightweight Catalog Summary Snapshot
CREATE TABLE IF NOT EXISTS catalog_snapshots (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    library_stamp   BIGINT NOT NULL DEFAULT 0,
    channel_count   INTEGER NOT NULL DEFAULT 0,
    groups          JSONB NOT NULL DEFAULT '[]'::jsonb,
    countries       JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code),
    CONSTRAINT chk_snapshot_groups_array CHECK (jsonb_typeof(groups) = 'array'),
    CONSTRAINT chk_snapshot_countries_array CHECK (jsonb_typeof(countries) = 'array')
);

-- 10. Real-Time Push Notification Trigger (LISTEN / NOTIFY)
CREATE OR REPLACE FUNCTION notify_lineup_change() RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify(
        'olympus_sync',
        json_build_object(
            'table', TG_TABLE_NAME,
            'account_id', NEW.account_id,
            'device_code', NEW.device_code,
            'revision', COALESCE(NEW.revision, 0)
        )::text
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_lineup_notify ON lineup_configs;
CREATE TRIGGER trg_lineup_notify
    AFTER INSERT OR UPDATE ON lineup_configs
    FOR EACH ROW EXECUTE FUNCTION notify_lineup_change();

DROP TRIGGER IF EXISTS trg_favorites_notify ON favorites;
CREATE TRIGGER trg_favorites_notify
    AFTER INSERT OR UPDATE ON favorites
    FOR EACH ROW EXECUTE FUNCTION notify_lineup_change();

DROP TRIGGER IF EXISTS trg_recordings_notify ON recording_books;
CREATE TRIGGER trg_recordings_notify
    AFTER INSERT OR UPDATE ON recording_books
    FOR EACH ROW EXECUTE FUNCTION notify_lineup_change();

-- 11. Provider login saved from the web dashboard and pulled by the device
CREATE TABLE IF NOT EXISTS provider_logins (
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    revision        BIGINT NOT NULL DEFAULT 0,
    login           JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code),
    CONSTRAINT chk_provider_login_object CHECK (jsonb_typeof(login) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_provider_logins_device ON provider_logins(device_code);

DROP TRIGGER IF EXISTS trg_provider_login_notify ON provider_logins;
CREATE TRIGGER trg_provider_login_notify
    AFTER INSERT OR UPDATE ON provider_logins
    FOR EACH ROW EXECUTE FUNCTION notify_lineup_change();

-- 12. Centralized Ingestion Cloud Catalog (Automated 30m Xtream Worker Sync)
CREATE TABLE IF NOT EXISTS cloud_categories (
    id              TEXT NOT NULL,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL DEFAULT '__shared__',
    name            TEXT NOT NULL,
    kind            TEXT NOT NULL DEFAULT 'live',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code, kind, id)
);

CREATE TABLE IF NOT EXISTS cloud_channels (
    id              TEXT NOT NULL,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL DEFAULT '__shared__',
    name            TEXT NOT NULL,
    stream_url      TEXT NOT NULL,
    logo_url        TEXT,
    group_title     TEXT,
    category_id     TEXT,
    kind            TEXT NOT NULL DEFAULT 'live',
    series_id       TEXT,
    season          INTEGER,
    epg_channel_id  TEXT,
    is_event_slot   BOOLEAN NOT NULL DEFAULT FALSE,
    event_start_ms  BIGINT NOT NULL DEFAULT 0,
    event_end_ms    BIGINT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (account_id, device_code, kind, id)
);

CREATE INDEX IF NOT EXISTS idx_cloud_channels_kind ON cloud_channels(account_id, device_code, kind);
CREATE INDEX IF NOT EXISTS idx_cloud_channels_live_active ON cloud_channels(account_id, device_code, is_event_slot, event_start_ms, event_end_ms) WHERE kind = 'live';
CREATE INDEX IF NOT EXISTS idx_cloud_channels_name_fts ON cloud_channels USING gin (to_tsvector('simple', name));

CREATE TABLE IF NOT EXISTS cloud_epg (
    id              BIGSERIAL PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL DEFAULT '__shared__',
    channel_id      TEXT NOT NULL,
    title           TEXT NOT NULL,
    start_millis    BIGINT NOT NULL,
    end_millis      BIGINT NOT NULL,
    description     TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cloud_epg_window ON cloud_epg(account_id, device_code, channel_id, start_millis);

CREATE TABLE IF NOT EXISTS cloud_sync_logs (
    id              BIGSERIAL PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    status          TEXT NOT NULL,
    live_count      INTEGER NOT NULL DEFAULT 0,
    vod_count       INTEGER NOT NULL DEFAULT 0,
    series_count    INTEGER NOT NULL DEFAULT 0,
    duration_ms     BIGINT NOT NULL DEFAULT 0,
    detail          TEXT,
    ran_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 13. End-User Error & Crash Logs Uploaded from Devices
CREATE TABLE IF NOT EXISTS error_logs (
    id              BIGSERIAL PRIMARY KEY,
    account_id      UUID REFERENCES accounts(id) ON DELETE SET NULL,
    device_code     TEXT NOT NULL DEFAULT '__shared__',
    platform        TEXT NOT NULL DEFAULT 'android',
    tag             TEXT NOT NULL DEFAULT 'ERROR',
    message         TEXT NOT NULL,
    stack_trace     TEXT,
    app_version     TEXT,
    detail          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_error_logs_created ON error_logs(created_at DESC);
