package io.reductxn.booking.api;

import io.reductxn.ledger.api.Currency;

/**
 * Where a chain ends, so an entry can be appended to it.
 *
 * <p>A port, not a lookup. An entry's hash covers its {@code prevHash} (L4), so a booking cannot build an
 * entry without knowing what precedes it - and a chain is per {@code (group, currency)}, which means a
 * cross-currency obligation appends to <em>two</em> chains and needs the head of each.
 *
 * <p>It is an interface rather than two {@code String} parameters because that is the honest shape of the
 * dependency: booking needs to know where a chain ends <em>for a currency it discovers by reading the
 * quote</em>, and it needs it twice. Passing the answer in as a function keeps this module pure - no
 * database, no clock - while making the read somebody else's problem, which is the arrangement that lets
 * booking be tested with a stub instead of a container.
 *
 * <p><strong>Whoever implements this is responsible for ordering.</strong> Two appends to one chain read
 * the same head unless the caller serialises them, and the second would then produce an entry whose
 * {@code prevHash} points at an entry that is no longer the chain's end. That is a correctness problem
 * rather than a performance one, it is invisible in the entry itself, and it is why the adapter that
 * implements this takes a lock per {@code (group, currency)} before reading.
 */
@FunctionalInterface
public interface ChainHead {

    /**
     * The hash of the last entry in the chain for {@code currency}, or null when the chain is empty.
     *
     * <p>Null rather than a sentinel string, because L4 defines the start of a chain as having no
     * predecessor, and an empty-string predecessor would be a value that nothing can produce.
     */
    String hashFor(Currency currency);
}
