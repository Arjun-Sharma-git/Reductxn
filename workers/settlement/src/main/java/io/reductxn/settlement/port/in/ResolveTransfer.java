package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

/**
 * Recording what a person found out about a transfer no rail would decide (RULE S6).
 *
 * <p><strong>This is the way back from the escalation report.</strong> Section 4.4's "or escalate" has two halves:
 * the worker says which movements nobody has decided (ADR 0024), and then somebody asks the rail. This is the second
 * half - an operator who has an answer from the rail's support desk, a statement or a bank line, and needs to get it
 * into the rows where the run's fate, the escalation report and a client's read model all read it.
 *
 * <p><strong>It never calls the rail, and that is the point rather than an implementation detail.</strong> A
 * resolution records something somebody already knows; asking the rail would make the command's outcome depend on a
 * rail that has been silent, which is the state it exists for. The two consequences of writing are the same as a
 * confirmation's and happen in one transaction: the transfer's state with its attribution, and - for
 * {@code confirmed} only - the settlement entry the movement books. Nothing else moves: no other transfer is
 * offered to the rail, no funding is recorded, and the run's gates are not re-read, because a resolution is an
 * answer about one movement rather than a new attempt at the run.
 *
 * <p><strong>The run's fate follows, and it is the same rule that execution uses.</strong> A resolved transfer can
 * complete a run ({@code done} when every transfer is confirmed) or end one ({@code failed} when an answer says the
 * money did not move) - and rather than deciding that here, this port's implementation asks {@code RunOutcome},
 * which is the one place the question "what is a run whose transfers are in these states" is answered. Two writers
 * of one fact is the shape this module keeps refusing (ADR 0021), and a resolution that disagreed with execution
 * about a run's status would be exactly that.
 *
 * <p><strong>Three things are refused rather than written</strong>, and each is a case where the assertion cannot be
 * true of the row: a transfer already resolved by anybody else (which would be a rewrite of history, and the refusal
 * names who decided and when, so a retry after a timeout learns it was itself), a transfer no rail has ever been
 * asked about ({@code pending}: there is no answer to have found out), and a transfer that is not in the run the
 * caller named.
 */
public interface ResolveTransfer {

    /**
     * Writes what the person found out, books the movement when there was one, and answers with the run as it now
     * stands.
     *
     * @param resolution the transfer, the outcome a person asserts, and who asserted it on what basis
     * @return the run read back from its rows, or a refusal naming why the assertion could not be recorded
     */
    Result<ComputedRun> resolve(TransferResolution resolution);
}
