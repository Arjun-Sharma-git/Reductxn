package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.NewDecision;
import io.reductxn.api.port.in.RecordedDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * Between the contract's shapes and the ports' types, for the decision operation.
 *
 * <p>The only class that knows both, as with the other two mappers in this package: the use case speaks
 * {@link NewDecision} and {@link RecordedDecision}, the wire speaks {@link DecisionRequest} and
 * {@link DecisionResponse}.
 *
 * <p>This is also where a request that does not say which answer it means becomes one, which is a property of the
 * wire format rather than of the use case: absent means approve, because approval is the common answer and because
 * the request that existed before refusals did has to keep meaning what it meant. An absent *body* is the same
 * request as an absent field since ADR 0053 - the member's id was the only thing that made a body necessary, and
 * the session supplies the author now.
 */
public final class DecisionMapper {

    /** The answer a request means when it does not say. */
    private static final String APPROVE = "approve";

    private DecisionMapper() {
    }

    /**
     * The request as the use case's command.
     *
     * @param now the boundary's instant (RULE B21). The contract lets a client send one for an obligation and not
     *            for an answer, and rightly: "when did you answer" is the moment the server recorded it, and a
     *            client-supplied instant would be a claim about a member's intent that nobody could check
     */
    public static NewDecision toCommand(UUID runId, DecisionRequest request, Instant now) {
        String answer = request == null || request.decision() == null ? APPROVE : request.decision();

        return new NewDecision(runId, answer, now);
    }

    public static DecisionResponse toResponse(RecordedDecision recorded) {
        return new DecisionResponse(recorded.runId(), recorded.memberId(), recorded.decision(),
                recorded.recordedAt());
    }
}
