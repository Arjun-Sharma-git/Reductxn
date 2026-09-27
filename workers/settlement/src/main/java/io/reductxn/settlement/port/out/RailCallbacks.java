package io.reductxn.settlement.port.out;

import io.reductxn.rails.api.PaymentRail;

import java.util.Optional;

/**
 * What a rail has told us about a transfer, without being asked (ADR 0051).
 *
 * <p><strong>This is a read of the inbox, not a second way to talk to a rail.</strong> A rail's callback is delivered
 * to {@code apps/api} - the only deployable with a web surface - which verifies its signature and writes it into
 * {@code settlement_rail_callbacks}. This port is how the driver finds it, so that {@code ExecuteRunService} can
 * prefer an answer it was given over one it has to ask for.
 *
 * <p><strong>It returns the rail's own type, and that is deliberate.</strong> A stored callback is exactly an answer
 * from {@link PaymentRail#status(String)} that arrived by a different route, so the driver hands it to the same
 * {@code record(...)} path and the money's state is decided in one place (ADR 0021). A port that returned a
 * bespoke shape would be a second description of the same answer, and the two would drift.
 *
 * <p><strong>An absent answer is not a refusal and not silence.</strong> It means nothing has been delivered about
 * this reference, which is the ordinary case for a rail that has never called back - and the caller's response is to
 * poll, which is what it did before this table existed.
 */
public interface RailCallbacks {

    /**
     * The latest answer a rail has delivered about this reference, if any has arrived.
     *
     * @param railRef the reference the transfer was offered under (RULE S2)
     * @return the rail's answer as it was delivered, or empty when nothing has been delivered
     */
    Optional<PaymentRail.RailStatus> answerFor(String railRef);
}
