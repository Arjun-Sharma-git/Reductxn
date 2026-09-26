package io.reductxn.api.port.out;

import io.reductxn.ledger.api.JournalEntry;

/**
 * Appending an entry to the ledger.
 *
 * <p>One method, and no read: an entry is built by {@code libs/booking} from the chain head it appends to,
 * so a port that could also return entries would be the second way to do that. The row types stay behind
 * this interface - {@code Record} is mapped inside the adapter - which is what RULE B16 asks for and why the
 * implementations in this module do not use jOOQ's generator.
 *
 * <p><strong>Appending is only safe inside the caller's transaction.</strong> The head the entry was built
 * against was read by {@link ChainHeads}, which takes a lock that lasts as long as the transaction; an
 * append in a different transaction would be outside that lock, and the chain's own unique index would then
 * be the only thing between two writers and a broken chain. The use case is {@code @Transactional} for
 * exactly this reason, and B15 keeps that annotation in one layer.
 */
public interface JournalEntries {

    /**
     * Writes the entry and its postings.
     *
     * @throws IllegalStateException if an entry with this id, or this predecessor, is already stored - a
     *         collision the derived ids are designed to produce rather than a condition to handle
     */
    void append(JournalEntry entry);
}
