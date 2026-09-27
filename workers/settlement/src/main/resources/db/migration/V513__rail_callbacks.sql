-- What a rail told us asynchronously about a transfer, as the latest answer it has given.
--
-- `V513` continues this module's `5xx` range.
--
-- **What this table is for, in one sentence:** the driver asks a rail about a transfer it has not confirmed, and a
-- rail that has already told us the answer should not be asked again. So the answer is stored where the driver reads
-- it, and `ExecuteRunService` prefers a delivered answer to a poll (ADR 0051).
--
-- **A row per `rail_ref`, replaced in place, and that is the idempotency.** `settlement_run_gates` (V507) made the
-- same choice for the same reason: what a reader needs is the *current* answer, and two rows would need a rule about
-- which one is current. A redelivered callback - which every rail will do, because a callback is an at-least-once
-- signal - rewrites this row rather than appending a second one, so the endpoint's promised idempotency is a property
-- of the schema rather than a check somebody has to remember to write.
--
-- **`provider` is recorded and is not part of the key.** A reference is derived from the run, the counterparties and
-- the amount (RULE S2), and a transfer is offered to exactly one rail - `reductxn.rails.provider` names *the* rail for
-- a deployment, not a rail per transfer - so a reference identifies a transfer here. The provider is kept because the
-- question an auditor asks about an unverified callback is where it came from, and that fact is cheap to store and
-- impossible to reconstruct later.
--
-- **The state vocabulary has a CHECK, unlike the run's status (V501).** The difference is who writes it: a run's
-- status is written by a Java enum in this repository, and this table's state arrives from outside - a rail's own
-- word for what happened. A word this schema does not know would be a row the driver could not act on, and refusing
-- it at the row is what stops an unknown state being stored as though it meant something. The API validates the same
-- vocabulary at the boundary, because a `400` is a better answer to a rail than a failed insert - this is the second
-- line, not the first.
--
-- **`detail` is the rail's own words** - the same free text `rail_detail` holds on the transfer (V503) - and it is
-- copied onto the transfer by the driver when the answer is applied, so a person reading the run sees what the rail
-- said without joining to this table.
CREATE TABLE settlement_rail_callbacks (
    rail_ref    text        PRIMARY KEY,
    provider    text        NOT NULL,
    state       text        NOT NULL,
    detail      text,
    received_at timestamptz NOT NULL,

    CONSTRAINT rail_callbacks_have_a_known_state
        CHECK (state IN ('accepted', 'pending', 'confirmed', 'failed'))
);

-- The driver's read is by `rail_ref`, which is the primary key, so no second index is needed. What is worth an index
-- is the other question an operator asks - "what has this rail been telling us lately?" - and it is not asked often
-- enough to pay for one here; a callback table is small next to the transfers it is about, because a reference is
-- one row whatever the rail says about it.
