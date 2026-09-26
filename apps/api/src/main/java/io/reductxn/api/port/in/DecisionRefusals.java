package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.UUID;

/**
 * An answer's expected failures, as values.
 *
 * <p>Two of them, and their small number is the slice's most interesting property: every rule about *what an answer
 * means* lives in the module that owns the run, so this deployable cannot refuse an answer for being early, for
 * coming from a member the run does not concern, or for arriving after the run has moved on. Those are judged where
 * they are computable, and this module's refusals are limited to what it can see from where it stands.
 *
 * <p>The two failures that are about the *request* rather than about the answer - a malformed body and an unknown
 * run - are {@link RunRefusals}, because reading a run meets them too and a code identifies a condition rather than
 * an endpoint (RULE B20).
 *
 * <p>One record per code with a {@code public static final String CODE}, because
 * {@code DomainErrorCodeTest} requires it of every concrete {@code DomainError} so the error-code parity gate can
 * enumerate what the server can emit. The namespace is {@code api} for the mechanical reason
 * {@code ObligationRefusals} documents at length: these classes live in {@code io.reductxn.api..}, and the guard
 * derives the namespace from the package rather than from the domain.
 */
public final class DecisionRefusals {

    private DecisionRefusals() {
    }

    /** This member has already answered this run, and an answer is final. */
    public record AlreadyAnswered(UUID runId, UUID memberId) implements DomainError {

        public static final String CODE = "api.decision_already_answered";

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

        public static final String CODE = "api.decision_not_acceptable";

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
