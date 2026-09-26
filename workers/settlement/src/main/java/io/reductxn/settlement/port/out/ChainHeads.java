package io.reductxn.settlement.port.out;

import io.reductxn.booking.api.ChainHead;

import java.util.UUID;

/**
 * Where a group's ledger chains end, so an entry can be appended to them.
 *
 * <p>The same dependency {@code apps/api} has, expressed the same way: {@code libs/booking} cannot build an
 * entry without its predecessor's hash (L4), and it takes the answer as a function so that booking stays pure
 * and the read is somebody else's problem.
 *
 * <p><strong>Whoever implements this owes the caller a lock, not just a read.</strong> Two appends to one
 * chain read the same head unless the caller serialises them, and the second then produces an entry whose
 * {@code prevHash} points at something that is no longer the chain's end - invisible in either entry and
 * visible only to a verifier. The adapter takes a lock per {@code (group, currency)} and holds it until the
 * transaction commits, which is why its implementation refuses to be asked outside one.
 */
public interface ChainHeads {

    /** The heads of this group's chains, looked up per currency as a booking discovers them. */
    ChainHead forGroup(UUID groupId);
}
