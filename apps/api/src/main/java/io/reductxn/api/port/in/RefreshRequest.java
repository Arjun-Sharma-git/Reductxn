package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A refresh token asking to be replaced, at an instant the boundary supplies.
 *
 * @param refreshToken the token the caller holds
 * @param refreshedAt  when the rotation was asked for, from the boundary's clock
 */
public record RefreshRequest(String refreshToken, Instant refreshedAt) {

    public RefreshRequest {
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(refreshedAt, "refreshedAt");
    }
}
