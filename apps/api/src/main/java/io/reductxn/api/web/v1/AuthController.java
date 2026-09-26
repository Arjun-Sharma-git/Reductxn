package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.IssuedTokens;
import io.reductxn.api.port.in.PhoneNumber;
import io.reductxn.api.port.in.RefreshRequest;
import io.reductxn.api.port.in.RefreshSession;
import io.reductxn.api.port.in.RequestSignIn;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignInCode;
import io.reductxn.api.port.in.VerifySignIn;
import io.reductxn.api.web.dto.OtpRequestBody;
import io.reductxn.api.web.dto.RefreshRequestBody;
import io.reductxn.api.web.dto.TokenPairResponse;
import io.reductxn.api.web.dto.VerifyOtpBody;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;

/**
 * The three sign-in operations: ask for a code, hand it back, and renew a session.
 *
 * <p><strong>What this build's authentication story is.</strong> A phone number and a code: no password to leak, no
 * password reset to get wrong, and the absence of a delivery provider is the one stand-in (the adapter that logs the code
 * says so loudly). The contract declares exactly these three operations, each with `security: []` - the OpenAPI way of
 * saying "the bearer token does not authenticate this" - and this class is what makes that declaration true.
 *
 * <p><strong>Nothing here decides anything.</strong> Which codes are accepted, how long one lives, how often a number may
 * ask, when a session ends: all of it belongs to the services. The boundary's jobs are the same three as everywhere else
 * in this module - is the request shaped so a use case can be handed it, what clock instant is this (RULE B21), and how
 * does an answer become a response.
 *
 * <p><strong>The verification body is the contract's, and the refresh body is this slice's addition.</strong> The first
 * was declared with `phoneNumber`, `code` and `deviceId`; the second had no body at all, so there was nowhere for a refresh
 * token to travel, and {@code RefreshRequestBody} is the schema that fixes it (ADR 0042).
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final RequestSignIn requestSignIn;
    private final VerifySignIn verifySignIn;
    private final RefreshSession refreshSession;
    private final Clock clock;

    public AuthController(RequestSignIn requestSignIn, VerifySignIn verifySignIn, RefreshSession refreshSession,
            Clock clock) {
        this.requestSignIn = Objects.requireNonNull(requestSignIn, "requestSignIn");
        this.verifySignIn = Objects.requireNonNull(verifySignIn, "verifySignIn");
        this.refreshSession = Objects.requireNonNull(refreshSession, "refreshSession");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Send a code to a number.
     *
     * <p>{@code 202} rather than {@code 201}, which is the contract's word and the honest one: a message was accepted for
     * delivery and nobody can say yet whether it arrived. The body is empty in both directions - there is no resource to
     * return and, deliberately, the code is not in the response: a code in a response is a code anybody reading the
     * response has, which is the same as no code at all.
     */
    @PostMapping("/otp/request")
    public ResponseEntity<Object> requestOtp(@RequestBody(required = false) OtpRequestBody request,
            HttpServletRequest http) {

        DomainError malformed = missing(request == null ? null : request.phoneNumber(), "phoneNumber");

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<Void> outcome = requestSignIn.request(new PhoneNumber(request.phoneNumber(), clock.instant()));

        if (outcome instanceof Result.Failure<Void> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /** Hand a code back and get a session. */
    @PostMapping("/otp/verify")
    public ResponseEntity<Object> verifyOtp(@RequestBody(required = false) VerifyOtpBody request,
            HttpServletRequest http) {

        DomainError malformed = request == null
                ? new RunRefusals.RequestIncomplete("body")
                : firstMissing(request.phoneNumber(), request.code(), request.deviceId());

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<IssuedTokens> issued = verifySignIn.verify(new SignInCode(
                request.phoneNumber(), request.code(), request.deviceId(), clock.instant()));

        if (issued instanceof Result.Ok<IssuedTokens> tokens) {
            return ResponseEntity.ok(pair(tokens.value()));
        }

        return problem(((Result.Failure<IssuedTokens>) issued).error(), http.getRequestURI());
    }

    /** Replace both tokens, given the refresh token. */
    @PostMapping("/refresh")
    public ResponseEntity<Object> refresh(@RequestBody(required = false) RefreshRequestBody request,
            HttpServletRequest http) {

        DomainError malformed = missing(request == null ? null : request.refreshToken(), "refreshToken");

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<IssuedTokens> issued = refreshSession.refresh(new RefreshRequest(request.refreshToken(), clock.instant()));

        if (issued instanceof Result.Ok<IssuedTokens> tokens) {
            return ResponseEntity.ok(pair(tokens.value()));
        }

        return problem(((Result.Failure<IssuedTokens>) issued).error(), http.getRequestURI());
    }

    private static TokenPairResponse pair(IssuedTokens issued) {
        return new TokenPairResponse(issued.accessToken(), issued.refreshToken(), (int) issued.expiresIn());
    }

    private static DomainError firstMissing(String phoneNumber, String code, String deviceId) {
        DomainError malformed = missing(phoneNumber, "phoneNumber");

        if (malformed == null) {
            malformed = missing(code, "code");
        }

        return malformed == null ? missing(deviceId, "deviceId") : malformed;
    }

    /** A missing or blank field, as the code four other boundaries already answer a missing field with. */
    private static DomainError missing(String value, String field) {
        return value == null || value.isBlank() ? new RunRefusals.RequestIncomplete(field) : null;
    }

    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
