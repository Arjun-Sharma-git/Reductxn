package io.reductxn.fx;

import io.reductxn.fx.api.AmountTooSmallToConvertException;
import io.reductxn.fx.api.FxConversion;
import io.reductxn.fx.api.FxConverter;
import io.reductxn.fx.api.FxConverters;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.fx.api.StaleRateException;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The worked examples from {@code docs/specs/fx.md}, plus the edge cases that only show up when the
 * arithmetic is actually run.
 *
 * <p>The numbers here are the same ones the spec states, computed by hand before the implementation
 * existed. That is deliberate: a test written after the code can only confirm that the code agrees
 * with itself.
 */
class FxConverterTest {

    private static final FxConverter FX = FxConverters.exact();

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency RUB = Currency.of("RUB", 2);
    private static final Currency USD = Currency.of("USD", 2);

    private static final Instant QUOTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T10:01:00Z");

    /** 1 JPY = 0.55904 INR, the spec's example rate. */
    private static final FxRate JPY_INR =
            FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "test");

    private static final FxRate RUB_USD =
            FxRate.ofMid(RUB, USD, 10_241L, 6, QUOTED_AT, EXPIRES_AT, "test");

    private static final FxRate INR_JPY =
            FxRate.ofMid(INR, JPY, 178_878L, 5, QUOTED_AT, EXPIRES_AT, "test");

    // --- the spec's worked examples -----------------------------------------------------------

    @Test
    void convertsFiftyThousandYenToRupeesExactly() {
        FxConversion result = FX.convert(Money.of(50_000L, JPY), JPY_INR);

        assertThat(result.converted().amountMinor()).isEqualTo(2_795_200L);
        assertThat(result.converted().currency()).isEqualTo(INR);
        assertThat(result.converted().format()).isEqualTo("27952.00");
        assertThat(result.residualIsZero()).isTrue();
    }

    @Test
    void convertsOneHundredThousandRupeesToDollarsExactly() {
        FxConversion result = FX.convert(Money.of(10_000_000L, RUB), RUB_USD);

        assertThat(result.converted().amountMinor()).isEqualTo(102_410L);
        assertThat(result.converted().currency()).isEqualTo(USD);
        assertThat(result.residualIsZero()).isTrue();
    }

    @Test
    void roundTripsRupeesBackToYen() {
        FxConversion converted = FX.convert(Money.of(2_795_200L, INR), INR_JPY);

        // 49,999.97856 JPY rounds to 50,000, which is the amount we started with.
        assertThat(converted.converted().amountMinor()).isEqualTo(50_000L);
        assertThat(converted.converted().currency()).isEqualTo(JPY);
    }

    // --- the residual -------------------------------------------------------------------------

    @Test
    void booksTheFractionalResidualRatherThanDroppingIt() {
        FxConversion result = FX.convert(Money.of(50_001L, JPY), JPY_INR);

        // Exact value is 2,795,255.904. We book 2,795,256 and record -0.096, so nothing is lost.
        assertThat(result.converted().amountMinor()).isEqualTo(2_795_256L);
        assertThat(result.residualScaled()).isEqualTo(-96L);
        assertThat(result.residualScale()).isEqualTo(3);
        assertThat(result.residualAsDecimalString()).isEqualTo("-0.096");
        assertThat(result.residualIsZero()).isFalse();
    }

    @Test
    void residualIsPositiveWhenRoundingRoundedDown() {
        // 50,006 JPY is 27,954.235424 INR exactly, which is 2,795,535.424 minor units. That fraction
        // is below a half, so it rounds down and the residual is positive.
        FxConversion result = FX.convert(Money.of(50_006L, JPY), JPY_INR);

        assertThat(result.converted().amountMinor()).isEqualTo(2_795_535L);
        assertThat(result.residualScaled()).isEqualTo(424L);
        assertThat(result.residualScale()).isEqualTo(3);
        assertThat(result.residualAsDecimalString()).isEqualTo("0.424");
    }

    @Test
    void residualIsNegativeWhenRoundingRoundedUp() {
        // 50,002 JPY is 2,795,311.808 minor units: above a half, so it rounds up and the residual is
        // negative. Both signs matter, because a sign error here would silently double the loss
        // instead of cancelling it.
        FxConversion result = FX.convert(Money.of(50_002L, JPY), JPY_INR);

        assertThat(result.converted().amountMinor()).isEqualTo(2_795_312L);
        assertThat(result.residualScaled()).isEqualTo(-192L);
        assertThat(result.residualScale()).isEqualTo(3);
        assertThat(result.residualAsDecimalString()).isEqualTo("-0.192");
    }

    // --- X3: never silently vanish ------------------------------------------------------------

    @Test
    void refusesAnAmountTooSmallToBeAYen() {
        // One paisa is 0.01 INR, which is far below one yen.
        assertThatThrownBy(() -> FX.convert(Money.of(1L, INR), INR_JPY))
                .isInstanceOf(AmountTooSmallToConvertException.class)
                .hasMessageContaining("converts to zero")
                .satisfies(thrown -> assertThat(
                        ((AmountTooSmallToConvertException) thrown).code())
                        .isEqualTo("fx.amount_too_small_to_convert"));
    }

    @Test
    void convertsZeroToZeroWithoutRefusing() {
        FxConversion result = FX.convert(Money.of(0L, INR), INR_JPY);

        assertThat(result.converted().amountMinor()).isZero();
        assertThat(result.residualIsZero()).isTrue();
    }

    // --- bad input ----------------------------------------------------------------------------

    @Test
    void rejectsAnAmountThatIsNotInTheRatesBaseCurrency() {
        assertThatThrownBy(() -> FX.convert(Money.of(100_000L, JPY), RUB_USD))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsANegativeAmountBecauseDirectionIsNotItsJob() {
        assertThatThrownBy(() -> FX.convert(Money.of(-50_000L, JPY), JPY_INR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    // --- scale invariance ---------------------------------------------------------------------

    @Test
    void producesTheSameResultHoweverTheRateIsScaled() {
        // 0.55904 written at scale 5 and at scale 8. The cancellation path has to make these agree,
        // otherwise a provider changing its reported scale would silently change every amount.
        FxRate coarse = FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "test");
        FxRate fine = FxRate.ofMid(JPY, INR, 55_904_000L, 8, QUOTED_AT, EXPIRES_AT, "test");

        FxConversion fromCoarse = FX.convert(Money.of(50_001L, JPY), coarse);
        FxConversion fromFine = FX.convert(Money.of(50_001L, JPY), fine);

        assertThat(fromFine.converted()).isEqualTo(fromCoarse.converted());
        assertThat(fromFine.residualScaled()).isEqualTo(fromCoarse.residualScaled());
        assertThat(fromFine.residualScale()).isEqualTo(fromCoarse.residualScale());
    }

    // --- X6: the boundary throws rather than wrapping ------------------------------------------

    @Test
    void convertsAtTheLargestDocumentedProductWithoutThrowing() {
        // rateScale 0, so no cancellation applies and the rate is 10,000 in full. Two things bite
        // here, and both are the point of docs/specs/fx.md section 2:
        //   * the exponent shift is part of the product, so JPY -> INR multiplies by a further 100
        //   * therefore the ceiling is about 9.2 * 10^12 minor units, not the 10^15 an amount-only
        //     bound would suggest
        FxRate rate = FxRate.ofMid(JPY, INR, 10_000L, 0, QUOTED_AT, EXPIRES_AT, "test");

        FxConversion result = FX.convert(Money.of(9_000_000_000_000L, JPY), rate);

        assertThat(result.converted().amountMinor()).isEqualTo(9_000_000_000_000_000_000L);
    }

    @Test
    void throwsRatherThanWrappingWhenTheProductLeavesLong() {
        FxRate rate = FxRate.ofMid(JPY, INR, 10_000L, 0, QUOTED_AT, EXPIRES_AT, "test");

        // 10^15 * 10^4 * 10^2 = 10^21, against a long maximum of about 9.22 * 10^18.
        assertThatThrownBy(() -> FX.convert(Money.of(1_000_000_000_000_000L, JPY), rate))
                .as("a wrapped value would be a plausible-looking wrong amount, so it must throw")
                .isInstanceOf(ArithmeticException.class);
    }

    // --- locking a quote ----------------------------------------------------------------------

    @Test
    void locksAQuoteAndCarriesTheConversionAndResidual() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        Instant lockedAt = Instant.parse("2026-09-25T10:00:30Z");

        FxQuote quote = FxQuote.lock(id, Money.of(50_001L, JPY), JPY_INR, lockedAt, false);

        assertThat(quote.id()).isEqualTo(id);
        assertThat(quote.lockedAt()).isEqualTo(lockedAt);
        assertThat(quote.convertedAmount().amountMinor()).isEqualTo(2_795_256L);
        assertThat(quote.residualScaled()).isEqualTo(-96L);
        assertThat(quote.residualScale()).isEqualTo(3);
        assertThat(quote.asConversion().residualAsDecimalString()).isEqualTo("-0.096");
        assertThat(quote.railPerformedFx()).isFalse();
    }

    @Test
    void refusesToLockAnExpiredRate() {
        assertThatThrownBy(() -> FxQuote.lock(
                UUID.randomUUID(), Money.of(50_000L, JPY), JPY_INR,
                EXPIRES_AT.plusSeconds(1), false))
                .isInstanceOf(StaleRateException.class)
                .hasMessageContaining("not usable at")
                .satisfies(thrown ->
                        assertThat(((StaleRateException) thrown).code()).isEqualTo("fx.rate_stale"));
    }

    @Test
    void refusesToLockARateQuotedInTheFuture() {
        assertThatThrownBy(() -> FxQuote.lock(
                UUID.randomUUID(), Money.of(50_000L, JPY), JPY_INR,
                QUOTED_AT.minusSeconds(1), false))
                .isInstanceOf(StaleRateException.class);
    }

    @Test
    void rejectsAQuoteWhoseAmountsDisagreeWithItsRate() {
        // A quote that mixes pairs would be a plausible-looking wrong number, so the record refuses
        // to exist rather than leaving the inconsistency for a later reader to notice.
        assertThatThrownBy(() -> new FxQuote(
                UUID.randomUUID(), JPY_INR, Money.of(50_000L, USD), Money.of(2_795_200L, INR),
                0L, 0, QUOTED_AT, false))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rejectsAQuoteWithANegativeAmount() {
        // Direction belongs to the transfer instruction, not to an amount (L2). The converter refuses
        // a negative amount already; this closes the same hole at the type, so the invariant holds
        // however the quote was built rather than only when it came through lock().
        assertThatThrownBy(() -> new FxQuote(
                UUID.randomUUID(), JPY_INR, Money.of(-50_000L, JPY), Money.of(2_795_200L, INR),
                0L, 0, QUOTED_AT, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base amount is a magnitude");

        assertThatThrownBy(() -> new FxQuote(
                UUID.randomUUID(), JPY_INR, Money.of(50_000L, JPY), Money.of(-2_795_200L, INR),
                0L, 0, QUOTED_AT, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("converted amount is a magnitude");
    }
}
