package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.PartitionOutcome;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Computing a run: the second deployable, against a real database.
 *
 * <p>What these tests verify is not that the worker called the planner. It is that the plan is <em>recorded</em>
 * - partitions, positions and transfers - and that the rows then stand up to the checks an auditor or an offline
 * job would apply: per-currency zero-sum read back from the rows (N1), every participant zeroed by applying the
 * plan's own transfers (N2), and the clearing account's residual, which is what RULE S4 gates on.
 *
 * <p>The status is asserted as carefully as the figures, because it is the one decision this module makes:
 * {@code awaiting_approval} for a cycle the group can settle, {@code awaiting_fx_funding} for one it cannot.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class ComputeRunTest extends WorkerDatabaseTest {

    @Test
    void aSingleCurrencyCycleIsPlannedAndWaitsForApproval() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        obligation(BORIS, CAROL, 4_000L, "INR", 2);
        obligation(CAROL, ALICE, 1_000L, "INR", 2);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        assertThat(run.run().status())
                .as("the group can settle this in the currency it owes, so the run waits for approval - RULE S1's "
                        + "quorum moves it on, and nothing submits yet")
                .isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(run.requiresExternalFunding()).isFalse();

        assertThat(run.partitions()).hasSize(1);
        PartitionOutcome inr = run.partitions().get(0);

        assertThat(inr.currency().code()).isEqualTo("INR");
        assertThat(inr.gross().amountMinor())
                .as("every obligation's amount, before netting: 10,000 + 4,000 + 1,000")
                .isEqualTo(15_000L);
        assertThat(inr.clearingPosition().isZero())
                .as("L8: an ordinary cycle leaves fx_clearing at zero in the currency it settles in - the "
                        + "case the external-funding rule exists to distinguish from the one below")
                .isTrue();
        assertThat(inr.externalRequirement().isZero()).isTrue();
        assertThat(inr.memberTransferTotal().isPositive()).isTrue();
        assertThat(inr.clearingTransferCount()).isZero();
        assertThat(inr.memberTransferCount())
                .as("Tier 1's N-1 bound, for three members with non-zero balances")
                .isBetween(1, 2);

        assertThat(countIn("settlement_runs")).isEqualTo(1);
        assertThat(countIn("settlement_cycles")).isEqualTo(1);
        assertThat(countIn("settlement_partitions")).isEqualTo(1);
        assertThat(countIn("settlement_transfers")).isEqualTo(inr.memberTransferCount());

        assertThat(sumOfStoredPositions("INR"))
                .as("N1, measured from the rows: the plan's positions sum to zero within the currency. Read back "
                        + "rather than recomputed, because the property is about what was stored")
                .isZero();
    }

    @Test
    void computingTwiceReturnsTheSameRunAndWritesNoSecondPlan() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        obligation(BORIS, CAROL, 4_000L, "INR", 2);

        ComputedRun first = computed(new RunRequest(GROUP, AS_OF));
        int transfersAfterFirst = countIn("settlement_transfers");

        ComputedRun second = computed(new RunRequest(GROUP, AS_OF));

        assertThat(second.run().id())
                .as("a retry is the ordinary case: a caller that timed out does not know whether the run was "
                        + "opened, so the second attempt must be a read rather than a second plan")
                .isEqualTo(first.run().id());
        assertThat(second.partitions()).isEqualTo(first.partitions());
        assertThat(countIn("settlement_runs")).isEqualTo(1);
        assertThat(countIn("settlement_transfers")).isEqualTo(transfersAfterFirst);
    }

    @Test
    void nothingToSettleIsRefusedRatherThanRecordedAsAnEmptyRun() {
        voidedObligation(ALICE, BORIS, 10_000L, "INR", 2);

        Result<ComputedRun> result = computeRun.compute(new RunRequest(GROUP, AS_OF));

        assertThat(result.isFailure())
                .as("a voided obligation is not in the cycle, and a run over nothing would read as a completed "
                        + "settlement")
                .isTrue();
        assertThat(((Result.Failure<ComputedRun>) result).error().code())
                .isEqualTo(RunRefusals.NothingToSettle.CODE);

        assertThat(countIn("settlement_runs")).isZero();
        assertThat(countIn("settlement_cycles")).isZero();
        assertThat(countIn("settlement_transfers")).isZero();
    }

    private ComputedRun computed(RunRequest request) {
        Result<ComputedRun> result = computeRun.compute(request);
        assertThat(result.isOk()).as("expected a computed run, got %s", result).isTrue();
        return ((Result.Ok<ComputedRun>) result).value();
    }

    /** The sum of the stored net positions for a currency, read from the column rather than from the plan. */
    private long sumOfStoredPositions(String currencyCode) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(net_minor), 0) FROM net_positions WHERE currency_code = ?",
                Long.class, currencyCode);
        return sum == null ? 0L : sum;
    }

    @Test
    void aCrossCurrencyCycleWaitsForFundingAndReportsTheRequirementPerCurrency() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        assertThat(run.run().status())
                .as("L8: a non-zero fx_clearing position in any currency flags the run as needing external "
                        + "funding, and it is a real blocking state rather than a warning (RULE S4)")
                .isEqualTo(RunStatus.AWAITING_FX_FUNDING);

        assertThat(run.partitions()).hasSize(2);
        assertThat(run.currencies().stream().map(currency -> currency.code()).toList())
                .as("ordered by currency code, as the plan is: two readings of the same run must agree")
                .containsExactly("INR", "JPY");

        PartitionOutcome inr = run.partitions().get(0);
        PartitionOutcome jpy = run.partitions().get(1);

        // The debtor's side in the debt currency, and the clearing account as its counterparty: the creditor is
        // not being paid in rupees, so no member transfer exists in this partition at all.
        assertThat(inr.clearingPosition().amountMinor())
                .as("ALICE owes the clearing account what she borrowed")
                .isEqualTo(12_500L);
        assertThat(inr.externalRequirement().amountMinor()).isEqualTo(12_500L);
        assertThat(inr.clearingTransferCount()).isEqualTo(1);
        assertThat(inr.memberTransferCount()).isZero();
        assertThat(inr.memberTransferTotal().isZero()).isTrue();

        // And the creditor's side in the settlement currency, at the locked rate - 12,500 minor INR is 224 JPY,
        // because the quote said so and the ledger may not convert (RULE B19).
        assertThat(jpy.clearingPosition().amountMinor())
                .as("the clearing account owes BORIS the converted amount")
                .isEqualTo(-224L);
        assertThat(jpy.externalRequirement().amountMinor()).isEqualTo(224L);
        assertThat(jpy.clearingTransferCount()).isEqualTo(1);
        assertThat(jpy.memberTransferCount()).isZero();

        // S5's whole point: the requirement is a reported number, per currency, and never summed with another
        // currency's - adding rupees to yen is not addition.
        assertThat(run.externalRequirements())
                .as("the exact amount per currency, in plan order, and never a total")
                .extracting(amount -> amount.currency().code(), Money::amountMinor)
                .containsExactly(tuple("INR", 12_500L), tuple("JPY", 224L));

        assertThat(sumOfStoredPositions("INR")).isZero();
        assertThat(sumOfStoredPositions("JPY")).isZero();
    }

    @Test
    void applyingThePlansTransfersZeroesEveryPosition() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        obligation(BORIS, CAROL, 3_000L, "INR", 2);
        obligation(CAROL, ALICE, 1_000L, "INR", 2);

        computed(new RunRequest(GROUP, AS_OF));

        for (String currencyCode : new String[] {"INR", "JPY"}) {
            Map<String, Long> balances = storedBalances(currencyCode);

            assertThat(balances).as("the plan has positions in " + currencyCode).isNotEmpty();

            for (Object[] transfer : storedTransfers(currencyCode)) {
                String from = (String) transfer[0];
                String to = (String) transfer[1];
                long amount = (Long) transfer[2];

                balances.merge(from, amount, Long::sum);
                balances.merge(to, -amount, Long::sum);
            }

            assertThat(balances.values())
                    .as("N2 per currency, measured from the stored rows: applying the plan's own transfers "
                            + "zeroes every position in " + currencyCode + ". A plan that did not would leave a "
                            + "member owing something nobody would collect")
                    .allMatch(balance -> balance == 0L);
        }
    }

    /** Stored positions for a currency, as the raw numbers a verification job would read. */
    private Map<String, Long> storedBalances(String currencyCode) {
        Map<String, Long> balances = new HashMap<>();

        jdbc.query("SELECT participant_id::text, net_minor FROM net_positions WHERE currency_code = ?",
                row -> {
                    balances.put(row.getString(1), row.getLong(2));
                }, currencyCode);

        return balances;
    }

    /** Stored transfers for a currency, in the deterministic order the schema's index provides. */
    private List<Object[]> storedTransfers(String currencyCode) {
        return jdbc.query("""
                SELECT from_user_id::text, to_user_id::text, amount_minor FROM settlement_transfers
                WHERE currency_code = ? ORDER BY rail_ref
                """, (row, index) -> new Object[] {row.getString(1), row.getString(2), row.getLong(3)},
                currencyCode);
    }
}
