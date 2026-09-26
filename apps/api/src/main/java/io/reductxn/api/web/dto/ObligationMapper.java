package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.NewObligation;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Money;

import java.time.Instant;

/**
 * Between the contract's shapes and the ports' types.
 *
 * <p>The only class that knows both, as with the preview's mapper: the use case speaks
 * {@link NewObligation} and {@link RecordedObligation}, the wire speaks
 * {@link RecordObligationRequest} and {@link ObligationResponse}, and if either changes it is this file that
 * changes rather than a controller.
 */
public final class ObligationMapper {

    private ObligationMapper() {
    }

    /**
     * The request as the use case's command.
     *
     * @param now the boundary's instant, used when the client did not send one. Read here rather than in the
     *            use case, which may not read a clock at all (RULE B21)
     */
    public static NewObligation toCommand(
            RecordObligationRequest request, Instant now, String idempotencyKey) {

        return new NewObligation(
                request.groupId(),
                request.debtorId(),
                request.creditorId(),
                request.amount().amountMinor(),
                request.amount().currency(),
                request.settleInCurrency(),
                request.memo(),
                request.occurredAt() == null ? now : request.occurredAt(),
                idempotencyKey);
    }

    public static ObligationResponse toResponse(RecordedObligation obligation) {
        return new ObligationResponse(
                obligation.id(),
                obligation.groupId(),
                obligation.debtorId(),
                obligation.creditorId(),
                new MoneyDto(obligation.amount().amountMinor(), obligation.amount().currency().code()),
                obligation.settleInCurrency().code(),
                obligation.fxQuote() == null ? null : quote(obligation.fxQuote()),
                obligation.memo(),
                statusName(obligation.status()),
                obligation.occurredAt());
    }

    /**
     * The locked conversion, with the rate the client was charged at.
     *
     * <p>{@code railPerformedFx} is written only when true, as in the preview's optional fields: the contract
     * says it is true when the rail performed the conversion, so false is the default a client already has.
     */
    private static FxQuoteDto quote(FxQuote quote) {
        FxRate rate = quote.rate();

        return new FxQuoteDto(
                quote.id(),
                new FxQuoteDto.RateDto(
                        rate.base().code(),
                        rate.quote().code(),
                        rate.rateScaled(),
                        rate.rateScale(),
                        rate.midRateScaled(),
                        rate.spreadBps(),
                        rate.quotedAt(),
                        rate.expiresAt(),
                        rate.source()),
                money(quote.baseAmount()),
                money(quote.convertedAmount()),
                quote.residualScaled(),
                quote.residualScale(),
                quote.lockedAt(),
                quote.railPerformedFx() ? Boolean.TRUE : null);
    }

    private static MoneyDto money(Money money) {
        return new MoneyDto(money.amountMinor(), money.currency().code());
    }

    /**
     * The status enum as the contract spells it, in an exhaustive switch rather than
     * {@code name().toLowerCase()}.
     *
     * <p>Same reasoning as the preview's algorithm names: lower-casing with the default locale is both a
     * localization bug and a rename hazard, and here the wire value is also what a client switches on. A new
     * constant in {@code ObligationStatus} must fail to compile here rather than appear in a response.
     */
    private static String statusName(ObligationStatus status) {
        return switch (status) {
            case OPEN -> "open";
            case NETTED -> "netted";
            case SETTLED -> "settled";
            case VOIDED -> "voided";
        };
    }
}
