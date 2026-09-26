package io.reductxn.rails.internal.mock;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.rails.api.PaymentRail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A rail that moves no money and says so by confirming everything it accepts.
 *
 * <p>Reached through {@link io.reductxn.rails.api.Rails#mock()} (RULE B10), never by name from another module.
 *
 * <p><strong>What it is for.</strong> Two things, and neither is "pretending to be a bank". It is the
 * deployment that has no CBDC connection yet - MASTER-PLAN phase 2's "settlement cycle on MockRail" - where the
 * alternative to a mock is that {@code executing} and {@code done} cannot be reached at all, so the states
 * above them cannot be tested. And it is the demo rail, because a rail's real behaviour is not available to a
 * reviewer running the compose stack.
 *
 * <p><strong>What it deliberately does not do: fail, wait, or vary.</strong> A mock that could be told to fail
 * would be a test double wearing a deployment's name, and the tests that need failure control it in their own
 * sources where the script is visible next to the assertion. Determinism is the only useful property here.
 *
 * <p><strong>Its idempotency is a memory, not a check.</strong> RULE S2 says re-submitting a transfer must
 * return the existing reference rather than move money twice. The rail cannot know the difference between a
 * re-submission and a new transfer - the caller's {@code railRef} is the whole of what distinguishes them - so
 * it keeps what it has already accepted and answers from that. That is the same mechanism a real rail uses, and
 * it is why the reference must be derived from the transfer rather than drawn at random.
 *
 * <p>Currency support is the three the catalogue seeds. An unsupported currency is answered with a terminal
 * failure rather than being accepted: a rail that "settles" something it cannot settle is worse than one that
 * refuses, and the caller that is meant to catch this is the run, which checks the rail's declared currencies
 * before it moves anything.
 */
public final class MockRail implements PaymentRail {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency USD = Currency.of("USD", 2);

    /** What this rail has already been asked for, by the caller's reference. */
    private final Map<String, RailStatus> accepted = new LinkedHashMap<>();

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public List<Currency> supportedCurrencies() {
        return List.of(INR, JPY, USD);
    }

    @Override
    public synchronized Result<RailReference> submit(TransferInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");

        RailStatus alreadyAccepted = accepted.get(instruction.railRef());
        if (alreadyAccepted != null) {
            return Result.ok(new RailReference(instruction.railRef(), alreadyAccepted));
        }

        if (!supportedCurrencies().contains(instruction.amount().currency())) {
            return Result.ok(new RailReference(instruction.railRef(), new RailStatus(
                    instruction.railRef(), RailStatus.State.FAILED,
                    "this rail does not settle " + instruction.amount().currency().code())));
        }

        RailStatus confirmed = new RailStatus(instruction.railRef(), RailStatus.State.CONFIRMED,
                "confirmed by " + name());
        accepted.put(instruction.railRef(), confirmed);

        return Result.ok(new RailReference(instruction.railRef(), confirmed));
    }

    @Override
    public synchronized Result<RailStatus> status(String railRef) {
        Objects.requireNonNull(railRef, "railRef");

        RailStatus known = accepted.get(railRef);

        if (known == null) {
            // Not a failure result: the rail answered, and what it answered is that it has nothing under this
            // reference. Silence would be a different thing, and this port has no way to express it yet.
            return Result.ok(new RailStatus(railRef, RailStatus.State.FAILED,
                    "this rail has no record of " + railRef));
        }

        return Result.ok(known);
    }
}
