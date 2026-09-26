package io.reductxn.api.port.out;

import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.GroupMember;
import io.reductxn.api.port.in.NewMember;

import java.util.List;
import java.util.UUID;

/**
 * The group membership rows. Owned by {@code apps/api} - the tables are this module's own, unlike the settlement
 * reads that cross into {@code workers/settlement} (MASTER-PLAN section 5.5).
 *
 * <p>No transaction wrapper: a list is one statement, and the write is an idempotent insert followed by a read of
 * the row it either wrote or found - which cannot disagree with itself, because the pair is the primary key.
 *
 * <p><strong>The read has no limit parameter</strong>, unlike the runs and cycles reads: see
 * {@code ReadGroupMembers.list} for why a group's membership is the one list a page would make worse.
 */
public interface GroupMembers {

    /** The group's members, in the order they joined. */
    List<GroupMember> listByGroup(UUID groupId);

    /**
     * Records the membership if the group does not already have it, and answers with the row either way.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an upsert: a name the group has already recorded is not changed
     * by a second request. Correcting a name belongs to the admin operation this build does not have, and an upsert
     * here would let any caller rename anybody by asking again.
     *
     * @return the member as stored, with {@link AddedMember#created()} saying whether this call inserted them
     */
    AddedMember add(NewMember member);
}
