package io.reductxn.rails.api;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.rails.api.PaymentRail.RailReference;
import io.reductxn.rails.api.PaymentRail.RailStatus;
import io.reductxn.rails.api.PaymentRail.TransferInstruction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mock rail, through the only door a caller has: {@link Rails#mock()}.
 *
 * <p>It lives in this package rather than beside the implementation because a test that imported
 * {@code MockRail} directly would be testing something no caller can obtain - the class is internal (RULE B10) and
 * the factory is the whole of its public surface. What is verified here is what a deployment gets.
 *
 * <p><strong>Why this deserves tests at all.</strong> It is a rail, and it is the only rail this build has: a
 * deployment that executes a run moves money through it, and the properties the run depends on are the rail's.
 * RULE S2's "re-submitting the same transfer is a no-op returning the existing rail reference" is implemented
 * here and nowhere else, and a mock with no test is a mock whose idempotency is an assumption.
 */
class MockRailTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency KWD = Currency.of("KWD", 3);

    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ALICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BORIS = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void itSettlesTheCurrenciesTheCatalogueSeedsAndSaysSo() {
        PaymentRail rail = Rails.mock();

        assertThat(rail.name()).isEqualTo("mock");
        assertThat(rail.supportedCurrencies())
                .as("a rail that declared nothing would be a deployment error by its own contract, and a rail "
                        + "that declared everything would make an unsupported currency untestable")
                .containsExactly(INR, JPY, Currency.of("USD", 2));
    }

    @Test
    void itConfirmsWhatItAcceptsAndReturnsTheCallersReference() {
        PaymentRail rail = Rails.mock();
        TransferInstruction instruction = instruction("reductxn/run/INR/alice/boris/100", 10_000L, INR);

        Result<RailReference> answer = rail.submit(instruction);

        assertThat(answer.isOk()).isTrue();
        assertThat(answer.orElseThrow().railRef())
                .as("the caller's reference, not the rail's own id: it is the whole of what makes a second "
                        + "submission recognisable as the same transfer")
                .isEqualTo(instruction.railRef());
        assertThat(answer.orElseThrow().status().state()).isEqualTo(RailStatus.State.CONFIRMED);
    }

    @Test
    void resubmittingReturnsWhatItAlreadyHasRatherThanMovingAgain() {
        PaymentRail rail = Rails.mock();
        TransferInstruction instruction = instruction("reductxn/run/INR/alice/boris/100", 10_000L, INR);

        RailStatus first = rail.submit(instruction).orElseThrow().status();
        RailStatus second = rail.submit(instruction).orElseThrow().status();

        assertThat(second)
                .as("RULE S2 at the rail: the second submission returned the state the rail already had rather "
                        + "than a fresh one, which is what 'a no-op returning the existing rail reference' means "
                        + "from the caller's side")
                .isEqualTo(first);
        assertThat(rail.status(instruction.railRef()).orElseThrow().state())
                .isEqualTo(RailStatus.State.CONFIRMED);
    }

    @Test
    void aReferenceItHasNeverSeenIsAnsweredWithATerminalFailureRatherThanSilence() {
        PaymentRail rail = Rails.mock();

        Result<RailStatus> answer = rail.status("reductxn/run/INR/nobody/owes/nothing");

        assertThat(answer.isOk())
                .as("the rail answered. That it has no record of the reference is the answer, and a port with no "
                        + "way to express silence should not pretend to be one")
                .isTrue();
        assertThat(answer.orElseThrow().state()).isEqualTo(RailStatus.State.FAILED);
        assertThat(answer.orElseThrow().detail()).contains("no record");
    }

    @Test
    void aCurrencyItCannotSettleIsRefusedTerminallyAndNamed() {
        PaymentRail rail = Rails.mock();

        RailStatus status = rail
                .submit(instruction("reductxn/run/KWD/alice/boris/100", 10_000L, KWD))
                .orElseThrow()
                .status();

        assertThat(status.state())
                .as("a terminal failure rather than an exception: the rail answered, and its answer is that it "
                        + "cannot do this. Accepting it would be a rail reporting a movement it cannot make")
                .isEqualTo(RailStatus.State.FAILED);
        assertThat(status.detail()).contains("KWD");
    }

    private static TransferInstruction instruction(String railRef, long amountMinor, Currency currency) {
        return new TransferInstruction(RUN, railRef, ALICE, BORIS, Money.of(amountMinor, currency));
    }
}
