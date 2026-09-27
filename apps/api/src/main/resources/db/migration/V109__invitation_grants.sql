-- A share link: the same code, granting a look rather than a way in (ADR 0055).
--
-- Owned by apps/api, continuing its `V10x` range (RULE B17). What it changes is one thing V105 guessed and ADR 0054 made
-- concrete: an invitation was a code that *joins* somebody, and a group also needs a code that lets somebody *look* - an
-- accountant, a landlord, a mediator - without becoming a member of it. Those are two audiences and one instrument.
--
-- **A column rather than a second table, because the instrument is the same one.** A share link has the same shape a
-- join code has, and every rule about it is already written against this table: the code is the primary key and
-- therefore globally unique (which is what lets a link carry its own group), the window is a half-open pair of instants,
-- a limit is optional and null means unbounded, and a use is spent by a conditional update so two racing requests cannot
-- both take the last one. A `share_links` table would restate all five and then need its own version of whichever of
-- them changed - which is the second description of "a code" that ADR 0021 exists to refuse. The one thing that differs
-- is what holding the code entitles somebody to, and that is one word.
--
-- **`grants` says what the code gives, not what it is.** `is_share` was the obvious alternative and it is the wrong
-- shape twice over: it makes "share" the marked case of an unmarked default (a boolean whose `false` means "the ordinary
-- one"), and it cannot hold the third thing a code might one day confer - a code that lets somebody answer a single run,
-- say, which is the same instrument again. A word with a CHECK is what the table already does for a member's `role`
-- (V104), and this one differs in the way that matters: **something reads it** - both creation paths write it, the
-- acceptance refuses a code that does not grant membership, and the shared read refuses one that does not grant reading.
--
-- **`membership` rather than `join`, and the word is the members read's.** What a group's people are is `members`, what
-- every one of them is called in the contract is a member, and what an acceptance records is a row in `group_members`.
-- `join` would name the act; `membership` names what the holder ends up with, which is what a refusal like "this code
-- does not join anybody" is actually about.
--
-- **The default is for the backfill, not for the code.** Every row that exists was made to join somebody, so
-- `DEFAULT 'membership'` writes the truth over the table without an `UPDATE` - and both creation paths name the word
-- explicitly afterwards, because a default that quietly decides a new column's value is how a `read` code would come to
-- grant membership the day somebody forgot the argument. The column keeps its default only so that adding a NOT NULL
-- column to a populated table is one statement; a `grants` of `NULL` would be a code that grants nothing, which no
-- caller can ask for and no operation would know what to do with.

-- Two statements would also work (`ADD COLUMN` and `ADD CONSTRAINT` cannot share one `ALTER TABLE`), and a column
-- constraint is used instead because it keeps the word and its allowed values in one place: the CHECK belongs to the
-- column it constrains.
ALTER TABLE invitations
    ADD COLUMN grants text NOT NULL DEFAULT 'membership'
        -- The two purposes this build has, named in one place so a third has to be added deliberately rather than
        -- spelled. Nothing in the schema filters on this column, so there is no index beside it.
        CONSTRAINT invitations_grants_is_a_purpose CHECK (grants IN ('membership', 'read'));

COMMENT ON COLUMN invitations.grants IS
    'What holding the code entitles somebody to: `membership` joins them to the group (an acceptance spends a use), `read` lets them see the group''s positions and nothing else (the shared read spends nothing). ADR 0055.';
