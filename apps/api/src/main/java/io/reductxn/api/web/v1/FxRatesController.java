package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadReferenceRate;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.web.dto.FxRateResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.fx.api.FxRate;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * {@code GET /v1/fx/rates}, per the contract: what a pair converts at, for a screen about to record a conversion.
 *
 * <p>The third and last of the reads ADR 0035's audit found the client calling with nothing behind it. It is the
 * narrowest of the three - the recording form asks for it only when the settlement currency differs from the debt
 * currency, and only to show a hint beside the field - and it is the one with a business decision in it, which is why
 * it waited for its own slice: **the reference read applies the same freshness policy as locking** (ADR 0037). A
 * stale rate is a refusal rather than a number, so the form can say "this pair cannot be settled in yet" before
 * somebody fills it in, instead of after.
 *
 * <p>Three refusals leave here and none of them is invented at this layer:
 *
 * <ul>
 *   <li>{@code 400 api.request_incomplete} - a missing or repeated-currency parameter. The general code the cycles,
 *       runs and members boundaries already use, rather than a fourth spelling of "you asked a malformed question".
 *   <li>{@code 422 api.obligation_unknown_currency} - a code the catalogue does not hold, which is the same condition
 *       the recording path refuses with the same code: the currency cannot be transacted, so no rate can exist.
 *   <li>{@code 422 fx.rate_stale} and {@code 503 fx.rate_unavailable} - the provider's own two answers, travelling
 *       unwrapped because it is the only thing that can tell them apart.
 * </ul>
 */
@RestController
@RequestMapping("/v1/fx/rates")
public class FxRatesController {

    private final ReadReferenceRate readReferenceRate;

    public FxRatesController(ReadReferenceRate readReferenceRate) {
        this.readReferenceRate = Objects.requireNonNull(readReferenceRate, "readReferenceRate");
    }

    @GetMapping
    public ResponseEntity<Object> rate(
            @RequestParam(name = "base", required = false) String base,
            @RequestParam(name = "quote", required = false) String quote,
            HttpServletRequest http) {

        DomainError malformed = malformedQuery(base, quote);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<FxRate> rate = readReferenceRate.forPair(base, quote);

        if (rate instanceof Result.Ok<FxRate> found) {
            return ResponseEntity.ok(FxRateResponse.of(found.value()));
        }

        return problem(((Result.Failure<FxRate>) rate).error(), http.getRequestURI());
    }

    /**
     * The two parameters, checked here rather than bound to their types.
     *
     * <p>{@code base} and {@code quote} are strings in the contract (a currency code), so nothing needs parsing;
     * what needs deciding is that both are present and that they are a <em>pair</em>. A conversion from a currency to
     * itself is refused rather than answered with a rate of one, because a fabricated identity rate is
     * indistinguishable from a quoted one to everything downstream - and the answer to "what does INR convert to in
     * INR at" is not a market fact.
     */
    private static DomainError malformedQuery(String base, String quote) {
        if (base == null || base.isBlank()) {
            return new RunRefusals.RequestIncomplete("base, a currency code");
        }
        if (quote == null || quote.isBlank()) {
            return new RunRefusals.RequestIncomplete("quote, a currency code");
        }
        if (base.equals(quote)) {
            return new RunRefusals.RequestIncomplete(
                    "quote, a currency other than base: a pair of one currency is not a conversion");
        }

        return null;
    }

    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        ProblemDetail detail = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(detail.getStatus()).body(detail);
    }
}
