package io.reductxn.rails.api;

import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.rails.api.PaymentRail.RailRejected;
import io.reductxn.rails.api.PaymentRail.RailStatus;
import io.reductxn.rails.api.PaymentRail.RailUnreachable;
import io.reductxn.rails.api.PaymentRail.TransferInstruction;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The circuit breaker, through {@link Rails#withCircuitBreaker}.
 *
 * <p>No Spring, no rail protocol and no sleeping: the breaker's whole job is a decision about time, so the clock
 * is a field this test moves. That is also why the clock is a parameter of the factory rather than something the
 * breaker reads - a cooldown that can only be tested by waiting two minutes is a cooldown nobody tests.
 *
 * <p>The rail behind the breaker is a counter that answers what the test tells it to. Deliberately not
 * {@code MockRail}: what is being verified is the breaker's arithmetic, and a real rail's idempotency memory would
 * only make the count harder to see.
 */
class CircuitBreakerTest {

    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Duration COOLDOWN = Duration.ofMinutes(2);
    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final CountingRail rail = new CountingRail();

    @Test
    void callsGoThroughWhileTheRailIsAnswering() {
        PaymentRail guarded = guarded();

        for (int i = 0; i < 10; i++) {
            assertThat(guarded.submit(instruction()).isOk()).isTrue();
        }

        assertThat(rail.calls).isEqualTo(10);
    }

    @Test
    void theRailIsLeftAloneAfterConsecutiveFailures() {
        PaymentRail guarded = guarded();
        rail.failWith(new RailUnreachable("whatever", "the network is down"));

        for (int i = 0; i < 3; i++) {
            assertThat(guarded.submit(instruction()).isOk()).isFalse();
        }

        assertThat(rail.calls).as("the third failure is what opened it").isEqualTo(3);

        Result<?> refused = guarded.submit(instruction());

        assertThat(rail.calls)
                .as("an open breaker does not call the rail at all - that is the whole point: a failing rail "
                        + "costs something to fail against, and this is the only thing reductxn can do about it")
                .isEqualTo(3);
        assertThat(refused.isOk()).isFalse();
        assertThat(((Result.Failure<?>) refused).error())
                .as("answered in the port's own vocabulary rather than a third state a caller would have to "
                        + "branch on: from the caller's side, a rail it has stopped calling is indistinguishable "
                        + "from one that is not answering, and the consequences are identical")
                .isInstanceOf(RailUnreachable.class);
        assertThat(((PaymentRail.RailRefusal) ((Result.Failure<?>) refused).error()).retryable()).isTrue();
        assertThat(((Result.Failure<?>) refused).error().message()).contains("left alone until");
    }

    @Test
    void oneProbeIsAllowedAfterTheCooldownAndClosesIt() {
        PaymentRail guarded = guarded();
        rail.failWith(new RailUnreachable("whatever", "the network is down"));
        for (int i = 0; i < 3; i++) {
            guarded.submit(instruction());
        }

        clock.advance(COOLDOWN.minusSeconds(1));
        assertThat(guarded.submit(instruction()).isOk()).as("still inside the cooldown").isFalse();
        assertThat(rail.calls).isEqualTo(3);

        clock.advance(Duration.ofSeconds(2));
        rail.answerWith(RailStatus.State.CONFIRMED);

        assertThat(guarded.submit(instruction()).isOk())
                .as("the cooldown expired, so exactly one call is allowed through")
                .isTrue();
        assertThat(rail.calls).isEqualTo(4);

        for (int i = 0; i < 5; i++) {
            guarded.submit(instruction());
        }

        assertThat(rail.calls)
                .as("and an answer closes it: the rail is being called again without waiting")
                .isEqualTo(9);
    }

    @Test
    void aProbeThatIsNotAnsweredOpensItAgain() {
        PaymentRail guarded = guarded();
        rail.failWith(new RailUnreachable("whatever", "the network is down"));
        for (int i = 0; i < 3; i++) {
            guarded.submit(instruction());
        }

        clock.advance(COOLDOWN.plusSeconds(1));
        assertThat(guarded.submit(instruction()).isOk()).isFalse();
        assertThat(rail.calls).as("one probe per cooldown, not a fresh burst").isEqualTo(4);

        clock.advance(Duration.ofSeconds(30));
        assertThat(guarded.submit(instruction()).isOk()).isFalse();
        assertThat(rail.calls).as("the cooldown restarted when the probe failed").isEqualTo(4);
    }

    @Test
    void aTerminalRefusalDoesNotOpenIt() {
        PaymentRail guarded = guarded();
        rail.failWith(new RailRejected("whatever", "insufficient funds"));

        for (int i = 0; i < 10; i++) {
            assertThat(guarded.submit(instruction()).isOk()).isFalse();
        }

        assertThat(rail.calls)
                .as("a rail that answers 'no' is a working rail with an opinion. Counting a rejection would let "
                        + "one group's insufficient funds stop everybody else's settlement")
                .isEqualTo(10);
        assertThat(((Result.Failure<?>) guarded.submit(instruction())).error())
                .isInstanceOf(RailRejected.class);
    }

    @Test
    void aRailThatThrowsIsTreatedAsARailThatDidNotAnswer() {
        PaymentRail guarded = guarded();
        rail.throwOnEveryCall();

        Result<?> answer = guarded.submit(instruction());

        assertThat(answer.isOk()).isFalse();
        assertThat(((Result.Failure<?>) answer).error())
                .as("the caller's contract is a Result, so an exception escaping here would change its behaviour "
                        + "based on where the fault happened - and an adapter that throws is a rail that did not "
                        + "answer")
                .isInstanceOf(RailUnreachable.class);
    }

    @Test
    void aPolicyThatCouldNotProtectAnythingIsRefused() {
        assertThatThrownBy(() -> new BreakerPolicy(0, COOLDOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one failure");
        assertThatThrownBy(() -> new BreakerPolicy(3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no time at all");
    }

    @Test
    void itIsStillTheSameRailOtherwise() {
        PaymentRail guarded = guarded();

        assertThat(guarded.name()).isEqualTo("counting");
        assertThat(guarded.supportedCurrencies())
                .as("currencies are the rail's to declare, and a breaker has no opinion about them")
                .isEqualTo(rail.supportedCurrencies());
    }

    private PaymentRail guarded() {
        return Rails.withCircuitBreaker(rail, new BreakerPolicy(3, COOLDOWN), clock);
    }

    private static TransferInstruction instruction() {
        return new TransferInstruction(RUN, "reductxn/run/INR/alice/boris/100", RUN,
                UUID.fromString("22222222-2222-4222-8222-222222222222"), Money.of(10_000L, Currency.of("INR", 2)));
    }

    /** A clock a test can move, because a cooldown is the only thing a breaker decides about. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** A rail that counts what it was asked and can be told what to answer. */
    private static final class CountingRail implements PaymentRail {

        private int calls;
        private RailStatus.State answer = RailStatus.State.CONFIRMED;
        private DomainError failure;
        private boolean throwOnEveryCall;

        private void answerWith(RailStatus.State state) {
            answer = state;
            failure = null;
        }

        private void failWith(DomainError refusal) {
            failure = refusal;
        }

        private void throwOnEveryCall() {
            throwOnEveryCall = true;
        }

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public List<Currency> supportedCurrencies() {
            return List.of(Currency.of("INR", 2));
        }

        @Override
        public Result<RailReference> submit(TransferInstruction instruction) {
            calls++;

            if (throwOnEveryCall) {
                throw new IllegalStateException("the connection was reset");
            }

            if (failure != null) {
                return Result.fail(failure);
            }

            return Result.ok(new RailReference(instruction.railRef(),
                    new RailStatus(instruction.railRef(), answer, "answered by the counting rail")));
        }

        @Override
        public Result<RailStatus> status(String railRef) {
            calls++;
            return Result.ok(new RailStatus(railRef, answer, "answered by the counting rail"));
        }
    }
}
