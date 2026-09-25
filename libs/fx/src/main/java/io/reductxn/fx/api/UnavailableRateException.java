package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;

/**
 * Raised when no rate could be obtained at all, for example a provider outage.
 *
 * <p>Carries the pair so the caller can report which part of the product is degraded. The port
 * declares this so an adapter cannot quietly return a placeholder rate instead (ADR 0013, point 6).
 */
public final class UnavailableRateException extends FxException {

    private static final long serialVersionUID = 1L;

    private final String baseCode;
    private final String quoteCode;
    private final Instant asOf;
    private final String reason;

    public UnavailableRateException(Currency base, Currency quote, Instant asOf, String reason) {
        super("fx.rate_unavailable",
                "no rate for " + base.code() + "/" + quote.code() + " at " + asOf + ": " + reason);
        this.baseCode = base.code();
        this.quoteCode = quote.code();
        this.asOf = asOf;
        this.reason = reason;
    }

    public String baseCode() {
        return baseCode;
    }

    public String quoteCode() {
        return quoteCode;
    }

    public Instant asOf() {
        return asOf;
    }

    /** Short developer description of why no rate was available. Not a client-facing string. */
    public String reason() {
        return reason;
    }
}
