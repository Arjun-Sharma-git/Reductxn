package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * What a rail's callback wrote, as the acknowledgement reports it.
 *
 * <p><strong>It is a receipt rather than a state, and more so here than anywhere else in this contract.</strong> A
 * callback records what a rail said; whether the money moved is a question for the transfer the reference belongs to,
 * which the settlement worker updates on its next pass - and which {@code GET /runs/{runId}} reports. So the
 * acknowledgement says what this deployment now holds: the reference, the rail's word, and when it arrived.
 *
 * @param provider   the rail that called back
 * @param railRef    the reference the callback was about
 * @param state      the rail's word, as recorded
 * @param receivedAt when this deployment recorded it
 */
public record RecordedRailCallback(String provider, String railRef, String state, Instant receivedAt) {

    public RecordedRailCallback {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
