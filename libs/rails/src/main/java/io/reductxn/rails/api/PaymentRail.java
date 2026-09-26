package io.reductxn.rails.api;

import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.List;
import java.util.UUID;

/**
 * The seam between reductxn and the outside world of money movement.
 *
 * <p>Principle P2: reductxn is non-custodial. It never holds CBDC and never holds private keys;
 * it produces settlement instructions and this port executes them. Once a transfer is confirmed,
 * the rail is authoritative for that movement and reductxn reconciles against it.
 *
 * <p>Every implementation must be idempotent on {@code railRef} (RULE S2). Re-submitting a
 * transfer that already exists must return the existing reference, not move money twice.
 */
public interface PaymentRail {

    /** Stable identifier for this rail, for example {@code mock} or {@code cbdc-sandbox}. */
    String name();

    /** Currencies this rail can settle. Empty means none, which is a deployment error. */
    List<Currency> supportedCurrencies();

    /**
     * Submits a single transfer instruction.
     *
     * <p><strong>A refusal says whether asking again could help.</strong> Two failures are possible and they mean
     * opposite things: {@link RailUnreachable} is the rail not answering (a network error, a 429, a 5xx), where
     * nothing was offered and nothing moved, and {@link RailRejected} is the rail answering that it will not
     * take the instruction (insufficient funds, a malformed account, a currency it does not settle), where
     * offering it again would produce the same answer. A caller that could not tell them apart would either
     * retry a refusal for ever or give up on a rail that had a bad minute - which is why the classification is
     * in the type rather than in a message.
     *
     * @param instruction the instruction, including a caller-derived {@code railRef}
     * @return the rail's reference, or a {@link RailRefusal}
     */
    Result<RailReference> submit(TransferInstruction instruction);

    /**
     * Polls the current state of a previously submitted transfer.
     *
     * <p>Used to resolve transfers left in {@code submitted} - by the driver in
     * {@code workers/settlement}, on a cadence.
     *
     * <p>A failure here is a {@link RailRefusal} like {@link #submit}'s, and callers should read it the way they
     * would read {@link RailUnreachable}: a poll has no side effects, so a rail that refuses to answer one has
     * still told the caller nothing about whether the money moved.
     */
    Result<RailStatus> status(String railRef);

    /**
     * A transfer as the rail sees it.
     *
     * @param railRef     idempotency key, derived from the run, counterparties and amount
     * @param fromUserId  payer
     * @param toUserId    payee
     * @param amount      exact amount in minor units
     */
    record TransferInstruction(UUID runId, String railRef, UUID fromUserId, UUID toUserId, Money amount) {}

    /** The rail's handle for a submitted transfer. */
    record RailReference(String railRef, RailStatus status) {}

    /** Where a transfer currently stands, as reported by the rail. */
    record RailStatus(String railRef, State state, String detail) {

        public enum State {
            ACCEPTED,
            PENDING,
            CONFIRMED,
            FAILED
        }
    }

    /**
     * A call the rail did not take, and the one question a caller has about it: could asking again help?
     *
     * <p><strong>Sealed with two implementations, deliberately.</strong> A single record with a
     * {@code retryable} flag would be easier to construct and easier to get wrong: the two cases are not shades
     * of one another - one means nothing was offered, the other means it was refused - and a caller that has to
     * switch over its type is a caller that cannot forget the difference. The {@code retryable()} method exists
     * for the places where the classification is all that matters (a backoff policy, a circuit breaker), and
     * those places read it rather than re-deriving it from the type.
     *
     * <p>Both are {@link DomainError}s, so a refusal that reaches a report carries a stable code - and a
     * deployment can alert on one without parsing prose. The {@code detail} is the rail's own words.
     */
    sealed interface RailRefusal extends DomainError permits RailUnreachable, RailRejected {

        /** The reference the call was about, so a refusal can be attributed without a second lookup. */
        String railRef();

        /**
         * Whether offering the same instruction again could produce a different answer.
         *
         * <p>True for a rail that did not answer, false for a rail that refused. The distinction is the whole
         * point of this type: retrying the first is what backoff exists for, and retrying the second is a
         * settlement loop that never ends.
         */
        boolean retryable();

        /** The rail's own words, which are free text because the rail's vocabulary is not reductxn's. */
        String detail();
    }

    /**
     * The rail did not answer: a network error, a rate limit, a 5xx, or a rail that has been left alone because
     * it kept failing.
     *
     * <p><strong>Nothing was offered and nothing moved.</strong> This is the case a caller may retry, and the
     * case it must not treat as a failure of the transfer: the transfer was never refused, so marking it failed
     * would claim a decision the rail never made.
     */
    record RailUnreachable(String railRef, String detail) implements RailRefusal {

        public static final String CODE = "rails.unreachable";

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the rail did not answer for " + railRef + ": " + detail
                    + ". Nothing was offered and nothing moved, so the same instruction may be sent again";
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * The rail answered, and the answer is that it will not take this instruction.
     *
     * <p><strong>Asking again would produce the same answer</strong>, so this is a terminal failure of the
     * transfer: the money did not move, and it will not move without something changing outside reductxn -
     * funds, a corrected account, a rail that settles the currency.
     */
    record RailRejected(String railRef, String detail) implements RailRefusal {

        public static final String CODE = "rails.rejected";

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the rail refused " + railRef + ": " + detail
                    + ". The same instruction would be refused again, so this transfer has failed";
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }
}
