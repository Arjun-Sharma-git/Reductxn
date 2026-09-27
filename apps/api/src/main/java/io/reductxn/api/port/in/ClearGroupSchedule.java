package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: stop a group's cadence, so nothing closes its books by itself (ADR 0057).
 *
 * <p><strong>Clearing is a write of its own rather than a schedule with a null instant.</strong> A row that says "there
 * is a schedule and it has no next due instant" is a state the worker would have to interpret and the contract would have
 * to describe twice - once as an absence and once as a value - and the operations that ask about a cadence would each
 * need the same rule. Removing the row makes "no cadence" a fact the primary key already expresses.
 *
 * <p>Nothing else changes when it clears: a cycle the worker has already opened stays open, and the run computed for it
 * is unaffected. A schedule decides *when* a closure happens and never what it contains.
 */
public interface ClearGroupSchedule {

    /**
     * Removes the group's schedule.
     *
     * @return success, or {@code no_such_group} / {@code no_such_schedule} - the last because answering the same success
     *     to "removed it" and "there was nothing" tells a client its request did something it did not
     */
    Result<Void> clear(UUID callerId, UUID groupId);
}