-- Invitations: a code that lets somebody join a group.
--
-- Owned by apps/api (MASTER-PLAN section 5.5), continuing its `V10x` range: Flyway keeps one version sequence per
-- schema whichever locations it reads (RULE B17, and the note in V101__obligations.sql).
--
-- **The code is the primary key, and that is a decision the contract made.** `POST /invites/{code}/accept` carries no
-- group, so the code has to identify the group on its own - which means a code is unique across the deployment rather
-- than within a group, and the lookup an acceptance performs is this table's key rather than a scan of one group's
-- rows. An added `id` column would be a second identifier for one thing (the shape ADR 0040 removed from `Member`),
-- and nothing would address it: the contract's `Invite` schema has no id, and after creation no operation names an
-- invite again.
--
-- **What is deliberately not here.** No `created_by`: this build has no identity, so the column could only be filled
-- with a value the request invented (the same absence ADR 0035 recorded for `group_members`). No `role` on the rule
-- that accepting confers either - accepting joins as `member`, which the operation's description states - because a
-- one-value column would be a vocabulary nobody chose and an invitation that confers `admin` is a decision somebody
-- has to make on purpose rather than inherit from a default.
--
-- **`max_uses` null means unbounded.** The contract's `Invite` schema requires only `code` and `expires_at` and makes
-- `maxUses` optional, and the optional field's absent value is the ordinary case for a link a group shares: it stops
-- when it expires. A constraint cannot say that, so the read that decides exhaustion treats null as "no limit" and the
-- upsert that spends a use does the same - one expression, written once, in `JooqInvitations`.
--
-- **No index on `group_id`.** Nothing reads a group's invitations back: the contract has no such operation, and the
-- activity read (still recorded as a gap) is the one that would want it. An index for a read that does not exist is a
-- write cost nobody pays for, and this file is where that would be noticed - the members migration created its index
-- because the members read existed.

CREATE TABLE invitations (
    -- Eight characters from a 32-symbol alphabet, which is 40 bits: unguessable enough that a code cannot be
    -- enumerated, short enough to read out loud or type. The CHECK is where that stops being advice - the alphabet
    -- excludes `0`, `1`, `I` and `O`, because a code is transcribed by a person and those four are the ones people get
    -- wrong.
    code        text        PRIMARY KEY,
    CONSTRAINT invitations_code_is_typeable CHECK (code ~ '^[A-HJ-NP-Z2-9]{8}$'),

    -- No foreign key to a `groups` table: there is none, deliberately, and `group_members` writes its own group_id as a
    -- plain uuid for the same reason (V104 says why in full).
    group_id    uuid        NOT NULL,

    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,

    -- How many times the code may be spent. Null is unbounded, and the two constraints below refuse the values that
    -- would make the rule ambiguous: a `max_uses` of zero could never be spent, and a negative one is not a count.
    max_uses    integer,
    uses        integer     NOT NULL DEFAULT 0,

    CONSTRAINT invitations_window_is_ordered  CHECK (expires_at > created_at),
    CONSTRAINT invitations_max_uses_is_a_count CHECK (max_uses IS NULL OR max_uses > 0),
    CONSTRAINT invitations_uses_start_at_zero CHECK (uses >= 0),

    -- A code may only be spent as many times as it allows. The acceptance path checks this too and answers `410`, but
    -- the constraint is what makes the check a fact rather than a hope: two acceptances racing on the last use both
    -- pass a check and only one can pass this.
    CONSTRAINT invitations_uses_stay_within_max CHECK (max_uses IS NULL OR uses <= max_uses)
);
