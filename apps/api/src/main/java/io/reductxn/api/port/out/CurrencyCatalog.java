package io.reductxn.api.port.out;

import io.reductxn.ledger.api.Currency;

import java.util.Optional;

/**
 * The currency catalogue: a code as the exponent needed to read amounts in it.
 *
 * <p>A read model over {@code currencies}, which {@code libs/fx} owns (RULE B17, MASTER-PLAN section 5.5).
 * It exists because the wire format does not carry an exponent -
 * {@code Money} on the contract is {@code { amountMinor, currency }} - so every request with an amount in it
 * arrives with a number the server cannot interpret until it knows how many minor units the currency has.
 * Resolving it from a table rather than from a constant map in this module is the whole point of P1: an
 * exponent compiled into a release is one that cannot be corrected without one.
 *
 * <p>{@link Optional} rather than a failure, because "this code is not in the catalogue" is a fact about the
 * request. Whether that is an error is a decision the caller makes, and it is the caller who can say what
 * the client should do about it.
 */
public interface CurrencyCatalog {

    /** The currency with this code, or empty when the catalogue does not have it. */
    Optional<Currency> byCode(String code);
}
