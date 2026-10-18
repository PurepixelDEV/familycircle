-- Short-lived, single-use codes to connect a phone to an existing account by QR code
-- (docs/QR_FORMAT.md). Only the SHA-256 hash of the code is stored.
CREATE TABLE pairing_codes (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  bytea NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    device_id  uuid REFERENCES devices (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pairing_codes_user_idx ON pairing_codes (user_id);
