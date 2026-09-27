package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.RequestedExecution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The contract's body for a {@code 202} from {@code POST /v1/runs/{runId}/execute}.
 *
 * <p><strong>A receipt rather than a status, and that is what makes the {@code 202} honest.</strong> The run has not
 * moved when this is answered: the worker's driver offers it to a rail on its next pass. So the body says the one
 * thing that is true at that moment - this member asked, at this instant - and leaves what happened to the run, where
 * {@code GET /runs/{runId}} reports it.
 *
 * <p><strong>The status is {@code 202} with a body rather than an empty {@code 202}.</strong> Every other write in
 * this contract answers with what it wrote; an empty body here would be the only write whose answer says nothing, and
 * a client that wanted to show a member "asked at 14:02" would have to invent the instant. It is still a receipt: the
 * {@code Location} of anything is the run, and this is not it.
 */
public record RequestedExecutionResponse(UUID runId, UUID requestedBy, Instant requestedAt) {

    public RequestedExecutionResponse {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    public static RequestedExecutionResponse of(RequestedExecution requested) {
        return new RequestedExecutionResponse(
                requested.runId(), requested.requestedBy(), requested.requestedAt());
    }
}
