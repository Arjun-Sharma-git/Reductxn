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

    /**
     * Whether the group already has this member.
     *
     * <p><strong>Added for the acceptance path, and its read-then-act shape is deliberate there.</strong> Accepting a
     * code asks "is this person already one of us" to decide whether an invitation should be spent at all, and the
     * remaining race is benign in the only way that matters: two acceptances of the *same* code by the *same* person at
     * the same instant may spend one use more than they needed, and those uses are their own. Nothing worse is possible,
     * because the membership write is idempotent whatever this answers - {@link #add} remains the thing that makes a
     * double membership impossible, and this is only the question "is there anything to do".
     *
     * <p>An earlier version of the acceptance avoided this method by spending the use first, which is correct about
     * exhaustion and wrong about the case the slice exists for: somebody who is already in the group, spending an
     * invitation that nobody else then gets. Refusing to spend for a membership that will not be recorded is worth one
     * extra read.
     */
    boolean has(UUID groupId, UUID userId);
}
