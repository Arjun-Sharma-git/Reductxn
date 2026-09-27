package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.List;
import java.util.UUID;

/**
 * The use case: what has happened in a group, newest first.
 *
 * <p><strong>Membership is the condition, and it is the same condition as the group read.</strong> A group the caller is not
 * in answers {@link GroupRefusals.NoSuchGroup} - one code for "no group with that id" and "not a group you are in", for the
 * reason ADR 0043 records: telling them apart would let anybody holding a guess learn which ids are real. A feed is
 * *especially* the read that must not leak, because it is the one that names people.
 *
 * <p><strong>Three kinds and no cursor.</strong> The history is the memberships, the settlement decisions and the
 * invitations - the rows that already record an act - and it is paged by a limit rather than a cursor, which is the decision
 * the obligations read already made: a cursor needs a total order that stays stable as a union grows, and this build has not
 * earned one yet. The contract's `cursor` parameter is deleted rather than left promising pagination nothing implements.
 */
public interface ReadActivity {

    /**
     * The group's history, newest first.
     *
     * @param groupId  the group
     * @param callerId who is asking, from the token
     * @param limit    how many events to answer with, clamped by the service
     * @return the page, or {@link GroupRefusals.NoSuchGroup} when the caller is not a member of that group
     */
    Result<List<ActivityEvent>> of(UUID groupId, UUID callerId, int limit);
}
