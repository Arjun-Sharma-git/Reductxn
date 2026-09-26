package io.reductxn.api.web.dto;

import java.time.Instant;

/**
 * The contract's request body for {@code POST /v1/groups/{groupId}/cycles}.
 *
 * <p><strong>One field, and it is required.</strong> The instant the books are closed at is the whole request: it
 * keys the cycle - one cycle per group per instant - and it is the instant every rate, tax rule and commitment in
 * it is resolved at (ADR 0032). A default of "now" would be worse than a missing field: two requests a
 * microsecond apart would be two different cycles, so a double click would settle a group twice and a retry after a
 * timeout would open a second cycle instead of finding the first.
 *
 * <p>What the contract's body used to carry - an {@code action} and a period between two dates - is gone, and each
 * for its own reason: the domain has no periods (a cycle is a claim about a moment, and "September" is a client's
 * way of choosing one), and closing a cycle has nowhere to be stored, because {@code settlement_cycles} has no
 * status column. A contract that offers an operation its own schema cannot express is one that will be implemented
 * as written one day.
 *
 * <p>An {@code Instant} rather than a string for the reason {@link RecordObligationRequest} uses one for
 * {@code occurredAt}: it is the same wire format, Spring parses it the same way, and a malformed one fails before
 * this record is constructed. As there, a missing or absent field is this module's to answer with a coded
 * {@code 400} rather than with Spring's own body.
 */
public record CycleRequest(Instant asOf) {
}
