package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Currency arriving from outside, and the gate it opens (RULE S4).
 *
 * <p>These are the tests that make {@code awaiting_fx_funding} a state rather than a dead end. Before the funding
 * path existed, a cross-currency run could reach it and nothing could move it on; the spec described a group
 * being asked for currency and the product had no way to record that the ask was met.
 *
 * <p>The fixture is the one {@code ComputeRunTest} uses for the cross-currency case: 12,500 INR owed by ALICE to
 * BORIS and settled as 224 JPY, which leaves the clearing account owing 12,500 INR and 224 JPY to the group's own
 * position - two currencies, both requiring something from outside.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class RecordFxFundingTest extends WorkerDatabaseTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);

    @Test
    void aCrossCurrencyRunStaysBlockedUntilEveryCurrencyIsCovered() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        assertThat(run.run().status()).isEqualTo(RunStatus.AWAITING_FX_FUNDING);
        assertThat(run.funding().required())
                .as("the requirement per currency, from the plan's own clearing positions")
                .extracting(Money::currency, Money::amountMinor)
                .containsExactly(tuple(INR, 12_500L), tuple(JPY, 224L));
        assertThat(run.funding().satisfied()).isFalse();

        ComputedRun afterYen = fund(runId, JPY, 224L, "external-transfer-1");

        assertThat(afterYen.run().status())
                .as("one of two currencies covered is one currency short, and the run says which")
                .isEqualTo(RunStatus.AWAITING_FX_FUNDING);
        assertThat(afterYen.funding().outstandingIn(INR).amountMinor()).isEqualTo(12_500L);
        assertThat(afterYen.funding().outstandingIn(JPY).isZero()).isTrue();

        ComputedRun afterBoth = fund(runId, INR, 12_500L, "external-transfer-2");

        assertThat(afterBoth.funding().satisfied()).isTrue();
        assertThat(afterBoth.run().status())
                .as("the gate opens onto approval, which is what the run was waiting for all along")
                .isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void partialFundingIsRecordedAndTheShortfallIsReported() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        ComputedRun afterPartial = fund(runId, JPY, 200L, "external-transfer-1");

        assertThat(afterPartial.funding().receivedIn(JPY).amountMinor())
                .as("the part that arrived is recorded rather than ignored, and the run stays blocked: a group "
                        + "that has brought in some of the currency has not covered it")
                .isEqualTo(200L);
        assertThat(afterPartial.funding().outstandingIn(JPY).amountMinor()).isEqualTo(24L);
        assertThat(afterPartial.run().status()).isEqualTo(RunStatus.AWAITING_FX_FUNDING);
    }

    @Test
    void fundingACurrencyWithNoRequirementIsRefused() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        Result<ComputedRun> refused =
                recordFxFunding.fund(new FundingRequest(runId, INR, Money.of(1_000L, INR), "external-transfer-1"));

        assertThat(refusalCode(refused))
                .as("an ordinary cycle needs nothing from outside, so a funding is a row asserting that a gap "
                        + "was closed when there was no gap")
                .isEqualTo("settlement.funding_not_required");
        assertThat(countIn("settlement_fx_fundings")).isZero();
    }

    @Test
    void fundingMoreThanTheRequirementIsRecordedRatherThanRefused() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        fund(runId, JPY, 224L, "external-transfer-1");
        ComputedRun afterTheExtra = fund(runId, JPY, 1L, "external-transfer-2");

        assertThat(afterTheExtra.funding().receivedIn(JPY).amountMinor())
                .as("the extra yen did arrive, so it is recorded: the gate asks whether each currency is covered, "
                        + "not whether it was covered exactly, and refusing the surplus would leave money a group "
                        + "believes it moved out of the only place that knows about it")
                .isEqualTo(225L);
        assertThat(afterTheExtra.funding().outstandingIn(JPY))
                .as("the surplus covers the yen requirement and stops there - it is not carried anywhere, so the "
                        + "run is still waiting for rupees")
                .isEqualTo(Money.zero(JPY));
        assertThat(afterTheExtra.funding().satisfied()).isFalse();
        assertThat(afterTheExtra.run().status()).isEqualTo(RunStatus.AWAITING_FX_FUNDING);
        assertThat(afterTheExtra.funding().required())
                .as("and the requirement is unchanged: a surplus in one currency never reduces what another needs")
                .extracting(Money::amountMinor)
                .containsExactly(12_500L, 224L);
    }

    @Test
    void fundingACurrencyTheRunDoesNotRequireIsRefused() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        Currency usd = Currency.of("USD", 2);

        assertThat(refusalCode(recordFxFunding.fund(
                new FundingRequest(runId, usd, Money.of(1_000L, usd), "external-transfer-1"))))
                .as("the plan asked for rupees and yen, so a dollar arrival would be a row saying a gap was "
                        + "closed while naming a gap that was never open")
                .isEqualTo("settlement.funding_not_required");
        assertThat(countIn("settlement_fx_fundings")).isZero();
    }

    @Test
    void aFundingThatIsNotAPaymentIsRefused() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        assertThat(refusalCode(recordFxFunding.fund(
                new FundingRequest(runId, JPY, Money.zero(JPY), "external-transfer-1"))))
                .as("the requirement has to be outstanding for this refusal to be about the amount rather than "
                        + "the currency, and JPY is outstanding here")
                .isEqualTo("settlement.funding_not_positive");
    }

    @Test
    void aRetriedFundingRecordsOneArrival() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        // Half the requirement, so the retry is not refused for having nothing left to cover - which is the
        // ordinary retry: the caller timed out and does not know whether the first call was recorded.
        fund(runId, JPY, 150L, "external-transfer-1");
        ComputedRun afterTheRetry = fund(runId, JPY, 150L, "external-transfer-1");

        assertThat(countIn("settlement_fx_fundings"))
                .as("RULE S2's idempotency for the funding path: the reference is the key, so the same arrival "
                        + "cannot be counted twice and a timeout cannot double the group's credit")
                .isEqualTo(1);
        assertThat(afterTheRetry.funding().receivedIn(JPY).amountMinor()).isEqualTo(150L);
        assertThat(afterTheRetry.funding().outstandingIn(JPY).amountMinor()).isEqualTo(74L);
    }

    @Test
    void fundingARunThatHasMovedOnIsRefused() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        jdbc.update("UPDATE settlement_runs SET status = 'done' WHERE id = ?", runId);

        assertThat(refusalCode(recordFxFunding.fund(
                new FundingRequest(runId, JPY, Money.of(224L, JPY), "external-transfer-1"))))
                .as("there is nothing for the money to unblock")
                .isEqualTo("settlement.not_waiting_on_the_group");
    }

    @Test
    void anUnknownRunIsRefused() {
        assertThat(refusalCode(recordFxFunding.fund(
                new FundingRequest(UUID.randomUUID(), JPY, Money.of(224L, JPY), "external-transfer-1"))))
                .isEqualTo("settlement.run_not_found");
    }

    /** A funding that had to succeed. */
    private ComputedRun fund(UUID runId, Currency currency, long amountMinor, String fundingRef) {
        return ok(recordFxFunding.fund(
                new FundingRequest(runId, currency, Money.of(amountMinor, currency), fundingRef)));
    }
}
