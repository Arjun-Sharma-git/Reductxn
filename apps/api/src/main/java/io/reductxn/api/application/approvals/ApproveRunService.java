package io.reductxn.api.application.approvals;

import io.reductxn.api.port.in.ApprovalRefusals;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.ApproveRun;
import io.reductxn.api.port.in.NewApproval;
import io.reductxn.api.port.in.RecordedApproval;
import io.reductxn.api.port.out.Approvals;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a member answered a run, so record it.
 *
 * <p><strong>It has no rules, and here that is a claim rather than a habit.</strong> The interesting rules about an
 * answer - that a decision is final, that it must come from one of the run's participants, that a rejection ends
 * the run - are the run's, they are computed over the run's stored rows, and they live in the module that owns the
 * run. What is left for this class is the sequence a single write needs: does the run exist, does the store accept
 * the answer, and what do we say back.
 *
 * <p><strong>Three refusals, and the third is the interesting one.</strong> A run that does not exist has nothing
 * to answer; a member who has already answered is refused rather than overwritten, because a decision is final
 * ({@code docs/specs/settlement.md} section 4.1) and the primary key is what makes that true under concurrency
 * rather than only in a single request; and an answer the run's rows will not hold - the group's clearing account
 * cannot answer, for instance - is reported as the store's refusal rather than as a {@code 500}.
 *
 * <p>What this class deliberately does not do is check the run's status. Recording an answer for a run that is
 * still being computed is legal and useful: the member's act is recorded, and it counts when the run reaches the
 * point where answers matter. Refusing it here would be this deployable re-deriving a lifecycle rule from a status
 * column in another module's table - the drift ADR 0020 exists to avoid, and a rule the worker already applies to
 * its own callers.
 */
@Service
public class ApproveRunService implements ApproveRun {

    private final Approvals approvals;

    public ApproveRunService(Approvals approvals) {
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    @Override
    @Transactional
    public Result<RecordedApproval> approve(NewApproval request) {
        Objects.requireNonNull(request, "request");

        UUID runId = request.runId();
        UUID memberId = request.memberId();

        if (!approvals.hasRun(runId)) {
            return Result.fail(new RunRefusals.NoSuchRun(runId));
        }

        Approvals.Written written = approvals.record(request);

        return switch (written) {
            case RECORDED -> Result.ok(new RecordedApproval(runId, memberId, request.recordedAt()));
            case ALREADY_ANSWERED -> Result.fail(new ApprovalRefusals.AlreadyAnswered(runId, memberId));
            case REFUSED_BY_THE_RUN -> Result.fail(new ApprovalRefusals.NotAcceptable(runId, memberId,
                    "the run's own rows refused it"));
        };
    }
}
