package io.reductxn.settlement.port.out;

import io.reductxn.planning.api.CycleObligation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The obligations a run starts from: everything still open in a group at the cycle's instant.
 *
 * <p>A read model over {@code obligations}, which is {@code apps/api}'s table (RULE B17). It is a read model
 * <em>rather than</em> a dependency on that module, and that is the whole reason it exists: this worker and
 * {@code apps/api} may never depend on each other (RULE B6), so each reads the other's tables through its own
 * projection. The API reads these tables the same way when it reports a run.
 *
 * <p>That does mean the reconstruction of a row into a {@link CycleObligation} exists twice - here and in the
 * API's preview. Stated rather than glossed: it is what a read model is, the two are projections of one table
 * rather than two models of one concept, and the disagreement it could hide is visible in the one place it
 * matters - both are reading the same rows with the same guard against a cross-currency obligation whose quote
 * is missing.
 *
 * <p><strong>"Open" excludes what a settled run has already settled, and that is the whole point of the
 * exclusion.</strong> A run nets everything open at its instant and discharges it once its transfers confirm;
 * without this, a later cycle would net the same commitments again and the group would move money twice for one
 * promise. The identity of each row travels with it because a run has to record what it settled
 * ({@code settlement_run_obligations}), and an obligation whose id is dropped on the way in cannot be referred
 * to on the way out. That is also why {@link OpenCommitment} is a wrapper rather than two parallel lists: the id
 * and the obligation are one fact read from one row.
 *
 * <p>The semantics are deliberately identical to the preview's port of the same name, including {@code asOf}
 * meaning "open at that instant" rather than "created before it": a run and a preview of the same cycle must be
 * the same answer, or a group would approve one thing and execute another. Settling is the one asymmetry, and it
 * is the same in both readers - an obligation a done run has settled is never open again, whatever instant is
 * asked about, because settling is something that happened rather than something that is true as of a date.
 */
public interface OpenObligations {

    /**
     * One commitment, with the identity of the row it came from.
     *
     * @param obligationId the obligation's identity, which a run records when it settles it
     * @param obligation   the commitment as the planner consumes it
     */
    record OpenCommitment(UUID obligationId, CycleObligation obligation) {

        public OpenCommitment {
            Objects.requireNonNull(obligationId, "obligationId");
            Objects.requireNonNull(obligation, "obligation");
        }
    }

    /**
     * Every obligation in this group that is still open at {@code asOf}, with its identity.
     *
     * @return the commitments, in any order - the planner normalises ordering itself
     */
    List<OpenCommitment> openFor(UUID groupId, Instant asOf);
}

