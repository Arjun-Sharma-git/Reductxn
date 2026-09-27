package io.reductxn.api.web.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * The contract's body for the acknowledgement of a rail callback.
 *
 * <p><strong>A receipt, and the same three facts the rail already knows plus one it does not.</strong> The state it
 * sent is echoed because that is what was recorded, and {@code receivedAt} is the instant this deployment holds it
 * under. Whether the money moved is not here: that is the transfer the reference belongs to, which the settlement
 * worker updates on its next pass and {@code GET /runs/{runId}} reports.
 *
 * <p>A rail needs this for one purpose: to be able to say "that delivery was acknowledged at this instant" without
 * replaying it. Answering with what was written is this contract's habit for every other write (ADR 0050).
 */
public record RailCallbackResponse(String provider, String railRef, String state, Instant receivedAt) {

    public RailCallbackResponse {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
