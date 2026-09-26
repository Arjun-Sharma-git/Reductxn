-- Who decided a transfer no rail would decide, on what basis, and when.
--
-- `V509` continues this module's `5xx` range.
--
-- **Why a person's answer needs columns of its own.** `rail_detail` holds what a rail said (`V503`, "the rail's own
-- words"), and that is the right place for a rail's answer and the wrong place for a person's. An operator who has
-- learned from the rail's support desk that the money moved is not the rail speaking, and a row that recorded the
-- assertion in the same free-text column as the rail's errors would lose the only thing that makes the assertion
-- worth anything: *who made it*. A detail and its source travel together or the detail is a rumour, which is the
-- same lesson `V507` learned about a verdict outliving its run.
--
-- **So a resolution is three facts, and they are written together or not at all.** `resolved_by` is the person,
-- `resolved_basis` is what they looked at (a rail's statement reference, a support ticket - whatever they were
-- reading when they decided), and `resolved_at` is when they decided. The CHECK below makes "all three or none" a
-- rule the database holds, because the alternative - a nullable attribution that a later writer can forget - is a
-- resolution nobody is answerable for.
--
-- **Nothing verifies the identity, and that is stated rather than papered over.** This build has no identity system
-- (membership, sessions and roles are `apps/api`'s and do not exist), so `resolved_by` is a string the operator
-- supplies: an *attribution*, not an authentication. It is still worth storing, because the question an auditor asks
-- is "who said the money moved", and a command that refused to record a name would leave that question unanswerable.
-- When identities exist, this column becomes a foreign key and the command becomes an act somebody was authorised to
-- take - and that is a decision, not an edit.
--
-- **The second CHECK says a resolution is about a resolution.** An attribution on a `pending`, `authorized` or
-- `submitted` transfer would describe a decision about money whose state says no decision has been made: either the
-- status write was lost or the attribution belongs to another row.
ALTER TABLE settlement_transfers ADD COLUMN resolved_by text;
ALTER TABLE settlement_transfers ADD COLUMN resolved_basis text;
ALTER TABLE settlement_transfers ADD COLUMN resolved_at timestamptz;

ALTER TABLE settlement_transfers ADD CONSTRAINT transfers_resolutions_are_attributed
    CHECK ((resolved_by IS NULL) = (resolved_at IS NULL)
        AND (resolved_by IS NULL) = (resolved_basis IS NULL));

ALTER TABLE settlement_transfers ADD CONSTRAINT transfers_resolutions_are_resolved
    CHECK (resolved_by IS NULL OR status IN ('confirmed', 'failed'));
