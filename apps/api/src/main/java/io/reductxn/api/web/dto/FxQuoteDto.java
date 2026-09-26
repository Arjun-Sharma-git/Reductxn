package io.reductxn.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code FxQuote} schema: a locked conversion, as the client is told about it.
 *
 * <p>The rate travels in full, including the provider's mid rate and the spread, because the contract says the
 * spread is <em>disclosed</em> rather than hidden: a client showing a group what a conversion cost cannot
 * infer it from the applied rate alone, and a client that has both can show it without asking again. That is
 * also why {@code FxRate} stores both.
 *
 * <p>{@code residualScaled} and {@code residualScale} are sent as the fraction they are, never as a whole minor
 * unit. The residual is genuinely fractional - always inside half a minor unit of the target currency - and
 * rounding it here would undo the care the conversion takes over not losing value (ADR 0013 point 3).
 */
public record FxQuoteDto(
        UUID id,
        RateDto rate,
        MoneyDto baseAmount,
        MoneyDto convertedAmount,
        long residualScaled,
        int residualScale,
        Instant lockedAt,
        Boolean railPerformedFx) {

    /**
     * The contract's {@code FxRate} schema.
     *
     * <p>{@code base} and {@code quote} are currency codes, as the contract's {@code Currency} schema is. The
     * exponents are not on the wire: the amounts carry codes too, and a client that needs to render them gets
     * the exponent from the currency catalogue once and caches it, which is what {@code CurrencyInfo} is for.
     */
    public record RateDto(
            String base,
            String quote,
            long rateScaled,
            int rateScale,
            long midRateScaled,
            int spreadBps,
            Instant quotedAt,
            Instant expiresAt,
            String source) {
    }
}
