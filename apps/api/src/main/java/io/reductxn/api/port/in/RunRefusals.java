package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.UUID;

/**
 * The run's expected failures, as values: the two a caller can hit before any use case has an opinion.
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

    /** There is no run with this id. */
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
            return "no settlement run has the id " + runId + ". A run is opened for a cycle before it can be read "
                    + "or approved, and the id has to be that run's";
        }
    }
}
