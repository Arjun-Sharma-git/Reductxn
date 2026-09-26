package io.reductxn.api.web.dto;

import io.reductxn.fx.api.FxRate;

import java.time.Instant;

/**
 * The contract's {@code FxRate} schema: a rate as it is told to a client.
 *
 * <p>Top level rather than nested inside {@link FxQuoteDto}, which is where it started, because two endpoints now
 * send it: a locked quote carries the rate it was locked at, and {@code GET /fx/rates} answers with a rate on its own.
 * One type for the schema means the two cannot describe it differently (ADR 0021), and the refactor is the same move
 * the client made when a second screen wanted its form field.
 *
 * <p>The rate travels in full, including the provider's mid rate and the spread, because the contract says the spread
 * is <em>disclosed</em> rather than hidden: a screen showing what a conversion costs cannot infer it from the applied
 * rate alone. {@code quotedAt} and {@code expiresAt} are both on the wire for the same reason - a rate a screen calls
 * "current" should be able to say how old it is, especially the reference read, whose whole job is to say whether a
 * cross-currency commitment is possible yet (ADR 0037).
 */
public record FxRateResponse(
        String base,
        String quote,
        long rateScaled,
        int rateScale,
        long midRateScaled,
        int spreadBps,
        Instant quotedAt,
        Instant expiresAt,
        String source) {

    public static FxRateResponse of(FxRate rate) {
        return new FxRateResponse(
                rate.base().code(),
                rate.quote().code(),
                rate.rateScaled(),
                rate.rateScale(),
                rate.midRateScaled(),
                rate.spreadBps(),
                rate.quotedAt(),
                rate.expiresAt(),
                rate.source());
    }
}
