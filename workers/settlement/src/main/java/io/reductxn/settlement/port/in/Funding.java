package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a run needs from outside, and what has arrived (RULE S4).
 *
 * <p>The blocker this represents is the reason {@code awaiting_fx_funding} exists: when a currency's
 * {@code fx_clearing} position is non-zero the group has promised currency it does not hold, and the engine
 * cannot produce it (P2 - reductxn is non-custodial and moves nothing itself). Until ADR 0015 this was a
 * state with no way out; this is the other side of the gate.
 *
 * <p><strong>Per currency, and never summed.</strong> The requirement is a magnitude per currency and the
 * coverage is checked per currency: nothing here adds rupees to yen (ADR 0012 point 4), which is why the
 * lists are of {@link Money} rather than of amounts.
 *
 * <p><strong>{@link #received} may exceed the requirement.</strong> A group that wires in more than the plan
 * needs has over-funded by its own choice; the gate asks whether each currency is covered, not whether it
 * was covered exactly, and refusing the excess would leave a real transfer unrecorded - a worse fact to have
 * in a ledger than a surplus. What the report must not do is present the excess as progress: it is not
 * carried into any other currency.
 *
 * @param required the outstanding external requirement per currency, from the plan's clearing positions
 * @param received what has been recorded as funded, per currency
 */
public record Funding(List<Money> required, List<Money> received) {

    public Funding {
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(received, "received");
        required = List.copyOf(required);
        received = List.copyOf(received);
    }

    /** Nothing required and nothing received: an ordinary single-currency cycle. */
    public static Funding none() {
        return new Funding(List.of(), List.of());
    }

    /**
     * The requirement and its coverage, both derived from rows.
     *
     * <p>{@code received} is summed here rather than by the caller, because funding arrives in pieces - a
     * treasurer may bring half a currency in today and half next week - and two partial fundings that together
     * cover the requirement have to read as covered. Summing is exact: {@code Money.plus} throws on overflow
     * rather than wrapping, and the currency must match, so a row recorded in the wrong currency cannot be
     * added to the requirement it is being compared against.
     *
     * @param required  the plan's external requirements, one per currency that needs funding
     * @param fundings  every recorded funding for the run, in any order
     */
    public static Funding of(List<Money> required, List<Money> fundings) {
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(fundings, "fundings");

        Map<Currency, Money> received = new LinkedHashMap<>();
        for (Money funding : fundings) {
            received.merge(funding.currency(), funding, Money::plus);
        }

        List<Money> receivedInRequirementOrder = new ArrayList<>();
        for (Money requirement : required) {
            Money covered = received.remove(requirement.currency());
            receivedInRequirementOrder.add(covered == null ? Money.zero(requirement.currency()) : covered);
        }

        // Any funding left over is in a currency the run does not require. It is not dropped silently: it is
        // reported after the requirements, which makes it visible in the answer rather than a row nobody reads.
        receivedInRequirementOrder.addAll(received.values());

        return new Funding(required, receivedInRequirementOrder);
    }

    /** What is still missing, per currency. Zero-amount entries are omitted rather than reported as met. */
    public List<Money> outstanding() {
        List<Money> outstanding = new ArrayList<>();

        for (int index = 0; index < required.size(); index++) {
            Money requirement = required.get(index);
            Money covered = received.get(index);

            if (covered.compareTo(requirement) < 0) {
                outstanding.add(requirement.minus(covered));
            }
        }

        return List.copyOf(outstanding);
    }

    /** Whether every currency the run needs has been brought in (RULE S4's gate). */
    public boolean satisfied() {
        return outstanding().isEmpty();
    }

    /** The currencies with an external requirement, which is what a funding has to be aimed at. */
    public List<Currency> requiredCurrencies() {
        return required.stream().map(Money::currency).toList();
    }

    /** The requirement in one currency, or zero when this run does not need it. */
    public Money requirementIn(Currency currency) {
        return required.stream()
                .filter(money -> money.currency().equals(currency))
                .findFirst()
                .orElseGet(() -> Money.zero(currency));
    }

    /** What is still missing in one currency: the requirement less what has been received. */
    public Money outstandingIn(Currency currency) {
        Money requirement = requirementIn(currency);
        Money covered = receivedIn(currency);

        return covered.compareTo(requirement) < 0 ? requirement.minus(covered) : Money.zero(currency);
    }

    /** What has been received in one currency, or zero. */
    public Money receivedIn(Currency currency) {
        return received.stream()
                .filter(money -> money.currency().equals(currency))
                .findFirst()
                .orElseGet(() -> Money.zero(currency));
    }
}
