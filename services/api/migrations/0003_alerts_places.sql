-- Phase 3: alerts (SOS, check-in, arrival/departure, battery), remote requests (ring, locate),
-- places with server-side presence, and per-viewer history sharing.
-- Privacy rules: docs/THREAT_MODEL.md and store/alerts.go (audiences are computed in SQL).

-- Opt-ins of the target person (default off) and the battery notice (default on, goes only to
-- people who can see the location anyway).
ALTER TABLE user_settings
    ADD COLUMN allow_ring           boolean NOT NULL DEFAULT false,
    ADD COLUMN allow_locate_request boolean NOT NULL DEFAULT false,
    ADD COLUMN battery_alerts       boolean NOT NULL DEFAULT true;

-- Owner additionally lets viewer see their history. Only effective while the location itself is
-- visible (visibleSQL) and the owner has history enabled.
ALTER TABLE location_shares ADD COLUMN history boolean NOT NULL DEFAULT false;

CREATE TABLE places (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id  uuid NOT NULL REFERENCES families (id) ON DELETE CASCADE,
    name       text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 64),
    position   geography(Point, 4326) NOT NULL,
    radius_m   integer NOT NULL CHECK (radius_m BETWEEN 50 AND 2000),
    created_by uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX places_family_idx ON places (family_id);

-- Last known inside/outside state per place and member (hysteresis, see store/places.go).
CREATE TABLE place_presence (
    place_id   uuid NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    inside     boolean NOT NULL,
    changed_at timestamptz NOT NULL,
    PRIMARY KEY (place_id, user_id)
);
CREATE INDEX place_presence_user_idx ON place_presence (user_id);

-- A member does not want arrival/departure alerts for this place.
CREATE TABLE place_mutes (
    place_id uuid NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (place_id, user_id)
);

CREATE TABLE alerts (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind            text NOT NULL CHECK (kind IN ('sos', 'sos_resolved', 'check_in', 'arrival', 'departure',
                                                  'battery_low', 'last_location', 'ring', 'locate')),
    actor_id        uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_device_id uuid REFERENCES devices (id) ON DELETE SET NULL,
    place_id        uuid REFERENCES places (id) ON DELETE SET NULL,
    place_name      text,
    position        geography(Point, 4326),
    accuracy_m      real CHECK (accuracy_m IS NULL OR accuracy_m > 0),
    battery_pct     smallint CHECK (battery_pct IS NULL OR battery_pct BETWEEN 0 AND 100),
    message         text CHECK (message IS NULL OR char_length(message) BETWEEN 1 AND 140),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz,
    resolved_at     timestamptz
);
CREATE INDEX alerts_actor_kind_idx ON alerts (actor_id, kind, created_at DESC);
CREATE INDEX alerts_created_idx ON alerts (created_at);

-- Who an alert was addressed to (fixed at creation). Reading additionally re-checks the current
-- relation (common family / visibility), so a revoked share also hides older alerts.
CREATE TABLE alert_recipients (
    alert_id bigint NOT NULL REFERENCES alerts (id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, alert_id)
);
CREATE INDEX alert_recipients_alert_idx ON alert_recipients (alert_id);
