package io.reductxn.api.web.v1;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.reductxn.api.application.rails.RailCallbackSignature;
import io.reductxn.api.port.in.AcceptRailCallback;
import io.reductxn.api.port.in.RailCallbackRefusals;
import io.reductxn.api.port.in.RecordedRailCallback;
import io.reductxn.api.web.dto.RailCallbackMapper;
import io.reductxn.api.web.dto.RailCallbackRequest;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import java.time.Clock;
import java.util.Objects;

/**
 * {@code POST /v1/webhooks/rails/{provider}}, per the contract.
 *
 * <p><strong>The first boundary here that no person uses.</strong> Every other operation in this module acts for a
 * member, resolved from a session; this one acts for a rail, which has no token - so the contract opts it out of the
 * global bearer scheme and what authenticates it is a signature over the bytes of its body
 * ({@link RailCallbackSignature}).
 *
 * <p><strong>It takes the body as bytes rather than as a bound DTO, and that is the whole design.</strong> A signature
 * covers bytes: binding first would mean the body had been trusted - parsed, converted, walked by Jackson - before
 * anything established who sent it. So the bytes are verified first and parsed after, and the DTO exists only for the
 * half that has already been established as the rail's.
 */
@RestController
@RequestMapping("/v1/webhooks/rails")
public class RailWebhooksController {

    private final AcceptRailCallback acceptRailCallback;
    private final RailCallbackSignature signatures;
    private final ObjectMapper json;
    private final Clock clock;

    public RailWebhooksController(
            AcceptRailCallback acceptRailCallback,
            RailCallbackSignature signatures,
            ObjectMapper json,
            Clock clock) {

        this.acceptRailCallback = Objects.requireNonNull(acceptRailCallback, "acceptRailCallback");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@code POST /v1/webhooks/rails/{provider}}: what a rail tells us asynchronously.
     *
     * <p><strong>The first boundary in this module with no session, and the only one a machine calls.</strong> A rail
     * has no member and no token; what it has is a shared secret, and the contract opts this operation out of the global
     * bearer scheme with {@code security: []}. What authenticates the request instead is a signature over its raw bytes,
     * checked before the body is parsed or read at all.
     *
     * <p><strong>It records an answer rather than applying one.</strong> The row it writes is read by
     * {@code workers/settlement}'s driver, which prefers a delivered answer to a poll (ADR 0051) - so the money's state
     * is still decided by the worker's own use case, and this endpoint cannot move anything by itself. That is why the
     * reference is not checked against the worker's transfers: an inert row is the worst a forged *signed* callback
     * could achieve, and only the rail's own secret can produce a signature.
     *
     * <p><strong>Every failure here is a {@code Problem} a rail can branch on.</strong> {@code 401} for a signature that
     * does not verify - with one code for a missing header, a wrong secret and an altered body, because telling them
     * apart would tell an attacker which half of a guess was right. {@code 400} for a body that verifies and cannot be
     * used, naming the field.
     */
    @PostMapping("/{provider}")
    public ResponseEntity<Object> callback(
            @PathVariable String provider,
            @RequestHeader(name = RailCallbackSignature.HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest http) {

        // Verified against the bytes, which is the whole reason this method takes them as bytes: a body that does not
        // verify is never parsed, so no field of it can influence anything.
        if (!signatures.verifies(body == null ? new byte[0] : body, signature)) {
            return problem(new RailCallbackRefusals.InvalidSignature(provider), http.getRequestURI());
        }

        RailCallbackRequest callback;

        try {
            callback = json.readValue(body, RailCallbackRequest.class);
        } catch (JacksonException malformed) {
            return problem(new RailCallbackRefusals.NotUnderstood("body", "not a json object"), http.getRequestURI());
        }

        Result<RecordedRailCallback> recorded = acceptRailCallback.accept(
                RailCallbackMapper.toCommand(provider, callback, clock.instant()));

        if (recorded instanceof Result.Ok<RecordedRailCallback> accepted) {
            // 202, per the contract, and not 200: the callback is recorded and the thing it is about - a transfer
            // moving or not moving - has not happened yet, because the worker is what acts on the answer.
            return ResponseEntity.accepted().body(RailCallbackMapper.toResponse(accepted.value()));
        }

        return problem(((Result.Failure<RecordedRailCallback>) recorded).error(), http.getRequestURI());
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
