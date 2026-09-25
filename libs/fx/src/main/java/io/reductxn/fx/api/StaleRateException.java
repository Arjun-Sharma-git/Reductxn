package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;

/**
 * Raised when a rate exists but is too old to lock against.
 *
 * <p>Deliberately a refusal rather than a warning. Locking a stale quote would promise a creditor an
 * amount computed from a rate the market has moved away from, and the drift lands on someone who
 * never agreed to carry it (ADR 0013, point 6). Failing closed is the correct behaviour; an
 * unavailable rate is an availability incident, a guessed rate is an accounting incident.
 *
 * <p>Same-currency obligations are unaffected by this and keep working, so a provider outage degrades
 * part of the product rather than all of it.
 */
public final class StaleRateException extends FxException {

    private static final long serialVersionUID = 1L;

    private final String baseCode;
    private final String quoteCode;
    private final Instant quotedAt;
    private final Instant expiresAt;
    private final Instant asOf;

    public StaleRateException(
            Currency base, Currency quote, Instant quotedAt, Instant expiresAt, Instant asOf) {
        super("fx.rate_stale",
                "rate " + base.code() + "/" + quote.code() + " quoted at " + quotedAt
                        + " and valid until " + expiresAt + " is not usable at " + asOf);
        this.baseCode = base.code();
        this.quoteCode = quote.code();
        this.quotedAt = quotedAt;
        this.expiresAt = expiresAt;
        this.asOf = asOf;
    }

    public String baseCode() {
        return baseCode;
    }

    public String quoteCode() {
        return quoteCode;
    }

    public Instant quotedAt() {
        return quotedAt;
    }

    /** When the rate stopped being usable. */
    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant asOf() {
        return asOf;
    }
}
