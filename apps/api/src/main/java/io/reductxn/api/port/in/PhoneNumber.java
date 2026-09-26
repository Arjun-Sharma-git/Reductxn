package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A phone number asking to be signed in, at an instant the boundary supplies (RULE B21).
 *
 * @param phoneNumber the number, as the caller wrote it
 * @param requestedAt when the request arrived, from the boundary's clock - which the rate limit counts from
 */
public record PhoneNumber(String phoneNumber, Instant requestedAt) {

    public PhoneNumber {
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
