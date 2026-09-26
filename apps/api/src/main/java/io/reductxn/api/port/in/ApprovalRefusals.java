package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.UUID;

/**
 * Recording an answer's expected failures, as values.
 *
 * <p>Three of them, and their small number is the slice's most interesting property: every rule about *what an
 * answer means* lives in the module that owns the run, so this deployable cannot refuse an answer for being early,
 * for coming from a member the run does not concern, or for arriving after the run has moved on. Those are judged
 * where they are computable, and this module's refusals are limited to what it can see from where it stands.
 *
 * <p>One record per code with a {@code public static final String CODE}, because
 * {@code DomainErrorCodeTest} requires it of every concrete {@code DomainError} so the error-code parity gate can
 * enumerate what the server can emit. The namespace is {@code api} for the mechanical reason
 * {@code ObligationRefusals} documents at length: these classes live in {@code io.reductxn.api..}, and the guard
 * derives the namespace from the package rather than from the domain.
 */
public final class ApprovalRefusals {

    private ApprovalRefusals() {
    }

    /** The request is missing something the use case cannot be handed at all. */
    public record RequestIncomplete(String field) implements DomainError {

        public static final String CODE = "api.approval_incomplete";

        public RequestIncomplete {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "this request has no " + field + ", so there is no answer to record";
        }
    }

    /** There is no run with this id, so there is nothing to answer. */
    public record NoSuchRun(UUID runId) implements DomainError {

        public static final String CODE = "api.approval_no_such_run";

        public NoSuchRun {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "no settlement run has the id " + runId + ", so this answer is about nothing. A run is opened "
                    + "for a cycle before it can be approved, and the id has to be that run's";
        }
    }

    /** This member has already answered this run, and an answer is final. */
    public record AlreadyAnswered(UUID runId, UUID memberId) implements DomainError {

        public static final String CODE = "api.approval_already_answered";

        public AlreadyAnswered {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "member " + memberId + " has already answered run " + runId + ", and a decision is final - the "
                    + "alternative is a product that has to answer what changed when somebody changed their mind";
        }
    }

    /**
     * The run's own rows will not accept this answer.
     *
     * <p>The one refusal here that is not this module's judgement: the schema refuses an answer from the group's
     * clearing account (the run's decisions come from members, and {@code fx_clearing} is a participant rather than
     * a member), and this reports that rather than answering a client's mistake with a {@code 500}. It is
     * deliberately vague about *why* the database said no, because this deployable does not decide what makes an
     * answer acceptable - it reports that the row was refused.
     */
    public record NotAcceptable(UUID runId, UUID memberId, String reason) implements DomainError {

        public static final String CODE = "api.approval_not_acceptable";

        public NotAcceptable {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " cannot hold this answer from member " + memberId + ": " + reason
                    + ". The run's rows are the authority on what an answer can be, and this one was refused "
                    + "rather than recorded";
        }
    }
}
