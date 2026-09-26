package io.reductxn.api.port.out;

import io.reductxn.api.port.in.OpenedCycle;

/**
 * The cycle rows, written from {@code apps/api}.
 *
 * <p><strong>Another module's table, and the same arrangement as {@link Decisions}.</strong> {@code
 * settlement_cycles} belongs to {@code workers/settlement} (RULE B17), and this deployable writes it because the
 * member's surface is here and the worker is there, with no dependency between them (RULE B6) and no broker in this
 * build. ADR 0020 made that crossing for an answer; ADR 0033 makes it for a cycle, and the reasons are the same
 * ones: the row is the interface, and the module that owns the meaning is the one that reads it - which is what the
 * worker's cadence does, computing a plan for every cycle that has no run.
 *
 * <p><strong>One method, and it is idempotent on the pair for the reason the worker's is.</strong> An insert that
 * conflicts on {@code (group_id, as_of)} writes nothing, and the cycle that is already there is what the caller
 * gets back - so a retry after a timeout, two clicks, and a policy firing twice all produce one cycle. That is also
 * why this adapter mints no id: the id identifies the row, and the pair identifies the *cycle*, which is the pair
 * the migration's unique constraint is on.
 */
public interface Cycles {

    /**
     * Writes the cycle if the group has none for that instant, and answers with the row either way.
     *
     * <p>No transaction and no refusal. The row is keyed by {@code (group_id, as_of)}, so a write arriving
     * concurrently can only be the same cycle, and the read that follows the insert cannot disagree with it. A
     * constraint this method has never heard of is a {@code 500}, because it would mean something is wrong that this
     * class has no honest answer for.
     *
     * @return the cycle as stored, with {@link OpenedCycle#created()} saying whether this call inserted it
     */
    OpenedCycle open(CycleToOpen cycle);
}
