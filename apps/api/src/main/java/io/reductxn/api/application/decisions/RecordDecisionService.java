package io.reductxn.api.application.decisions;

import io.reductxn.api.port.in.DecisionRefusals;
import io.reductxn.api.port.in.ReadRun;
import io.reductxn.api.port.in.RecordDecision;
import io.reductxn.api.port.in.NewDecision;
import io.reductxn.api.port.in.RecordedDecision;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.Decisions;
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
 * <p><strong>Four refusals now, and the two in the middle are the pair worth keeping apart.</strong> A run that does
 * not exist has nothing to answer; a caller who is not a member of the group whose books it settles is refused the
 * way every other group write refuses them, with the same {@code no_such_group} (ADR 0049, 0053); a member who has
 * already answered is refused rather than overwritten, because a decision is final
 * ({@code docs/specs/settlement.md} section 4.1) and the primary key is what makes that true under concurrency
 * rather than only in a single request; and an answer the run's rows will not hold - the group's clearing account
 * cannot answer, for instance - is reported as the store's refusal rather than as a {@code 500}.
 *
 * <p><strong>That membership check is about who may write, not about whose answer counts</strong>, and collapsing
 * the two would be wrong in both directions: a member of the group who owes nothing may answer, their row is
 * written and shown, and the run's arithmetic counts it for nothing - deliberately, because a fact that is hidden is
 * a fact nobody fixes (ADR 0016's amendment). What is *not* asked here is whether the caller is a participant: that
 * is the quorum's scope, it is a rule over the run's own rows, and re-deriving it in this deployable is the drift
 * ADR 0020 refuses.
 *
 * <p>What this class deliberately does not do is check the run's status. Recording an answer for a run that is
 * still being computed is legal and useful: the member's act is recorded, and it counts when the run reaches the
 * point where answers matter. Refusing it here would be this deployable re-deriving a lifecycle rule from a status
 * column in another module's table - the drift ADR 0020 exists to avoid, and a rule the worker already applies to
 * its own callers.
 */
@Service
public class RecordDecisionService implements RecordDecision {

    private final ReadRun readRun;
    private final Decisions decisions;

    public RecordDecisionService(ReadRun readRun, Decisions decisions) {
        this.readRun = Objects.requireNonNull(readRun, "readRun");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
    }

    @Override
    @Transactional
    public Result<RecordedDecision> record(UUID callerId, NewDecision decision) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(decision, "decision");

        UUID runId = decision.runId();

        // The run, and the caller's right to answer it, in one read - since ADR 0054 the store narrows by membership, so
        // a run in a group this caller is not in is not a row and answers `no_such_run`. The order is unchanged: the run
        // first, then the write, and a stranger learns nothing about whether the id they sent is real.
        //
        // **The refusal is now `no_such_run` where ADR 0053 chose `no_such_group`, and that is a correction the narrowed
        // read made rather than a change of guard.** Both are `404` and both refuse a stranger, but `no_such_group`'s
        // message names the group id it could not find - so an unauthenticated request holding any run uuid was handed
        // the group that run belongs to, in the refusal's own prose. A run-scoped route answers a run-scoped code, which
        // names only the id the caller sent.
        Result<RunReport> found = readRun.read(callerId, runId);

        if (found instanceof Result.Failure<RunReport> refused) {
            return Result.fail(refused.error());
        }

        Decisions.Written written = decisions.record(callerId, decision);

        return switch (written) {
            case RECORDED -> Result.ok(new RecordedDecision(runId, callerId, decision.decision(),
                    decision.recordedAt()));
            case ALREADY_ANSWERED -> Result.fail(new DecisionRefusals.AlreadyAnswered(runId, callerId));
            case REFUSED_BY_THE_RUN -> Result.fail(new DecisionRefusals.NotAcceptable(runId, callerId,
                    "the run's own rows refused it"));
        };
    }
}
