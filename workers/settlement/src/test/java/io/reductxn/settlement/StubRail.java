package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.rails.api.PaymentRail.RailReference;
import io.reductxn.rails.api.PaymentRail.RailRejected;
import io.reductxn.rails.api.PaymentRail.RailStatus;
import io.reductxn.rails.api.PaymentRail.RailUnreachable;
import io.reductxn.rails.api.PaymentRail.TransferInstruction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A rail under the test's control, in the worker's own test sources.
 *
 * <p><strong>Why this is not {@code MockRail}.</strong> The module's mock confirms everything and cannot be told
 * otherwise - deliberately, because a rail that behaves differently when asked is a test double wearing a
 * deployment's name. This one is a test double and is named like one: it is scripted per transfer, it counts what
 * it was asked, and every assertion about "the rail saw this once" is a question about it rather than about the
 * worker's own rows. The worker's rows could agree with each other while the rail was asked twice.
 *
 * <p>It lives here rather than in {@code libs/rails} for the same reason: nothing in the product needs a rail
 * that fails on demand, and putting one in a library would put it one import away from a deployment.
 *
 * <p>The default is confirmation, so a test that says nothing about the rail is about the ordinary path.
 */
final class StubRail implements PaymentRail {

    /** What this rail will do with one transfer, by its rail reference. */
    enum Behaviour {

        /** Accept it and confirm it, as a rail does when nothing goes wrong. */
        CONFIRM,

        /** Accept it and leave it unconfirmed, which is a transfer in flight rather than a failure. */
        ACCEPT_WITHOUT_CONFIRMING,

        /** Accept it and answer that it failed, which is a transfer the rail took and then could not move. */
        REFUSE,

        /**
         * Refuse it before taking it: the rail answers that it will not accept this instruction, which is the
         * terminal kind of refusal ({@code RailRejected}) and fails the transfer.
         */
        REJECT,

        /** Never receive it: the call does not complete, which is the retryable kind of failure. */
        UNREACHABLE
    }

    /** The currencies a fresh stub settles: what the demo catalogue seeds. */
    private static final List<Currency> DEFAULT_CURRENCIES = List.of(
            Currency.of("INR", 2), Currency.of("JPY", 0), Currency.of("USD", 2));

    private final Map<String, Behaviour> behaviours = new LinkedHashMap<>();
    private final Map<String, RailStatus.State> answers = new LinkedHashMap<>();
    private final List<TransferInstruction> submissions = new ArrayList<>();
    private final List<String> polls = new ArrayList<>();
    private final Set<Currency> currencies = new LinkedHashSet<>(DEFAULT_CURRENCIES);

    /**
     * Back to a rail that has been told nothing and asked nothing.
     *
     * <p>Needed because this is a Spring bean and therefore one instance for a whole test class: without this, a
     * test that restricts what the rail settles would restrict it for the next test, and every assertion that the
     * rail was <em>not</em> asked would fail for reasons belonging to a test that already ran. Sharing one double
     * across a class is worth it - it is what lets a test say "the rail saw this once" - but only if the state is
     * cleared between tests, which is what this is for.
     */
    StubRail reset() {
        behaviours.clear();
        answers.clear();
        submissions.clear();
        polls.clear();
        currencies.clear();
        currencies.addAll(DEFAULT_CURRENCIES);
        return this;
    }

    /** Scripts one transfer's fate. */
    StubRail scripting(String railRef, Behaviour behaviour) {
        behaviours.put(railRef, behaviour);
        return this;
    }

    /** Restricts what this rail will settle, for the deployment-mistake case. */
    StubRail supporting(Currency... supported) {
        currencies.clear();
        currencies.addAll(List.of(supported));
        return this;
    }

    /**
     * Confirms a transfer the rail previously accepted without confirming.
     *
     * <p>What happens at a real rail between two polls, and the only way to test the {@code submitted -->
     * confirmed} arrow without a clock or a scheduler.
     */
    void confirm(String railRef) {
        answers.put(railRef, RailStatus.State.CONFIRMED);
    }

    /** How many times the rail was asked to move this transfer. */
    int submissionsOf(String railRef) {
        return (int) submissions.stream().filter(instruction -> instruction.railRef().equals(railRef)).count();
    }

    List<TransferInstruction> submissions() {
        return List.copyOf(submissions);
    }

    /** How many times the rail was asked what became of something. */
    int polls() {
        return polls.size();
    }

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public List<Currency> supportedCurrencies() {
        return List.copyOf(currencies);
    }

    @Override
    public Result<RailReference> submit(TransferInstruction instruction) {
        submissions.add(instruction);

        Behaviour behaviour = behaviours.getOrDefault(instruction.railRef(), Behaviour.CONFIRM);

        return switch (behaviour) {
            case CONFIRM -> accept(instruction, RailStatus.State.CONFIRMED, "confirmed by the stub rail");
            case ACCEPT_WITHOUT_CONFIRMING -> accept(instruction, RailStatus.State.PENDING, "accepted by the stub");
            case REFUSE -> accept(instruction, RailStatus.State.FAILED, "insufficient funds");
            case REJECT -> Result.fail(new RailRejected(instruction.railRef(), "this rail will not take it"));
            case UNREACHABLE -> Result.fail(new RailUnreachable(instruction.railRef(), "the network is down"));
        };
    }

    @Override
    public Result<RailStatus> status(String railRef) {
        polls.add(railRef);

        RailStatus.State state = answers.getOrDefault(railRef, RailStatus.State.PENDING);

        return Result.ok(new RailStatus(railRef, state, "the stub rail's answer"));
    }

    private Result<RailReference> accept(TransferInstruction instruction, RailStatus.State state, String detail) {
        answers.put(instruction.railRef(), state);
        return Result.ok(
                new RailReference(instruction.railRef(), new RailStatus(instruction.railRef(), state, detail)));
    }

    /**
     * What a rail that cannot be reached answers.
     *
     * <p>A {@link DomainError} rather than an exception, because that is what the port's {@code Result} carries.
     * The code is namespaced and well formed like every other, and this one is invisible to
     * {@code DomainErrorCodeTest}: that test reads the modules on its own classpath, and a test source is not part
     * of a module's published classes - which is the right boundary, since a stub rail cannot fail in production.
     */
    /** The two refusals the port defines are used as they are: a stub that invented its own would be a second
     * vocabulary for the same thing, and the whole point of the classification is that every adapter speaks it. */
}
