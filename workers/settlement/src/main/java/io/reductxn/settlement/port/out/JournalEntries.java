package io.reductxn.settlement.port.out;

import io.reductxn.ledger.api.JournalEntry;

/**
 * Appending an entry to the ledger.
 *
 * <p><strong>This module is the ledger's second writer, and the reason is ownership of the fact rather than of
 * the table.</strong> {@code apps/api} books a commitment when it is recorded; a run books a movement when the
 * rail confirms it, and the only process that knows a movement happened is this one. The alternative -
 * publishing an event for the API to book - would make a settlement's recording depend on another
 * deployable's availability, and would put the exact knowledge of what moved at a distance from the write.
 * RULE B17 is about which module's migration creates a table; the booking *shape* is {@code libs/booking}'s
 * (RULE B22) and stays there, which is what keeps two writers from disagreeing about what an entry is.
 *
 * <p>One method, and no read: the entry arrives built, from {@code libs/booking}, against the chain head this
 * adapter's sibling read. A port that could also return entries would be a second way to do that.
 *
 * <p><strong>Appending is only safe inside the caller's transaction</strong>, and the collision behaviour is
 * the worker's rather than the API's:
 *
 * <ul>
 *   <li>The head the entry was built against was read under a Postgres advisory lock that lasts until the
 *       transaction commits ({@link ChainHeads}), so an append outside one is outside the lock.</li>
 *   <li><strong>A duplicate entry id is not an error here.</strong> An entry's id is derived from the transfer
 *       it records, so two worker instances that confirm the same transfer at the same moment compute the same
 *       id - and the second one's append is a no-op rather than a failure. That is the right answer for a
 *       worker, where two instances overlapping is ordinary: the movement is recorded once, both callers see
 *       success, and the loser's entry (which differs only in the hash of a predecessor that is no longer the
 *       chain's end) is dropped. The chain is not forked, because the lock serialises the two writers and the
 *       second reads the first's entry as its predecessor.</li>
 * </ul>
 */
public interface JournalEntries {

    /**
     * Writes the entry and its postings, or does nothing when this entry id is already stored.
     *
     * @param entry the entry to append, built by {@code libs/booking} against a locked chain head
     */
    void append(JournalEntry entry);
}
