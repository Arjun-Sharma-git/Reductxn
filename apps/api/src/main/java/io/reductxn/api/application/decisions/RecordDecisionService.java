package io.reductxn.api.application.decisions;

import io.reductxn.api.port.in.DecisionRefusals;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadRun;
import io.reductxn.api.port.in.RecordDecision;
import io.reductxn.api.port.in.NewDecision;
import io.reductxn.api.port.in.RecordedDecision;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.Decisions;
import io.reductxn.api.port.out.Groups;
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
    private final Groups groups;

    public RecordDecisionService(ReadRun readRun, Decisions decisions, Groups groups) {
        this.readRun = Objects.requireNonNull(readRun, "readRun");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Transactional
    public Result<RecordedDecision> record(UUID callerId, NewDecision decision) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(decision, "decision");

        UUID runId = decision.runId();

        // The run, then its group, then the write - the order the sibling `execute` endpoint uses, and for its
        // reason: a stranger holding a run id must not be able to tell a run that exists from one that does not, so
        // both answer the same 404. The reader is shared rather than a second "which group is this run's" query,
        // which is one row read one way.
        Result<RunReport> found = readRun.read(runId);

        if (found instanceof Result.Failure<RunReport> refused) {
            return Result.fail(refused.error());
        }

        UUID groupId = ((Result.Ok<RunReport>) found).value().groupId();

        // The same fact and the same refusal every other group write asks (ADR 0049), which is why it is this call
        // and not a second implementation of "may this person write here".
        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
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
