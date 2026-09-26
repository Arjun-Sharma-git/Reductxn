package io.reductxn.api.port.out;

import io.reductxn.api.port.in.NewDecision;

import java.util.UUID;

/**
 * Writing an answer into the run's decision rows, and the two things the store can say about it.
 *
 * <p><strong>This is the one place in the module that knows another module's table.</strong> {@code
 * settlement_decisions} is {@code workers/settlement}'s - its migrations are the {@code 5xx} range and this module
 * owns none of them - and this deployable writes a row into it because the member's surface is here and the run
 * there, with no dependency between the two and no broker in the build (RULE B6). The precedent is the ledger:
 * {@code journal_entries} belongs to {@code libs/ledger}, and both deployables append to it, because the module
 * that knows the fact writes it.
 *
 * <p><strong>What makes that safe is not trust, it is the design of quorum.</strong> ADR 0016 made a run's quorum
 * a pure function over the run's own rows: {@code Quorum.counted()} intersects the answers with the run's
 * participants, so an answer from somebody the run does not concern counts for nothing, whichever deployable wrote
 * it. The worst a mistaken write here can do is record one member's approval, and the run still cannot execute
 * without quorum, its funding covered and a rail that settles its currencies.
 *
 * <p>The shape this writes is pinned from both sides: {@code DecisionApiTest} here, and
 * {@code RecordedDecisionsTest} in {@code workers/settlement}, which writes the same row with the same SQL and
 * checks that the run's arithmetic judges it. A change to that table which broke the crossing would fail one of
 * them rather than a deployment.
 */
public interface Decisions {

    /** Whether there is a run with this id at all, which is the one thing a client's request cannot tell us. */
    boolean hasRun(UUID runId);

    /**
     * Writes the answer, and reports what happened.
     *
     * <p>Returns a value rather than throwing on the two refusals the database itself can produce, because they are
     * expected outcomes of a legal request: the primary key on {@code (run_id, member_id)} refuses a second answer,
     * and the run's own check constraints refuse one that cannot belong to it. Both are the store's decisions, and
     * a client deserves a code rather than a {@code 500} - which is also the difference between reading-then-writing
     * (a race nobody sees) and letting the row's own constraints be the guard.
     */
    Written record(NewDecision approval);

    /** What the store did with an answer. */
    enum Written {

        /** The row is written and the run can see it. */
        RECORDED,

        /** This member had already answered this run. Nothing was written. */
        ALREADY_ANSWERED,

        /**
         * The run's rows refused it - the schema's constraints are the authority on what an answer can be. Nothing
         * was written.
         */
        REFUSED_BY_THE_RUN
    }
}
