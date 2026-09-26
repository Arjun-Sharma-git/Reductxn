package io.reductxn.api.port.in;

import java.util.List;
import java.util.UUID;

/**
 * The use case: the runs of one group, newest first.
 *
 * <p><strong>It exists as a use case rather than as a controller calling a repository</strong> for the reason RULE
 * B12 gives, and it is the smallest one in this module: no {@code Result}, because nothing here can fail. A group
 * with no runs and a group that does not exist are the same answer (an empty list), and both are true - see
 * {@code SettlementRuns.listByGroup} for why inventing a refusal for the second would mean asking a question this
 * build cannot answer.
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
    List<RunReport> list(UUID groupId, int limit);
}
