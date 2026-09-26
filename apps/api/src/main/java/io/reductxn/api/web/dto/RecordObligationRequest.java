package io.reductxn.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's request body for {@code POST /obligations}.
 *
 * <p><strong>No bean-validation annotations, deliberately.</strong> They would be silently inert:
 * {@code spring-boot-starter-web} does not bring a validation provider, so {@code @NotNull} on a field of a
 * request body is ignored and a missing field arrives as null - which is worse than not annotating, because
 * the annotation reads like a check. The controller validates what it needs instead, and the reason it is a
 * check rather than a second failure vocabulary is that a malformed request is a {@code 400} while a
 * well-formed request the domain refuses is a {@code 422} with a code the client branches on.
 *
 * <p>{@code occurredAt} is optional here as the contract says; the controller substitutes the clock's instant,
 * because the boundary is the only layer allowed to read one (RULE B21).
 */
public record RecordObligationRequest(
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        MoneyDto amount,
        String settleInCurrency,
        String memo,
        Instant occurredAt) {
}
