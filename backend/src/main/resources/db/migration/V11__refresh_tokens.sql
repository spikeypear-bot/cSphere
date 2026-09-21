-- D17 stage 6: refresh tokens, so a 15-minute access token does not mean a
-- 15-minute session.
--
-- Deliberately NOT a JWT. The entire purpose of this table is that a session can
-- be revoked, and a signed token cannot be — a row can be deleted, a claim set
-- already in someone's hands cannot be recalled. Access tokens and refresh
-- tokens solve opposite problems, so they are built out of opposite materials.
--
-- Only the SHA-256 hash of the token is stored. A refresh token is a bearer
-- credential: an unhashed column would make a database leak equivalent to
-- handing over every live session. SHA-256 rather than BCrypt is right here —
-- the value is 256 bits of CSPRNG output, so there is no low-entropy guess to
-- slow an attacker down, and this runs on every access-token expiry.
--
-- `family_id` links every token descended from one login. Rotation issues a new
-- token and revokes the old one on each refresh; presenting an already-rotated
-- token therefore means it was replayed or stolen, and the only safe response is
-- to revoke the whole family and force a fresh login. See RefreshTokenService.

CREATE TABLE refresh_tokens (
    token_id   UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id  UUID NOT NULL,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_refresh_token_expiry_after_issue CHECK (expires_at > issued_at)
);

-- Rotation and reuse detection both read by hash; family revocation reads by
-- family. Neither is a scan we want to grow with the table.
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

COMMENT ON TABLE refresh_tokens IS
    'Opaque, rotating, revocable session credentials. One live row per session; rotated rows are kept revoked so reuse can be detected.';
COMMENT ON COLUMN refresh_tokens.token_hash IS
    'SHA-256 hex of the opaque token. The token itself is never stored and cannot be recovered from this column.';
COMMENT ON COLUMN refresh_tokens.family_id IS
    'Groups every token descended from a single login. Reuse of a rotated token revokes the entire family.';
COMMENT ON COLUMN refresh_tokens.revoked_at IS
    'Set on rotation, on logout, or on family revocation. NULL means the token is live.';
