-- Identity: who a person is, the code that proves it, and the session that remembers it.
--
-- Owned by apps/api (MASTER-PLAN section 5.5 names `users`, `devices`, `sessions`, `kyc_*` as this module's), continuing
-- its `V10x` range (RULE B17).
--
-- **Why this table set did not exist until now, and what it breaks open.** Every boundary in this build takes a uuid on
-- trust: a decision's `memberId`, an obligation's `debtorId`, an invitation's `userId` are all "whoever the body says",
-- and ADR 0020 recorded that as the shape of the gap rather than papering over it. `GET /me` and the three auth
-- operations have been in the contract since the first slice with nothing behind them, and the client's member-facing
-- screens fell back to showing a uuid where a name belongs. This is the table set that lets a request say who is asking.
--
-- **A phone number is the identity, and the first verification is a registration.** There is no separate sign-up, which
-- is what an OTP build means: the code proves the number, and a number nobody has seen before becomes a user. The
-- alternative - a `users` table seeded by an admin operation - would need an admin, and there is no admin until there are
-- users.
--
-- **`devices` is a table MASTER-PLAN names and this file does not create.** What a device would hold that a session does
-- not is a push token and a name, and nothing reads either: the verify operation's `deviceId` says which device the
-- session belongs to, so it is a column below. A table with one column and no reader is the shape the members migration
-- refused for `groups` (V104), and it arrives with the thing that reads it.
--
-- **`kyc_*` is not here either**, and the contract's `User.kycTier` is served as `none`: there is no KYC provider, and a
-- tier nobody checked is not a tier. The field exists because the schema requires it, and the operation's description
-- says what that value means in this deployment.

CREATE TABLE users (
    id           uuid        PRIMARY KEY,

    -- The identity, unique: two rows differing only in formatting would be one person with two accounts, so the number
    -- is stored as the caller wrote it and compared exactly.
    phone_number text        NOT NULL,

    -- What the product calls them before any group does. Not the group's name for them (`group_members.display_name` is
    -- that), because the two can differ and neither is a correction of the other.
    display_name text        NOT NULL,

    created_at   timestamptz NOT NULL,

    CONSTRAINT users_phone_numbers_are_unique UNIQUE (phone_number),
    CONSTRAINT users_phone_numbers_are_not_empty CHECK (length(btrim(phone_number)) > 0),
    CONSTRAINT users_display_names_are_not_empty CHECK (length(btrim(display_name)) > 0)
);

-- The one-time codes that prove a phone number, and the rate limit that keeps them from being a way to send messages.
--
-- **The code is stored hashed, and this is the table where that matters most.** A six-digit code is 20 bits: a leak of
-- plain codes is a leak of every live code, guessable by hand. The hash is PBKDF2 with a per-challenge salt (see
-- `OneTimeCodeHash`), and the salt lives in this row because a challenge is verified once and the row is short-lived -
-- there is nothing to gain by reusing one.
--
-- **A challenge is consumed by being marked spent rather than deleted.** Two codes in flight for one number is the
-- ordinary case - somebody asked twice - so the lookup is the newest unspent row, and a successful verification marks it
-- spent instead of deleting it, so the rate limit can still count what was asked for.
CREATE TABLE otp_challenges (
    id           uuid        PRIMARY KEY,
    phone_number text        NOT NULL,

    code_hash    bytea       NOT NULL,
    salt         bytea       NOT NULL,

    -- How many times this code may be tried before it is dead. A code that can be guessed with unlimited attempts is
    -- not a second factor, it is a delay.
    attempts     integer     NOT NULL DEFAULT 0,

    requested_at timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    spent_at     timestamptz,

    CONSTRAINT otp_windows_are_ordered CHECK (expires_at > requested_at),
    CONSTRAINT otp_attempts_start_at_zero CHECK (attempts >= 0)
);

-- The rate limit's index: "how many codes has this number asked for since this instant". The rows are the record, which
-- is why a spent challenge stays - counting what was asked for is the whole point.
CREATE INDEX otp_challenges_by_number ON otp_challenges (phone_number, requested_at DESC);

-- Sessions: one row per sign-in, holding both tokens hashed.
--
-- **Opaque tokens hashed at rest rather than JWTs.** The contract's `bearerAuth` used to say `bearerFormat: JWT` and it
-- no longer does (ADR 0042): a JWT needs a signing key and a story for rotating it, and it would buy statelessness this
-- deployment has no use for - `POST /auth/refresh` needs a row to rotate either way, so a stateless access token would
-- save one indexed lookup and cost a key management problem. A 256-bit random token has no structure to trust, which is
-- why it is hashed with SHA-256 rather than PBKDF2: there is no dictionary to attack a value drawn from 2^256.
--
-- **Rotation replaces both tokens in the row.** A refresh that issued a new access token and kept the same refresh token
-- would leave the old refresh token valid for the rest of its life, which is the opposite of what rotating one is for.
-- The row therefore holds one pair and a replay of the previous refresh token simply does not match - a 401, with no
-- reuse-detection state to keep (recorded as a gap: detecting *reuse* rather than refusing it needs a history of rotated
-- tokens).
--
-- **The refresh window is never rewritten by a rotation.** `refresh_expires_at` is set once, here, and a rotating update
-- leaves it alone - which is the only way a rotation cannot extend a session. A session is thirty days long, and using it
-- does not make it longer.
CREATE TABLE sessions (
    -- The access token's SHA-256, hex: the key, because every authenticated request looks a session up by it and nothing
    -- addresses a session by an id.
    access_token_hash  text        PRIMARY KEY,
    refresh_token_hash text        NOT NULL,

    user_id            uuid        NOT NULL,

    -- Which device asked. Recorded rather than enforced, and not a foreign key: there is no `devices` table, for the
    -- reason at the top of this file.
    device_id          text        NOT NULL,

    created_at         timestamptz NOT NULL,
    access_expires_at  timestamptz NOT NULL,
    refresh_expires_at timestamptz NOT NULL,

    CONSTRAINT sessions_access_end_before_refresh CHECK (access_expires_at < refresh_expires_at),
    CONSTRAINT sessions_devices_are_not_empty CHECK (length(btrim(device_id)) > 0)
);

-- Rotation looks a session up by its refresh token, and the authenticated reads look one up by its access token - which
-- is the primary key. This index is the refresh half: without it every rotation scans the table.
CREATE UNIQUE INDEX sessions_by_refresh_token ON sessions (refresh_token_hash);
