package io.reductxn.settlement;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.TaxRules;
import io.reductxn.netting.api.TransferInstruction;
import io.reductxn.settlement.api.CrossCurrencyObligation;
import io.reductxn.settlement.api.CurrencyPlan;
import io.reductxn.settlement.api.CycleObligation;
import io.reductxn.settlement.api.SettlementPlan;
import io.reductxn.settlement.api.SettlementPlanner;
import io.reductxn.settlement.api.SettlementPlans;
import io.reductxn.settlement.api.SingleCurrencyObligation;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composition properties from ADR 0012, over generated cycles.
 *
 * <p><strong>Scope is deliberate.</strong> These do not re-prove N4, N5 or N6 - those are properties of
 * the single-currency engine, they are already asserted in {@code libs/netting}, and this module hands
 * that engine unmodified input. Re-asserting them here would duplicate a proof rather than add one.
 *
 * <p>What is asserted here is what the <em>composition</em> can get wrong: that the two legs of a
 * cross-currency obligation are consistent (X7, and the clearing position checked against the cycle
 * directly), that every currency in the cycle is accounted for, that the clearing participant settles
 * like any other, and that a plan is reproducible from the same obligations in any order.
 */
class SettlementPlannerPropertyTest {

    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency USD = Currency.of("USD", 2);
    private static final Currency TND = Currency.of("TND", 3);

    /** Mixed exponents on purpose: 0, 2 and 3, so the exponent shift is genuinely exercised. */
    private static final List<Currency> CURRENCIES = List.of(JPY, INR, USD, TND);

    private static final List<UUID> MEMBERS =
            IntStream.range(0, 6).mapToObj(index -> new UUID(0L, index + 1L)).toList();

    private static final Instant QUOTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T11:00:00Z");
    private static final Instant LOCKED_AT = Instant.parse("2026-09-25T10:00:30Z");

    private static final SettlementPlanner PLANNER = SettlementPlans.perCurrency();

    /** 10^exponent, without floating point (P12). */
    private static long pow10(int exponent) {
        long value = 1L;
        for (int index = 0; index < exponent; index++) {
            value *= 10L;
        }
        return value;
    }

    // --- generators ---------------------------------------------------------------------------

    /** A cycle of up to eight obligations. Empty is included: a cycle with nothing in it is valid. */
    @Provide
    Arbitrary<List<CycleObligation>> cycles() {
        return obligations().list().ofMinSize(0).ofMaxSize(8);
    }

    /** A cycle that touches no second currency, so nothing needs external funding. */
    @Provide
    Arbitrary<List<CycleObligation>> singleCurrencyCycles() {
        return singleCurrencyObligations().list().ofMinSize(1).ofMaxSize(8);
    }

    @Provide
    Arbitrary<CycleObligation> obligations() {
        return Arbitraries.oneOf(singleCurrencyObligations(), crossCurrencyObligations());
    }

    /**
     * The creditor is derived from an offset inside 1..n-1, so a member can never owe themselves and
     * the offset can never be zero.
     */
    @Provide
    Arbitrary<CycleObligation> singleCurrencyObligations() {
        Arbitrary<Integer> debtorIndex = Arbitraries.integers().between(0, MEMBERS.size() - 1);
        Arbitrary<Integer> creditorOffset = Arbitraries.integers().between(1, MEMBERS.size() - 1);
        Arbitrary<Currency> currency = Arbitraries.of(CURRENCIES);
        Arbitrary<Long> amount = Arbitraries.longs().between(0L, 1_000_000L);

        return Combinators.combine(debtorIndex, creditorOffset, currency, amount)
                .as((debtor, offset, curr, minor) -> new SingleCurrencyObligation(
                        MEMBERS.get(debtor),
                        MEMBERS.get((debtor + offset) % MEMBERS.size()),
                        Money.of(minor, curr)));
    }

    /**
     * A locked cross-currency obligation.
     *
     * <p><strong>The amount is generated from a computed floor, not from 1.</strong> Settling into a
     * coarser currency shrinks the amount by the exponent shift, so a rate at or above 1 does not
     * prevent rounding to zero: one paisa at 3.035 INR per USD is 0.03035 JPY, which rounds away and
     * is refused (X3, correctly). A generator that ignored this would fail inside itself rather than
     * exercise the planner.
     */
    @Provide
    Arbitrary<CycleObligation> crossCurrencyObligations() {
        Arbitrary<Integer> debtorIndex = Arbitraries.integers().between(0, MEMBERS.size() - 1);
        Arbitrary<Integer> creditorOffset = Arbitraries.integers().between(1, MEMBERS.size() - 1);

        return crossTerms().flatMap(terms ->
                Arbitraries.longs().between(terms.minimumAmount(), 1_000_000L).flatMap(minor ->
                        Combinators.combine(debtorIndex, creditorOffset).as((debtor, offset) -> {
                            FxRate rate = FxRate.ofMid(terms.from(), terms.to(), terms.rateScaled(),
                                    terms.rateScale(), QUOTED_AT, EXPIRES_AT, "property-test");
                            FxQuote quote = FxQuote.lock(new UUID(minor, terms.rateScaled()),
                                    Money.of(minor, terms.from()), rate, LOCKED_AT, false);
                            return (CycleObligation) new CrossCurrencyObligation(
                                    MEMBERS.get(debtor),
                                    MEMBERS.get((debtor + offset) % MEMBERS.size()),
                                    quote);
                        })));
    }

    /** The pair, the rate, and the smallest amount that survives conversion through it. */
    private record CrossTerms(Currency from, Currency to, long rateScaled, int rateScale,
                              long minimumAmount) {
    }

    @Provide
    Arbitrary<CrossTerms> crossTerms() {
        return Arbitraries.integers().between(0, CURRENCIES.size() - 1).flatMap(baseIndex ->
                Arbitraries.integers().between(1, CURRENCIES.size() - 1).flatMap(quoteOffset ->
                        Arbitraries.integers().between(0, 4).flatMap(scale ->
                                Arbitraries.longs().between(pow10(scale), 10_000L).map(scaled -> {
                                    Currency from = CURRENCIES.get(baseIndex);
                                    Currency to = CURRENCIES.get(
                                            (baseIndex + quoteOffset) % CURRENCIES.size());
                                    return new CrossTerms(from, to, scaled, scale,
                                            smallestConvertibleAmount(from, to, scaled, scale));
                                }))));
    }

    /**
     * The smallest source amount that converts to at least one minor unit of the target.
     *
     * <p>Derived from the same shape the converter uses: the numerator carries the rate and any upward
     * exponent shift, the denominator carries the rate scale and any downward one.
     */
    private static long smallestConvertibleAmount(
            Currency from, Currency to, long rateScaled, int rateScale) {
        int shift = to.minorUnitExponent() - from.minorUnitExponent();
        long numeratorPerSourceMinor = rateScaled * pow10(Math.max(0, shift));
        long denominator = pow10(rateScale + Math.max(0, -shift));
        // +2 rather than +1: rounding is half-up, and a unit of slack keeps the floor off the boundary
        // rather than exactly on it.
        return denominator / numeratorPerSourceMinor + 2L;
    }

    // --- X7: per-currency zero-sum, including the clearing account ----------------------------

    @Property
    void x7_positionsSumToZeroInEveryCurrency(@ForAll("cycles") List<CycleObligation> cycle) {
        SettlementPlan plan = PLANNER.plan(cycle, TaxRules.none());

        for (CurrencyPlan partition : plan.partitions()) {
            long total = partition.netting().positions().stream()
                    .mapToLong(position -> position.net().amountMinor())
                    .sum();

            // Members plus the internal account. Including the clearing account is the whole point:
            // without it, zero-sum would be a coincidence rather than evidence that the two legs of
            // every cross-currency obligation were built consistently.
            assertThat(total)
                    .as("X7: positions including fx_clearing sum to zero in %s",
                            partition.currency().code())
                    .isZero();
        }
    }

    @Property
    void theClearingPositionIsExactlyTheNetOfTheCrossCurrencyLegs(
            @ForAll("cycles") List<CycleObligation> cycle) {
        SettlementPlan plan = PLANNER.plan(cycle, TaxRules.none());

        for (CurrencyPlan partition : plan.partitions()) {
            // Recomputed from the cycle itself, not from the plan, so this checks the two-leg
            // decomposition rather than checking the engine against itself.
            long expected = 0L;
            for (CycleObligation obligation : cycle) {
                if (obligation instanceof CrossCurrencyObligation cross) {
                    if (cross.debtCurrency().equals(partition.currency())) {
                        expected = Math.addExact(expected, cross.owed().amountMinor());
                    }
                    if (cross.settlementCurrency().equals(partition.currency())) {
                        expected = Math.subtractExact(expected, cross.settlementAmount().amountMinor());
                    }
                }
            }

            assertThat(partition.clearingPosition().amountMinor())
                    .as("the two legs in %s net to the clearing position", partition.currency().code())
                    .isEqualTo(expected);
        }
    }

    // --- completeness -------------------------------------------------------------------------

    @Property
    void everyCurrencyInTheCycleAppearsExactlyOnceAsAPartition(
            @ForAll("cycles") List<CycleObligation> cycle) {
        SettlementPlan plan = PLANNER.plan(cycle, TaxRules.none());

        Set<Currency> touched = new HashSet<>();
        for (CycleObligation obligation : cycle) {
            touched.add(obligation.owed().currency());
            touched.add(obligation.settlementCurrency());
        }

        // A currency reached only through a clearing leg still gets a partition, and nothing appears
        // twice. Dropping either would silently fail to settle part of the cycle.
        assertThat(new HashSet<>(plan.currencies())).isEqualTo(touched);
        assertThat(plan.partitions()).hasSize(touched.size());
    }

    // --- the honest consequence of P2 ---------------------------------------------------------

    @Property
    void anOrdinaryCycleNeverNeedsExternalFunding(
            @ForAll("singleCurrencyCycles") List<CycleObligation> cycle) {
        SettlementPlan plan = PLANNER.plan(cycle, TaxRules.none());

        assertThat(plan.requiresExternalFunding())
                .as("a cycle confined to currencies its members hold cannot need outside help")
                .isFalse();
        assertThat(plan.partitionsRequiringExternalFunding()).isEmpty();

        for (CurrencyPlan partition : plan.partitions()) {
            assertThat(partition.clearingPosition()).isEqualTo(Money.zero(partition.currency()));
            assertThat(partition.clearingTransfers()).isEmpty();
        }
    }

    // --- the clearing participant settles like any other --------------------------------------

    @Property
    void transfersSettleEveryParticipantIncludingTheClearingAccount(
            @ForAll("cycles") List<CycleObligation> cycle) {
        SettlementPlan plan = PLANNER.plan(cycle, TaxRules.none());

        for (CurrencyPlan partition : plan.partitions()) {
            Map<UUID, Long> balances = new HashMap<>();
            for (NetPosition position : partition.netting().positions()) {
                balances.put(position.userId(), position.net().amountMinor());
            }

            for (TransferInstruction transfer : partition.netting().transfers()) {
                balances.merge(transfer.fromUserId(), transfer.amount().amountMinor(), Long::sum);
                balances.merge(transfer.toUserId(), -transfer.amount().amountMinor(), Long::sum);
            }

            for (Map.Entry<UUID, Long> balance : balances.entrySet()) {
                // Every participant ends flat, the clearing account included. If its legs were not
                // netted, this is where it would show.
                assertThat(balance.getValue())
                        .as("%s ends flat in %s", balance.getKey(), partition.currency().code())
                        .isZero();
            }
        }
    }

    // --- reproducibility ----------------------------------------------------------------------

    @Property
    void theSameCycleInAnyOrderProducesTheSamePlan(
            @ForAll("cycles") List<CycleObligation> cycle) {
        List<CycleObligation> reversed = cycle.reversed();

        assertThat(PLANNER.plan(reversed, TaxRules.none()))
                .as("a plan must not depend on the order obligations arrived in")
                .isEqualTo(PLANNER.plan(cycle, TaxRules.none()));
    }
}
