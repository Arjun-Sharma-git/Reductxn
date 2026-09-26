package io.reductxn.api.port.in;

import io.reductxn.ledger.api.Currency;

import java.util.List;

/**
 * The use case: the currency catalogue, for a client that needs an exponent.
 *
 * <p>The contract's own words for why this read exists: {@code Money} on the wire is {@code { amountMinor, currency }}
 * and carries no exponent, so a client cannot render a single amount - "the server deliberately does not repeat the
 * exponent on every amount, because it is reference data". Every screen that shows money reads this once and indexes
 * it, so until this endpoint existed the client's amounts had nowhere to get their exponent from and the catalogue
 * query answered with whatever a failed request answers with.
 *
 * <p>It answers the ledger's {@link Currency}, which is the type this module already uses wherever an amount and its
 * exponent travel together ({@code RecordedObligation} carries one). A type of its own for the same two fields would
 * be a second shape to keep in step with the first, for nothing.
 *
 * <p>{@code is_cbdc} and {@code jurisdiction} are in the table and not served, deliberately and in one place: no
 * screen consumes either, a rail needs the first and tax the second, and a field nobody consumes is a field nobody
 * notices is wrong. They arrive with their consumers.
 */
public interface ReadCurrencies {

    /** Every currency this deployment can transact, ordered by code. */
    List<Currency> all();
}
