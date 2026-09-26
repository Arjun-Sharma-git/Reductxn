package io.reductxn.api.web.dto;

import io.reductxn.ledger.api.Money;

/**
 * Integer minor units plus a currency code. Never a floating-point value (P1).
 *
 * <p>The contract's {@code Money} schema, in one place, because both the preview's response and the
 * obligation's request and response carry it. It was nested inside the preview's response until a second
 * endpoint needed it, which is the point at which a nested type becomes a copied one.
 *
 * <p>Not {@code io.reductxn.ledger.api.Money}: that is the ledger's exact-money type, and two types with one
 * name is how a wire format and a domain concept start being confused for each other.
 */
public record MoneyDto(long amountMinor, String currency) {

    /**
     * The wire shape of a ledger amount, in one place.
     *
     * <p>Every mapper used to spell {@code new MoneyDto(money.amountMinor(), money.currency().code())} itself, which
     * is one conversion written three times - and the third copy is where a {@code minorUnitExponent} would quietly
     * get added to a schema that deliberately does not carry one.
     */
    public static MoneyDto of(Money money) {
        return new MoneyDto(money.amountMinor(), money.currency().code());
    }
}
