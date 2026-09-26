package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.NewApproval;
import io.reductxn.api.port.in.RecordedApproval;

import java.time.Instant;
import java.util.UUID;

/**
 * Between the contract's shapes and the ports' types, for the approval operation.
 *
 * <p>The only class that knows both, as with the other two mappers in this package: the use case speaks
 * {@link NewApproval} and {@link RecordedApproval}, the wire speaks {@link ApproveRunRequest} and
 * {@link ApprovalResponse}.
 */
public final class ApprovalMapper {

    private ApprovalMapper() {
    }

    /**
     * The request as the use case's command.
     *
     * @param now the boundary's instant (RULE B21). The contract lets a client send one for an obligation and not
     *            for an answer, and rightly: "when did you answer" is the moment the server recorded it, and a
     *            client-supplied instant would be a claim about a member's intent that nobody could check
     */
    public static NewApproval toCommand(UUID runId, ApproveRunRequest request, Instant now) {
        return new NewApproval(runId, request.memberId(), now);
    }

    public static ApprovalResponse toResponse(RecordedApproval recorded) {
        return new ApprovalResponse(recorded.runId(), recorded.memberId(), recorded.recordedAt());
    }
}
