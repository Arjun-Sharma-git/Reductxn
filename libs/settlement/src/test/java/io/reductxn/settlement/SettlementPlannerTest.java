package io.reductxn.settlement;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.TaxRule;
import io.reductxn.netting.api.TaxRules;
import io.reductxn.netting.api.TransferInstruction;
import io.reductxn.settlement.api.CrossCurrencyObligation;
import io.reductxn.settlement.api.CurrencyPlan;
import io.reductxn.settlement.api.CycleObligation;
import io.reductxn.settlement.api.FxClearing;
import io.reductxn.settlement.api.SettlementPlan;
import io.reductxn.settlement.api.SettlementPlanner;
import io.reductxn.settlement.api.SettlementPlans;
import io.reductxn.settlement.api.SingleCurrencyObligation;
import io.reductxn.settlement.api.TaxRuleResolver;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The partitioner, checked against ADR 0012's hand-computed worked example.
 *
 * <p>The numbers in the first test are the ones the ADR states before any of this code existed. That
 * ordering matters: a test written afterwards can only confirm the code agrees with itself.
 */
class SettlementPlannerTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency USD = Currency.of("USD", 2);

    private static final UUID ALICE = new UUID(0L, 1L);
    private static final UUID BORIS = new UUID(0L, 2L);
    private static final UUID CAROL = new UUID(0L, 3L);
    private static final UUID DAVE = new UUID(0L, 4L);
    private static final UUID ERIN = new UUID(0L, 5L);

    private static final Instant QUOTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T10:01:00Z");

    private static final SettlementPlanner PLANNER = SettlementPlans.perCurrency();

    /** 1 JPY = 0.55904 INR, so 50,000 JPY is exactly 27,952.00 INR. The ADR's own example. */
    private static FxQuote fiftyThousandYenInRupees() {
        FxRate rate = FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "test");
        return FxQuote.lock(new UUID(0L, 99L), Money.of(50_000L, JPY), rate,
                QUOTED_AT.plusSeconds(30), false);
    }

    private static SettlementPlan planOf(CycleObligation... obligations) {
        return PLANNER.plan(List.of(obligations), TaxRules.none());
    }

    private static CurrencyPlan partition(SettlementPlan plan, Currency currency) {
        return plan.partitionFor(currency).orElseThrow();
    }

    private static long netOf(CurrencyPlan plan, UUID participant) {
        return plan.netting().positions().stream()
                .filter(position -> position.userId().equals(participant))
                .map(NetPosition::net)
                .mapToLong(Money::amountMinor)
                .findFirst()
                .orElseThrow();
    }

    // --- the ADR 0012 worked example -----------------------------------------------------------

    @Test
    void reproducesTheAdr0012WorkedExample() {
        // "Alice owes JPY, Boris is owed INR. Netting leaves fx_clearing at +50,000 JPY and
        //  -27,952 INR." - ADR 0012, decision point 5.
        SettlementPlan plan = planOf(new CrossCurrencyObligation(ALICE, BORIS, fiftyThousandYenInRupees()));

        assertThat(plan.currencies()).containsExactly(INR, JPY);

        CurrencyPlan yen = partition(plan, JPY);
        CurrencyPlan rupees = partition(plan, INR);

        assertThat(yen.clearingPosition().amountMinor()).isEqualTo(50_000L);
        assertThat(yen.clearingPosition().currency()).isEqualTo(JPY);
        assertThat(rupees.clearingPosition().amountMinor()).isEqualTo(-2_795_200L);
        assertThat(rupees.clearingPosition().currency()).isEqualTo(INR);

        assertThat(netOf(yen, ALICE)).isEqualTo(-50_000L);
        assertThat(netOf(rupees, BORIS)).isEqualTo(2_795_200L);
    }

    @Test
    void reportsTheExternalRequirementPerCurrency() {
        SettlementPlan plan = planOf(new CrossCurrencyObligation(ALICE, BORIS, fiftyThousandYenInRupees()));

        assertThat(plan.requiresExternalFunding()).isTrue();
        assertThat(plan.partitionsRequiringExternalFunding()).hasSize(2);

        // The group must obtain rupees and dispose of yen. Reported per currency, never summed.
        assertThat(partition(plan, JPY).externalRequirement().amountMinor()).isEqualTo(50_000L);
        assertThat(partition(plan, INR).externalRequirement().amountMinor()).isEqualTo(2_795_200L);
    }

    @Test
    void treatsTheCrossCurrencyLegAsClearingNotAsAMemberDebt() {
        SettlementPlan plan = planOf(new CrossCurrencyObligation(ALICE, BORIS, fiftyThousandYenInRupees()));

        // Boris is never a participant in the yen book, and Alice is never one in the rupee book.
        // Presenting the clearing legs as member transfers would show the group's FX exposure as a
        // member's debt.
        CurrencyPlan yen = partition(plan, JPY);
        CurrencyPlan rupees = partition(plan, INR);

        assertThat(yen.memberTransfers()).isEmpty();
        assertThat(rupees.memberTransfers()).isEmpty();
        assertThat(yen.clearingTransfers()).hasSize(1);
        assertThat(rupees.clearingTransfers()).hasSize(1);

        TransferInstruction rupeeLeg = rupees.clearingTransfers().getFirst();
        assertThat(rupeeLeg.fromUserId()).isEqualTo(FxClearing.ACCOUNT_ID);
        assertThat(rupeeLeg.toUserId()).isEqualTo(BORIS);
        assertThat(rupeeLeg.amount().amountMinor()).isEqualTo(2_795_200L);
    }

    // --- ordinary cycles ----------------------------------------------------------------------

    @Test
    void leavesNoExternalRequirementForAnOrdinaryCycle() {
        SettlementPlan plan = planOf(
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(1_000_000L, INR)),
                new SingleCurrencyObligation(CAROL, BORIS, Money.of(400_000L, INR)));

        assertThat(plan.currencies()).containsExactly(INR);

        CurrencyPlan rupees = partition(plan, INR);
        assertThat(rupees.clearingPosition()).isEqualTo(Money.zero(INR));
        assertThat(rupees.requiresExternalFunding()).isFalse();
        assertThat(plan.requiresExternalFunding()).isFalse();
        assertThat(rupees.clearingTransfers()).isEmpty();
        assertThat(rupees.memberTransfers()).hasSize(2);
    }

    @Test
    void keepsMemberPositionsSeparateFromTheInternalAccount() {
        SettlementPlan plan = planOf(
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(1_000_000L, INR)),
                new SingleCurrencyObligation(CAROL, BORIS, Money.of(400_000L, INR)));

        CurrencyPlan rupees = partition(plan, INR);

        assertThat(rupees.memberPositions()).hasSize(3);
        assertThat(rupees.memberPositions())
                .noneMatch(position -> FxClearing.isClearing(position.userId()));
    }

    @Test
    void plansAnEmptyCycleAsEmpty() {
        assertThat(planOf().isEmpty()).isTrue();
        assertThat(planOf().requiresExternalFunding()).isFalse();
    }

    // --- determinism and order independence ---------------------------------------------------

    @Test
    void producesTheSamePlanRegardlessOfInputOrder() {
        CycleObligation first = new SingleCurrencyObligation(ALICE, BORIS, Money.of(1_000_000L, INR));
        CycleObligation second = new SingleCurrencyObligation(CAROL, BORIS, Money.of(400_000L, INR));
        CycleObligation third = new CrossCurrencyObligation(ALICE, CAROL, fiftyThousandYenInRupees());

        SettlementPlan forwards = PLANNER.plan(List.of(first, second, third), TaxRules.none());
        SettlementPlan backwards = PLANNER.plan(List.of(third, second, first), TaxRules.none());

        // A preview must not depend on the order rows came back from a database. The engine sorts
        // positions and transfers internally, and partitions are ordered by currency code.
        assertThat(backwards).isEqualTo(forwards);
    }

    @Test
    void ordersPartitionsByCurrencyCode() {
        // USD and JPY are supplied before INR, and the plan still reports them in code order.
        SettlementPlan plan = planOf(
                new CrossCurrencyObligation(CAROL, ALICE, fiftyThousandYenInRupees()),
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(500L, USD)));

        assertThat(plan.currencies()).containsExactly(INR, JPY, USD);
    }

    @Test
    void normalisesTheOrderSoEvenTheDirectPlanIsReproducible() {
        // The engine's direct plan emits one transfer per obligation "in the order they were recorded"
        // - that is asserted as intended behaviour in libs/netting. So its output does depend on the
        // order it is handed, and this is the cycle that selects that plan: three obligations over
        // five members, where netting would invent a fourth transfer and cost more (ADR 0011).
        List<CycleObligation> asRecorded = List.of(
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(300L, INR)),
                new SingleCurrencyObligation(ALICE, CAROL, Money.of(100L, INR)),
                new SingleCurrencyObligation(DAVE, ERIN, Money.of(200L, INR)));
        TaxRule flatPerTransfer = TaxRules.flatPerTransfer(Money.of(50L, INR));

        SettlementPlan forwards = PLANNER.plan(asRecorded, flatPerTransfer);
        SettlementPlan backwards = PLANNER.plan(asRecorded.reversed(), flatPerTransfer);

        // If this assertion ever fails, the test has stopped covering the case it exists for.
        assertThat(partition(forwards, INR).netting().algorithm())
                .isEqualTo(NettingResult.Algorithm.PAIRWISE);
        assertThat(backwards)
                .as("a preview must not depend on row order, whichever tier the engine picks")
                .isEqualTo(forwards);
    }

    // --- per-currency tax ---------------------------------------------------------------------

    @Test
    void resolvesTheTaxRulePerCurrencyPartition() {
        CycleObligation cross = new CrossCurrencyObligation(ALICE, BORIS, fiftyThousandYenInRupees());
        CycleObligation single = new SingleCurrencyObligation(CAROL, BORIS, Money.of(1_000_000L, INR));

        TaxRuleResolver perCurrency = currency ->
                currency.equals(JPY)
                        ? TaxRules.flatPerTransfer(Money.of(20_000L, JPY))   // 200 JPY per transfer
                        : TaxRules.flatPerTransfer(Money.of(10_000L, INR));  // 100 INR per transfer

        SettlementPlan plan = PLANNER.plan(List.of(cross, single), perCurrency);

        // One transfer in the yen book, so 200 JPY. Two in the rupee book, so 200 INR. Both land on
        // 20,000 minor units, but using either rule for both currencies would give 10,000 and 40,000,
        // so this genuinely fails if the rule is not resolved per partition.
        assertThat(partition(plan, JPY).netting().tax().amountMinor()).isEqualTo(20_000L);
        assertThat(partition(plan, INR).netting().tax().amountMinor()).isEqualTo(20_000L);
    }

    // --- refused input ------------------------------------------------------------------------

    @Test
    void refusesTheClearingAccountAsAPartyToAnObligation() {
        assertThatThrownBy(() -> new SingleCurrencyObligation(
                FxClearing.ACCOUNT_ID, BORIS, Money.of(100L, INR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fx_clearing is not a member");

        assertThatThrownBy(() -> new CrossCurrencyObligation(
                ALICE, FxClearing.ACCOUNT_ID, fiftyThousandYenInRupees()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fx_clearing is not a member");
    }

    @Test
    void refusesANegativeAmount() {
        assertThatThrownBy(() -> new SingleCurrencyObligation(ALICE, BORIS, Money.of(-100L, INR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("magnitude");
    }

    @Test
    void refusesAnObligationFromAMemberToThemselves() {
        assertThatThrownBy(() -> new SingleCurrencyObligation(ALICE, ALICE, Money.of(100L, INR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different participants");
    }

    @Test
    void refusesACycleContainingOneCurrencyCodeWithTwoExponents() {
        // Not hypothetical: Currency has no registry, so both are constructible. Ordering partitions
        // by code alone would silently merge them and corrupt every amount in the merged partition.
        // Rejecting loudly is the only safe answer, because one currency cannot have two exponents.
        CycleObligation asRupees =
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(100L, Currency.of("INR", 2)));
        CycleObligation asYenLike =
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(100L, Currency.of("INR", 0)));

        assertThatThrownBy(() -> PLANNER.plan(List.of(asRupees, asYenLike), TaxRules.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sorted by currency code and unique");
    }
}
