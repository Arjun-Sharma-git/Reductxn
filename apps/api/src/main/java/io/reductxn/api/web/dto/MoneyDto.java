package io.reductxn.api.web.dto;

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
}
