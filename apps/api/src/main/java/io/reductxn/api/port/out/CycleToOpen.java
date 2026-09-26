package io.reductxn.api.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The cycle row to write, with every value already decided.
 *
 * <p>The arrangement {@link ObligationToRecord} uses, for the same reason: the use case decides what the row says -
 * an identity, the pair that keys it, the two instants - and the adapter writes it. Nothing below this layer mints
 * an id or reads a clock, which is what makes a retry and a test mean the same thing twice.
 *
 * @param id      the cycle's identity, minted by the use case
 * @param groupId whose books it closes
 * @param asOf    the instant they were closed at
 * @param at      when the request arrived, which is the row's {@code created_at}
 */
public record CycleToOpen(UUID id, UUID groupId, Instant asOf, Instant at) {

    public CycleToOpen {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(at, "at");
    }
}
