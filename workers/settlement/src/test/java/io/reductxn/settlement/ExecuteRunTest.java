package io.reductxn.settlement;

import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.TransferStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Execution: the first thing in reductxn that moves money (RULES S1, S2, S3, S4).
 *
 * <p>The rail is a {@link StubRail} in this module's test sources, and every test here asks one of two
 * questions: <em>did nothing move when it should not have</em>, and <em>does the record say what moved</em>. The
 * first is why the gates are checked before the loop rather than inside it, and the second is why the assertions
 * read the stored rows as well as the returned report - the report is derived from those rows, and a test that
 * only read the report would pass if both were wrong in the same way.
 *
 * <p>The stub is {@code @Primary} rather than replacing the bean: the module's own rail is still wired, which is
 * part of what the context proves (a deployment gets a working rail by default), and this one takes precedence
 * for the calls these tests make.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class})
class ExecuteRunTest extends WorkerDatabaseTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);

    @Autowired
    private StubRail rail;

    /**
     * A rail that has been told nothing and asked nothing.
     *
     * <p>It is one bean for the whole context, which is what makes "the rail saw this once" a meaningful
     * assertion - and it means the test that restricts what the rail settles would otherwise restrict it for
     * every test after it.
     */
    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    @Test
    void aRunWithoutQuorumDoesNotExecute() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        assertThat(refusalCode(executeRun.execute(runId))).isEqualTo("settlement.quorum_not_met");
        assertThat(rail.submissions())
                .as("RULE S1 is worth nothing if the check happens after the first transfer: the rail must not "
                        + "have heard of this run at all")
                .isEmpty();
        assertThat(storedRunStatus(runId)).isEqualTo("awaiting_approval");
        assertThat(storedTransferStatuses(runId)).containsOnly("pending");
    }

    @Test
    void aRunBlockedOnFundingDoesNotExecute() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        DomainError refusal = refusalOf(executeRun.execute(run.run().id()));

        assertThat(refusal.code()).isEqualTo("settlement.fx_unfunded");
        assertThat(refusal).isInstanceOf(RunRefusals.FxUnfunded.class);
        assertThat(((RunRefusals.FxUnfunded) refusal).outstanding())
                .as("the refusal carries the exact amounts, per currency, because a group told it is short "
                        + "'some currency' cannot act and a group told the figure can")
                .extracting(Money::currency, Money::amountMinor)
                .containsExactly(tuple(INR, 12_500L), tuple(JPY, 224L));
        assertThat(rail.submissions()).isEmpty();
        assertThat(storedRunStatus(run.run().id())).isEqualTo("awaiting_fx_funding");
    }

    @Test
    void anApprovedAndFundedRunExecutesAndConfirmsEveryTransfer() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        ComputedRun executed = ok(executeRun.execute(run.run().id()));

        assertThat(executed.run().status()).isEqualTo(RunStatus.DONE);
        assertThat(executed.transfers()).isNotEmpty();
        assertThat(executed.transfers())
                .as("every transfer confirmed, and each carries what the rail said - the transfer's state and the "
                        + "rail's own words are two different facts and both are stored")
                .allMatch(transfer -> transfer.status() == TransferStatus.CONFIRMED)
                .allMatch(transfer -> transfer.railDetail() != null);

        for (PlannedTransfer transfer : executed.transfers()) {
            assertThat(rail.submissionsOf(transfer.railRef()))
                    .as("moved once, under the reference derived from its own coordinates (RULE S2)")
                    .isEqualTo(1);
        }

        assertThat(rail.submissions()).hasSize(executed.transfers().size());
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(storedTransferStatuses(run.run().id())).containsOnly("confirmed");
    }

    @Test
    void replayingAnExecutedRunAsksTheRailForNothingNew() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        ComputedRun first = ok(executeRun.execute(run.run().id()));
        int submissionsAfterTheFirstPass = rail.submissions().size();

        ComputedRun replay = ok(executeRun.execute(run.run().id()));

        assertThat(replay.run().status())
                .as("RULE S2: a caller that retried after a timeout gets the same answer, and executing a "
                        + "finished run is a read rather than an error")
                .isEqualTo(RunStatus.DONE);
        assertThat(replay.transfers()).isEqualTo(first.transfers());
        assertThat(rail.submissions())
                .as("nothing was offered again: a resolved transfer is skipped, so a replay cannot move money "
                        + "twice - and this asserts it at the rail rather than only in our own rows")
                .hasSize(submissionsAfterTheFirstPass);
        assertThat(rail.polls()).isZero();
    }

    @Test
    void aRefusedTransferLeavesTheRunFailedAndTheOthersConfirmed() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer doomed = run.transfers().get(1);
        rail.scripting(doomed.railRef(), StubRail.Behaviour.REFUSE);

        ComputedRun executed = ok(executeRun.execute(run.run().id()));

        assertThat(executed.run().status())
                .as("RULE S3: reduction partially applied. The run says so rather than reporting a settlement "
                        + "that did not happen")
                .isEqualTo(RunStatus.FAILED);
        assertThat(executed.transfers())
                .filteredOn(transfer -> transfer.id().equals(doomed.id()))
                .singleElement()
                .satisfies(transfer -> {
                    assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
                    assertThat(transfer.railDetail())
                            .as("the rail's own reason, stored as it was given")
                            .isEqualTo("insufficient funds");
                });
        assertThat(storedTransferStatuses(run.run().id()))
                .as("the confirmed transfers stayed confirmed: there is no compensating transaction, because "
                        + "money that has moved cannot be unmoved")
                .contains("confirmed", "failed");
        assertThat(rail.submissionsOf(doomed.railRef())).isEqualTo(1);
    }

    @Test
    void aRailThatHasNotAnsweredLeavesTheRunExecutingUntilItDoes() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer slow = run.transfers().get(0);
        rail.scripting(slow.railRef(), StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);

        ComputedRun inFlight = ok(executeRun.execute(run.run().id()));

        assertThat(inFlight.run().status())
                .as("a transfer the rail has accepted and not confirmed is in flight, which is not done and not "
                        + "failed - and `executing` is where the run stays until the rail answers")
                .isEqualTo(RunStatus.EXECUTING);
        assertThat(inFlight.transfers())
                .filteredOn(transfer -> transfer.id().equals(slow.id()))
                .singleElement()
                .satisfies(transfer -> assertThat(transfer.status()).isEqualTo(TransferStatus.SUBMITTED));

        rail.confirm(slow.railRef());
        ComputedRun afterTheRailAnswered = ok(executeRun.execute(run.run().id()));

        assertThat(rail.polls())
                .as("the second pass asked rather than submitted again: the question a submitted transfer poses "
                        + "is whether the money moved, and only the rail knows")
                .isEqualTo(1);
        assertThat(afterTheRailAnswered.run().status()).isEqualTo(RunStatus.DONE);
        assertThat(afterTheRailAnswered.transfers()).allMatch(transfer -> transfer.status() == TransferStatus.CONFIRMED);
    }

    @Test
    void aRailThatCannotBeReachedLeavesTheTransferAuthorizedForAnotherAttempt() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        PlannedTransfer unreachable = run.transfers().get(0);
        rail.scripting(unreachable.railRef(), StubRail.Behaviour.UNREACHABLE);

        ComputedRun executed = ok(executeRun.execute(run.run().id()));

        assertThat(executed.transfers())
                .filteredOn(transfer -> transfer.id().equals(unreachable.id()))
                .singleElement()
                .satisfies(transfer -> {
                    assertThat(transfer.status())
                            .as("the call did not complete, so the rail never took it: `authorized` rather than "
                                    + "`failed` is the difference between a transfer that was refused and one that "
                                    + "was never offered")
                            .isEqualTo(TransferStatus.AUTHORIZED);
                    assertThat(transfer.railDetail()).contains("rails.unreachable");
                });
        assertThat(executed.run().status())
                .as("nothing failed, so the run is not failed - it is waiting, and re-running the pass is what "
                        + "retries. Only retryable failures are retried, and this is the shape of one")
                .isEqualTo(RunStatus.EXECUTING);
    }

    @Test
    void aRailThatCannotSettleACurrencyIsRefusedBeforeAnythingMoves() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        rail.supporting(INR);

        DomainError refusal = refusalOf(executeRun.execute(run.run().id()));

        assertThat(refusal.code()).isEqualTo("settlement.currency_not_supported");
        assertThat(refusal.message()).contains("JPY");
        assertThat(rail.submissions())
                .as("checked before the loop, not inside it: discovering this on the second transfer would leave "
                        + "the first half of the group's money moved and the second half not")
                .isEmpty();
    }

    @Test
    void aRejectedRunNeverExecutes() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        decided(runId, BORIS, RunDecision.Decision.REJECT);

        assertThat(refusalCode(executeRun.execute(runId))).isEqualTo("settlement.run_rejected");
        assertThat(rail.submissions()).isEmpty();
    }

    @Test
    void aCycleThatNetsToNothingIsDoneWithoutMovingAnything() {
        // Two obligations that cancel: ALICE owes BORIS 100 and BORIS owes ALICE 100, so both positions are zero
        // and the engine emits no transfer at all.
        obligation(ALICE, BORIS, 100L, "INR", 2);
        obligation(BORIS, ALICE, 100L, "INR", 2);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        assertThat(run.transfers()).isEmpty();

        ComputedRun executed = ok(executeRun.execute(run.run().id()));

        assertThat(executed.run().status())
                .as("a group must be able to close a cycle in which nothing is owed: refusing to finish it "
                        + "would leave the cycle open forever")
                .isEqualTo(RunStatus.DONE);
        assertThat(rail.submissions()).isEmpty();
    }

    @Test
    void executingAnUnknownRunIsRefused() {
        assertThat(refusalCode(executeRun.execute(UUID.randomUUID()))).isEqualTo("settlement.run_not_found");
    }

    private List<String> storedTransferStatuses(UUID runId) {
        return jdbc.queryForList(
                "SELECT status FROM settlement_transfers WHERE run_id = ? ORDER BY rail_ref", String.class, runId);
    }
}
