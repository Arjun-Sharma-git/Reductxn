-- Two columns `obligations` needs before anything can be recorded over HTTP.
--
-- A new migration rather than an edit to V101, because V101 is applied history: it has run in every
-- environment that has a schema, and Flyway would refuse the changed checksum rather than apply it.
--
-- **`memo`.** The contract's request body has `memo` in its required list and its `Obligation` response
-- carries it, so a server that could not store one would have to answer a request it had accepted with a
-- response missing a field the client sent. Free text authored by a member: never translated, never parsed
-- for meaning (ADR 0014).
--
-- Nullable deliberately, and this is the one place the contract does not get to decide. Rows written
-- before this column existed have no memo, and backfilling them with an empty string would claim every
-- historical obligation was recorded with a blank one. The contract's requirement is enforced on the way
-- in, where it means something.
--
-- **`idempotency_key`.** The contract makes the header required, and the reason it exists is that a retried
-- request must not record a second obligation - which is also what stops a second *entry* being booked,
-- since entry ids are derived from the obligation's identity (libs/booking). Storing the key is what lets a
-- replay be recognised and answered with what was recorded the first time, rather than refused.
--
-- The unique index is the part that cannot be talked out of: two rows in one group cannot share a key, so a
-- concurrent retry collides instead of appending. What that costs is stated in the use case - the loser of
-- the race gets an error rather than the winner's response, and turning that into a replay needs a
-- transaction of its own.

ALTER TABLE obligations ADD COLUMN memo text;
ALTER TABLE obligations ADD COLUMN idempotency_key text;

-- The contract's maxLength, as a constraint. A client that sends 500 characters has made a mistake the
-- server should refuse rather than truncate: silently shortening something a person wrote is worse than
-- telling them it is too long.
ALTER TABLE obligations ADD CONSTRAINT obligations_memo_fits_the_contract
    CHECK (memo IS NULL OR length(memo) <= 280);

ALTER TABLE obligations ADD CONSTRAINT obligations_key_is_meaningful
    CHECK (idempotency_key IS NULL OR btrim(idempotency_key) <> '');

-- Partial, because a null key means "no key was given" and Postgres treats nulls as distinct in a unique
-- index anyway - so the predicate documents the intent rather than changing the behaviour.
CREATE UNIQUE INDEX obligations_one_per_idempotency_key
    ON obligations (group_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
