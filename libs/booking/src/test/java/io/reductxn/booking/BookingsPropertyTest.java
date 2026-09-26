package io.reductxn.booking;

import io.reductxn.booking.api.Booking;
import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.ChainHead;
import io.reductxn.booking.api.Commitment;
import io.reductxn.booking.api.SettledTransfer;
import io.reductxn.fx.api.AmountTooSmallToConvertException;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.FxLinkage;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SingleCurrencyObligation;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Booking, for any rate and any amount rather than for the worked example.
 *
 * <p>L9 is the interesting one, and it is checked with {@link FxLinkage} - the verifier that will run against
 * the stored entries - rather than by comparing two link ids. A test that compared them would agree with
 * itself even if the verifier had the pairing rule backwards, and that is precisely the failure L9 exists to
 * catch: an entry can be balanced, chained, and alone.
 */
class BookingsPropertyTest {

    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency USD = Currency.of("USD", 2);
    private static final Currency RUB = Currency.of("RUB", 2);

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);
    private static final UUID OBLIGATION = new UUID(0L, 2L);
    private static final UUID TRANSFER = new UUID(0L, 3L);

    private static final Instant MADE_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant LOCKED_AT = Instant.parse("2026-09-20T09:59:00Z");
    private static final Instant QUOTED_AT = Instant.parse("2026-09-20T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-20T11:00:00Z");

    /** Both chains start empty here. Which chain a leg joins is the unit test's question, not this one's. */
    private static final ChainHead HEADS = currency -> null;

    @Property(tries = 300)
    void everyBookingPairsItsLinkAndLeavesTheClearingAccountHoldingWhatItRecorded(
            @ForAll("postableQuotes") FxQuote quote) {

        CrossCurrencyObligation obligation = new CrossCurrencyObligation(ALICE, BORIS, quote);
        Booking booking = Bookings.crossCurrency(
                HEADS,
                new Commitment(obligation.quote().id(), GROUP, MADE_AT, null, null),
                obligation,
                FxClearing.ACCOUNT_ID);

        Money owed = obligation.owed();
        Money settlement = obligation.settlementAmount();

        assertThat(FxLinkage.unpairedLinkIds(booking.entries()))
                .as("L9: a booking produces a pair, whatever the rate and whatever the amount")
                .isEmpty();

        assertThat(booking.base().currency())
                .as("L6: the debt leg is in the debt currency, and the settlement leg is not")
                .isEqualTo(owed.currency());
        assertThat(booking.quote().currency()).isEqualTo(settlement.currency());

        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, owed.currency(), booking.base().postings()))
                .as("L7: the clearing account holds the owed amount in the debt currency, exactly")
                .isEqualTo(owed);
        assertThat(Balances.netDebitOf(
                        FxClearing.ACCOUNT_ID, settlement.currency(), booking.quote().postings()))
                .as("L7: and owes the converted amount in the settlement currency, exactly")
                .isEqualTo(settlement.negate());

        assertThat(net(booking.base().postings()))
                .as("L1: each side balances on its own, which is what makes two entries necessary rather "
                        + "than one mixed entry")
                .isEqualTo(Money.zero(owed.currency()));
        assertThat(net(booking.quote().postings())).isEqualTo(Money.zero(settlement.currency()));
    }

    /**
     * Rates and amounts that produce a postable pair.
     *
     * <p>Filtered rather than shrunk: a conversion can round to zero minor units, and booking refuses that
     * instead of writing a base leg with no partner. That refusal is asserted in the unit test, so excluding
     * those cases here is not hiding them - including them would only make this property fail for a reason
     * that is a different, deliberate behaviour.
     */
    @Provide
    Arbitrary<FxQuote> postableQuotes() {
        Arbitrary<Currency> currencies = Arbitraries.of(JPY, INR, USD, RUB);

        // `Combinators.combine` returns a Combinator4, which offers `as` but not `filter` - so the terms are
        // materialised first and the filter runs on that. Filtering rather than branching inside an `as`
        // because jqwik's `nothing()` carries a Void that has to be disambiguated at every call site, and a
        // filter says the same thing in one place.
        record RateTerms(Currency from, Currency to, long scaled, int scale) {
        }

        Arbitrary<FxRate> rates = Combinators
                .combine(currencies, currencies,
                        Arbitraries.longs().between(1L, 500_000_000L),
                        Arbitraries.integers().between(0, 6))
                .as(RateTerms::new)
                .filter(terms -> !terms.from().equals(terms.to()))
                .map(terms -> FxRate.ofMid(
                        terms.from(), terms.to(), terms.scaled(), terms.scale(),
                        QUOTED_AT, EXPIRES_AT, "property-test"));

        return Combinators.combine(rates, Arbitraries.longs().between(1L, 10_000_000L))
                .as((rate, minor) -> {
                    try {
                        return Optional.of(FxQuote.lock(
                                new UUID(0L, minor), Money.of(minor, rate.base()), rate, LOCKED_AT, false));
                    } catch (AmountTooSmallToConvertException tooSmall) {
                        // libs/fx refuses a conversion that rounds to zero, so a very coarse rate against a
                        // very small amount never becomes a quote at all. That refusal happens before booking
                        // sees anything, which is the right place for it - and it is why the zero check in
                        // Bookings is a guard against a hand-built quote rather than a case that arises here.
                        return Optional.<FxQuote>empty();
                    }
                })
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow);
    }

    private static Money net(List<Posting> postings) {
        Money total = Money.zero(postings.get(0).currency());

        for (Posting posting : postings) {
            total = total.plus(posting.signedAmount());
        }

        return total;
    }

    /**
     * A commitment and its settlement leave both parties flat, for any amount.
     *
     * <p>This is N2 measured on the ledger rather than on the plan's own rows: the plan says which transfers
     * zero every position, and this says that booking those transfers does it for real. The two together are
     * what makes a settled cycle's balances readable - a group whose member still shows the debt after the
     * money moved is the failure this property rules out.
     *
     * <p>Generated over amounts rather than asserted for one, because the interesting cases are the edges:
     * one minor unit, a large amount, and a currency with no minor unit at all. The currencies are chosen to
     * include one of each exponent, since a posting's amount is in the currency's own units.
     */
    @Property(tries = 300)
    void aCommitmentAndItsSettlementLeaveEveryPartyFlat(
            @ForAll("amounts") Money amount) {

        SingleCurrencyObligation owed = new SingleCurrencyObligation(ALICE, BORIS, amount);

        JournalEntry commitment = Bookings.singleCurrency(HEADS, new Commitment(
                OBLIGATION, GROUP, MADE_AT, null, null), owed);

        JournalEntry settlement = Bookings.settlement(
                HEADS,
                new SettledTransfer(TRANSFER, GROUP, ALICE, BORIS, amount, MADE_AT, "reductxn/property/INR"));

        List<Posting> postings = new ArrayList<>(commitment.postings());
        postings.addAll(settlement.postings());

        assertThat(Balances.netDebitOf(ALICE, amount.currency(), postings))
                .as("the payer lands on zero: what they promised is exactly what they paid")
                .isEqualTo(Money.zero(amount.currency()));
        assertThat(Balances.netDebitOf(BORIS, amount.currency(), postings))
                .as("and so does the payee")
                .isEqualTo(Money.zero(amount.currency()));

        assertThat(net(settlement.postings()))
                .as("L1: the settlement entry balances on its own, like every entry")
                .isEqualTo(Money.zero(amount.currency()));
    }

    /** Amounts in currencies with different exponents, including the smallest postable amount. */
    @Provide
    Arbitrary<Money> amounts() {
        Arbitrary<Currency> currencies = Arbitraries.of(JPY, INR, USD, RUB);

        return Combinators.combine(currencies, Arbitraries.longs().between(1L, 10_000_000_000L))
                .as((currency, minor) -> Money.of(minor, currency));
    }
}
