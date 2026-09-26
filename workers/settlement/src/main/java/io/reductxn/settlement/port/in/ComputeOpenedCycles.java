package io.reductxn.settlement.port.in;

/**
 * Computing the runs for the cycles somebody has opened: the other half of {@link DriveRuns}.
 *
 * <p><strong>The two cadences answer two questions.</strong> {@link DriveRuns} asks "which runs can move?" and
 * moves them. This port asks "which cycles were opened and nothing has computed?" and computes them. Splitting
 * them is not tidiness: the first is about money already in flight and is bounded by rail calls, the second is
 * about a request not yet answered and is bounded by the planner. A worker whose tick did both would tie the rate
 * of rail traffic to the rate of planning.
 *
 * <p><strong>Why it exists at all.</strong> A cycle was opened and computed in one act, so nothing was ever in the
 * state this port consumes. The moment a cycle can be opened on its own - by an operator, and later by a group's
 * schedule or a member through the API - something has to notice it, and it must not be a person: an operator who
 * has to remember to compute is an operator who can forget, and a cycle nobody computes is a group that asked for
 * a settlement that never happened.
 *
 * <p><strong>{@link ComputeRun} does the work, and this is only the decision about which cycles get it.</strong>
 * Every rule about a plan - which obligations, which rates, which status, and the refusal when there is nothing to
 * settle - is already in that use case, and it stays there: a pass with rules of its own would be a second place
 * where "may this cycle be computed?" is answered.
 */
public interface ComputeOpenedCycles {

    /**
     * Computes the runs for the cycles that have been opened and have none, oldest first.
     *
     * @param batchSize the most cycles to attempt in one pass, so a backlog drains over several passes rather than
     *                  in one long one
     * @return what was attempted, and what each attempt became or why it did not
     */
    ComputedCycles computeOpened(int batchSize);
}
