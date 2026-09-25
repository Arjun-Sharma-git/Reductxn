package io.reductxn.fx;

import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.ledger.api.Currency;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of {@link FxRate}.
 *
 * <p>A rate is constructed once from provider data and then used for every conversion, so the checks
 * belong at construction. A malformed rate that is allowed to exist produces plausible-looking wrong
 * amounts across a whole cycle rather than failing once.
 */
class FxRateTest {

    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency INR = Currency.of("INR", 2);
    private static final Instant QUOTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T10:01:00Z");

    private static FxRate valid() {
        return FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "test");
    }

    @Test
    void acceptsAWellFormedRate() {
        FxRate rate = valid();

        assertThat(rate.pair()).isEqualTo("JPY/INR");
        assertThat(rate.rateScaled()).isEqualTo(55_904L);
        assertThat(rate.rateScale()).isEqualTo(5);
        assertThat(rate.hasSpread()).isFalse();
    }

    @Test
    void rejectsACurrencyHavingARateAgainstItself() {
        assertThatThrownBy(() ->
                FxRate.ofMid(INR, INR, 100_000L, 5, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("no rate against itself");
    }

    @Test
    void rejectsANonPositiveRate() {
        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 0L, 5, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("rateScaled must be positive");

        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, -1L, 5, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rejectsAScaleBeyondTheSupportedMaximum() {
        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 55_904L, FxRate.MAX_SCALE + 1, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("rateScale out of range");

        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 55_904L, -1, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rejectsExpiryThatIsNotStrictlyAfterTheQuote() {
        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, QUOTED_AT, "test"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("strictly after");

        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, QUOTED_AT.minusSeconds(1), "test"))
                .isInstanceOf(InvalidRateException.class);
    }

    @Test
    void rejectsNegativeSpreadAndBlankSource() {
        assertThatThrownBy(() ->
                new FxRate(JPY, INR, 55_904L, 5, 55_000L, -1, QUOTED_AT, EXPIRES_AT, "test"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("spreadBps");

        assertThatThrownBy(() ->
                FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "  "))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("source");
    }

    @Test
    void treatsTheQuoteInstantAsUsableAndTheExpiryInstantAsNot() {
        FxRate rate = valid();

        assertThat(rate.isUsableAt(QUOTED_AT)).as("quoted exactly now").isTrue();
        assertThat(rate.isUsableAt(QUOTED_AT.minusMillis(1))).as("before it was quoted").isFalse();
        assertThat(rate.isUsableAt(EXPIRES_AT.minusMillis(1))).as("just before expiry").isTrue();
        assertThat(rate.isUsableAt(EXPIRES_AT)).as("exactly at expiry").isFalse();
    }

    @Test
    void reportsASpreadWhenTheAppliedRateDiffersFromMid() {
        FxRate withSpread =
                new FxRate(JPY, INR, 55_402L, 5, 55_904L, 90, QUOTED_AT, EXPIRES_AT, "test");

        assertThat(withSpread.hasSpread()).isTrue();
        assertThat(withSpread.midRateScaled()).isEqualTo(55_904L);
        assertThat(withSpread.spreadBps()).isEqualTo(90);
    }
}
