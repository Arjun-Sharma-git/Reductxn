package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.List;
import java.util.UUID;

/**
 * The use case: the runs of one group, newest first.
 *
 * <p><strong>It exists as a use case rather than as a controller calling a repository</strong> for the reason RULE
 * B12 gives, and it is the smallest one in this module.
 *
 * <p><strong>A {@code Result} since ADR 0054, and the argument for its absence is the reason it changed.</strong>
 * This port used to say: "no {@code Result}, because nothing here can fail. A group with no runs and a group that does
 * not exist are the same answer (an empty list), and both are true - see {@code SettlementRuns.listByGroup} for why
 * inventing a refusal for the second would mean asking a question this build cannot answer." The claim was true of
 * the *rows* and false of the *request*: a group the caller is not in is not a group whose runs are theirs to count,
 * and answering it with an empty list is not a refusal that cannot be asked - it is an answer a stranger can read,
 * which is what made nine reads in this module the half of ADR 0043 that was left open. The caller travels beside
 * the request, the membership check comes before the rows are read, and the refusal is
 * {@link GroupRefusals.NoSuchGroup} - one answer for "no such group" and "not yours" (ADR 0044).
 *
 * <p><strong>Why this exists at all, when the run read endpoint already does.</strong> A member cannot approve a plan
 * they cannot find. The contract had `GET /runs/{runId}` and no way to list runs, so the approval path was reachable
 * only by somebody who already had the identifier - which in this build means whoever ran the worker's command line
 * and copied it. The screen that answers "what does this group have to decide?" is the missing half of ADR 0020's
 * approval, not a convenience around it.
 */
public interface ReadGroupRuns {

    /**
     * At most {@code limit} runs, most recently created first.
     *
     * @param limit clamped by the caller, because a limit is a boundary concern rather than a rule
     */
    Result<List<RunReport>> list(UUID callerId, UUID groupId, int limit);
}
