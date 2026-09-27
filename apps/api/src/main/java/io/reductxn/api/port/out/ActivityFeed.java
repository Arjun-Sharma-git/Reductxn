package io.reductxn.api.port.out;

import io.reductxn.api.port.in.ActivityEvent;

import java.util.List;
import java.util.UUID;

/**
 * A group's history, read from the rows that recorded it.
 *
 * <p><strong>Three tables, three modules' worth of ownership, one read.</strong> {@code group_members} and
 * {@code invitations} are this module's (MASTER-PLAN section 5.5); {@code settlement_decisions} belongs to the settlement
 * worker (RULE B17). Reading across that line is what this module already does to derive an obligation's status and to list
 * cycles, and the rule it keeps is the one that matters: the worker owns the rows, this reads them and writes nothing. A
 * foreign key in either direction would make one deployable's migrations a prerequisite of the other's, which is why the
 * worker's own tables say so in their comments.
 *
 * <p><strong>Where a decision's group comes from is worth knowing before reading the adapter.</strong>
 * {@code settlement_decisions} has no {@code group_id}: a decision is about a run. So attributing one to a group is a join
 * through {@code settlement_runs.group_id} - and the run, not the cycle, is what carries it, which makes it one join rather
 * than two.
 *
 * <p>The port answers with the same type the API does, because there is no storage shape to map: what the rows say and what
 * a client is told are the same three kinds of fact. A separate stored type would be a translation with nothing to
 * translate.
 */
public interface ActivityFeed {

    /**
     * The group's most recent events, newest first.
     *
     * <p>{@code limit} is per source rather than per page: the store clamps each of its three reads to it and merges what
     * came back, because the newest overall event cannot be known without looking at the newest of each kind.
     *
     * @param groupId the group whose history this is
     * @param limit   how many events the caller wants, already clamped by the use case
     */
    List<ActivityEvent> recent(UUID groupId, int limit);
}
