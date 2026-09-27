package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.Objects;
import java.util.UUID;

/**
 * The run's expected failures, as values: the two a caller can hit before any use case has an opinion, and - since
 * ADR 0050 - the one a use case reaches by projecting the worker's own verdicts.
 *
 * <p><strong>Why these two live here rather than in {@link DecisionRefusals}.</strong> They were written for the
 * approval endpoint, and the second caller showed that neither is about approving: a run that does not exist is a
 * run that cannot be read either, and a request missing a field is malformed whatever it was asking for. A code is
 * contract surface and its identity is the *condition* (RULE B20), so two codes for "no such run" - one per endpoint
 * - would be two identities for one thing and a client branching on both. The other three refusals stayed where they
 * were, because they really are about an answer: that this member already gave one, and that the run's rows will not
 * hold one.
 *
 * <p>One record per code with a {@code public static final String CODE}, because {@code DomainErrorCodeTest}
 * requires it of every concrete {@code DomainError} so the parity gate can enumerate what the server can emit.
 */
public final class RunRefusals {

    private RunRefusals() {
    }

    /** The request is missing something the use case cannot be handed at all. */
    public record RequestIncomplete(String field) implements DomainError {

        public static final String CODE = "api.request_incomplete";

        public RequestIncomplete {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "this request has no " + field + ", so there is nothing to act on";
        }
    }

    /**
     * There is no run with this id **that this caller may read**.
     *
     * <p><strong>One code for two conditions, since ADR 0054.</strong> A run that does not exist and a run in a group the
     * caller is not in answer identically, because telling them apart would make every run-scoped route an oracle for
     * which run ids are real - and a run id names a plan, its amounts and its answers. The same sameness the group reads
     * carry as {@link GroupRefusals.NoSuchGroup}, from the other direction: there the code is the group's, and here it is
     * the run's, because that is the id the caller sent.
     *
     * <p>The javadoc used to say only "there is no run with this id" and the message named only that condition, which was
     * true when this code could only mean one thing. The contract's paragraph for both run reads now states the wider
     * meaning, so the message does too: a refusal whose prose under-describes its own condition is the kind of gap this
     * repository keeps finding in the other direction.
     */
    public record NoSuchRun(UUID runId) implements DomainError {

        public static final String CODE = "api.no_such_run";

        public NoSuchRun {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "there is no settlement run " + runId + " that this caller may read. Either no run has that id, or "
                    + "the run belongs to a group the caller is not a member of - a run is read by the members of the "
                    + "group it settles, and the id has to be that run's";
        }
    }

    /**
     * The run cannot move for now, so a request to execute it would do nothing.
     *
     * <p><strong>The contract's {@code 409} for {@code POST /runs/{runId}/execute} is one status for two situations,
     * and this is one code for them for the same reason.</strong> "Quorum not reached, or already executing" are the
     * same diagnosis from a member's side: the thing they are trying to influence has not arrived or has moved on, and
     * asking again changes neither. The status travels with it so the answer says which state the run is in - the same
     * shape {@code settlement.not_waiting_on_the_group} has, and for the same reason.
     *
     * <p><strong>409 rather than 422, and the difference is what a client should do.</strong> The request is
     * well-formed and it could have succeeded earlier - before the run started executing, or once the group had
     * approved - so a client learns to try again when the run says it is ready rather than that it asked wrongly. That
     * is the reading {@code DecisionRefusals.AlreadyAnswered} carries.
     *
     * <p>Reached by asking for a run that is already {@code executing}, finished ({@code done}, {@code failed},
     * {@code rejected}), still being opened ({@code pending}, {@code computing}), or waiting on a group that has not
     * approved enough of it. A run waiting on currency is <em>not</em> refused: the driver honours the request as soon
     * as the currency arrives, so recording the intent is worth more than refusing it, and the contract's {@code 409}
     * names quorum and "already executing" rather than widening to funding.
     */
    public record NotReadyToExecute(UUID runId, String status) implements DomainError {

        public static final String CODE = "api.run_not_ready";

        public NotReadyToExecute {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " is " + status + ", so a request to execute it now would change nothing: only a "
                    + "run whose group has approved it, and which has not already moved, can be offered to a rail";
        }
    }
}
