package io.reductxn.rails.api;

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
     * @param instruction the instruction, including a caller-derived {@code railRef}
     * @return the rail's reference, or a failure classified as retryable or terminal
     */
    Result<RailReference> submit(TransferInstruction instruction);

    /**
     * Polls the current state of a previously submitted transfer.
     *
     * <p>Used by the reconciliation sweep to resolve transfers left in {@code submitted}.
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
}
