-- FamilyCircle initial schema.
-- Conventions: UUID primary keys, timestamptz everywhere (UTC), secrets only as SHA-256 hashes,
-- coordinates only as PostGIS geography(Point, 4326).

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS citext;

-- Singleton row with instance-wide settings.
CREATE TABLE instance_settings (
    id                boolean PRIMARY KEY DEFAULT true CHECK (id),
    registration_mode text NOT NULL DEFAULT 'invite_only'
                      CHECK (registration_mode IN ('invite_only', 'closed')),
    created_at        timestamptz NOT NULL DEFAULT now()
);
INSERT INTO instance_settings DEFAULT VALUES;

CREATE TABLE users (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email             citext NOT NULL UNIQUE CHECK (char_length(email) BETWEEN 3 AND 254),
    display_name      text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 64),
    password_hash     text NOT NULL,
    locale            text NOT NULL DEFAULT 'de' CHECK (locale IN ('de', 'en')),
    is_instance_admin boolean NOT NULL DEFAULT false,
    -- Only adult accounts exist today. Managed child accounts are designed in ADR-0006.
    account_type      text NOT NULL DEFAULT 'adult' CHECK (account_type IN ('adult')),
    avatar_jpeg       bytea CHECK (avatar_jpeg IS NULL OR octet_length(avatar_jpeg) <= 262144),
    avatar_updated_at timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

-- Privacy settings. Every default is the most private option.
CREATE TABLE user_settings (
    user_id                uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    sharing_paused         boolean NOT NULL DEFAULT false,
    history_enabled        boolean NOT NULL DEFAULT false,
    history_retention_days integer NOT NULL DEFAULT 30 CHECK (history_retention_days BETWEEN 1 AND 365),
    share_battery          boolean NOT NULL DEFAULT false,
    updated_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    platform     text NOT NULL CHECK (platform IN ('android', 'other')),
    app_version  text CHECK (app_version IS NULL OR char_length(app_version) <= 32),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz,
    revoked_at   timestamptz
);
CREATE INDEX devices_user_idx ON devices (user_id);

-- Server-side sessions. Web sessions use only the access token (cookie, sliding expiry);
-- device sessions additionally use a rotating refresh token.
CREATE TABLE sessions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id          uuid REFERENCES devices (id) ON DELETE CASCADE,
    kind               text NOT NULL CHECK (kind IN ('web', 'device')),
    access_hash        bytea NOT NULL UNIQUE,
    access_expires_at  timestamptz NOT NULL,
    refresh_hash       bytea UNIQUE,
    prev_refresh_hash  bytea,
    refresh_rotated_at timestamptz,
    refresh_expires_at timestamptz,
    user_agent         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    last_used_at       timestamptz NOT NULL DEFAULT now(),
    revoked_at         timestamptz,
    CHECK ((kind = 'device') = (device_id IS NOT NULL))
);
CREATE INDEX sessions_user_idx ON sessions (user_id);
CREATE INDEX sessions_device_idx ON sessions (device_id) WHERE device_id IS NOT NULL;
CREATE INDEX sessions_prev_refresh_idx ON sessions (prev_refresh_hash) WHERE prev_refresh_hash IS NOT NULL;

CREATE TABLE families (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Membership and role. The role grants administration of the family (name, invites,
-- members) but never access to anyone's location.
CREATE TABLE family_members (
    family_id uuid NOT NULL REFERENCES families (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      text NOT NULL CHECK (role IN ('admin', 'member')),
    color     text NOT NULL CHECK (color ~ '^#[0-9a-f]{6}$'),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (family_id, user_id)
);
CREATE INDEX family_members_user_idx ON family_members (user_id);

CREATE TABLE invites (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id  uuid NOT NULL REFERENCES families (id) ON DELETE CASCADE,
    code_hash  bytea NOT NULL UNIQUE,
    code_hint  text NOT NULL,
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    max_uses   integer NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 20),
    use_count  integer NOT NULL DEFAULT 0 CHECK (use_count >= 0 AND use_count <= max_uses),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invites_family_idx ON invites (family_id);

-- Explicit, per-viewer opt-in: owner shares their location with viewer.
-- Visibility additionally requires a common family and sharing_paused = false (see store/visibility.go).
CREATE TABLE location_shares (
    owner_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewer_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, viewer_id),
    CHECK (owner_id <> viewer_id)
);
CREATE INDEX location_shares_viewer_idx ON location_shares (viewer_id);

-- Last known position per user (always stored while the user uploads).
-- candidate_* holds the most recent implausible fix, used to confirm real jumps (e.g. after a flight).
CREATE TABLE locations_latest (
    user_id               uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    device_id             uuid REFERENCES devices (id) ON DELETE SET NULL,
    position              geography(Point, 4326) NOT NULL,
    accuracy_m            real NOT NULL CHECK (accuracy_m > 0),
    altitude_m            real,
    speed_mps             real CHECK (speed_mps IS NULL OR speed_mps >= 0),
    bearing_deg           real CHECK (bearing_deg IS NULL OR (bearing_deg >= 0 AND bearing_deg < 360)),
    battery_pct           smallint CHECK (battery_pct IS NULL OR battery_pct BETWEEN 0 AND 100),
    battery_charging      boolean,
    motion                text NOT NULL DEFAULT 'unknown'
                          CHECK (motion IN ('unknown', 'stationary', 'walking', 'driving')),
    recorded_at           timestamptz NOT NULL,
    received_at           timestamptz NOT NULL DEFAULT now(),
    candidate_position    geography(Point, 4326),
    candidate_accuracy_m  real,
    candidate_recorded_at timestamptz
);

-- Full history, written only when user_settings.history_enabled = true.
CREATE TABLE location_history (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id        uuid REFERENCES devices (id) ON DELETE SET NULL,
    client_event_id  uuid NOT NULL,
    position         geography(Point, 4326) NOT NULL,
    accuracy_m       real NOT NULL CHECK (accuracy_m > 0),
    altitude_m       real,
    speed_mps        real,
    bearing_deg      real,
    battery_pct      smallint,
    battery_charging boolean,
    motion           text NOT NULL DEFAULT 'unknown',
    suspect          boolean NOT NULL DEFAULT false,
    recorded_at      timestamptz NOT NULL,
    received_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, client_event_id)
);
CREATE INDEX location_history_user_time_idx ON location_history (user_id, recorded_at DESC);
