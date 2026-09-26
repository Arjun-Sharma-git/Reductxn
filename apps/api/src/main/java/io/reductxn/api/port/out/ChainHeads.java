package io.reductxn.api.port.out;

import io.reductxn.booking.api.ChainHead;

import java.util.UUID;

/**
 * The chain heads of one group, which is what booking needs to build an entry (L4).
 *
 * <p><strong>Per group, because booking's port is per currency.</strong> {@code ChainHead.hashFor(currency)}
 * takes a currency and nothing else - correct for booking, which knows only the currencies it is posting in -
 * but a chain is per {@code (group, currency)}, so the group has to come from somewhere. It comes from here:
 * the use case asks for the group's heads once and hands booking a view of them, which is one object instead
 * of a parameter booking would have to carry in every signature for a value it never looks at.
 *
 * <p><strong>The implementation takes a lock per chain before answering.</strong> {@code ChainHead}'s own
 * documentation puts the responsibility for ordering on whoever implements it, and this is that place. Two
 * appends to one chain that read the same head produce two entries claiming the same predecessor - invisible
 * in both entries, and a broken chain to any verifier. The lock belongs in the database rather than in the
 * process: a chain is shared by every instance of this application, so an in-JVM lock would serialise one
 * of them and leave the rest racing.
 */
public interface ChainHeads {

    /**
     * A view of this group's chains, one per currency, safe to append to within the current transaction.
     */
    ChainHead forGroup(UUID groupId);
}
