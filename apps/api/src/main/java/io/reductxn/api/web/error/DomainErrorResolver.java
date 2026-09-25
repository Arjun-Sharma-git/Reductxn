package io.reductxn.api.web.error;

import io.reductxn.kernel.api.DomainError;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

/**
 * Gives a domain failure its code on the way out.
 *
 * <p><strong>Why not {@code @ExceptionHandler}.</strong> The obvious approach is
 * {@code @ExceptionHandler(DomainError.class)}, and it does not compile: Spring requires a
 * {@code Class<? extends Throwable>}, and {@code DomainError} is an interface a failure implements rather
 * than a type it extends. Handling the interface - which is exactly what "every domain failure carries a
 * code" means - therefore needs a resolver. The alternatives were a hand-maintained list of exception
 * classes, which silently loses the code the day someone adds one and forgets the list, or a common base
 * class in {@code libs/kernel}, which is a change to the domain core made for the convenience of the HTTP
 * layer. Recorded in MASTER-PLAN section 15 rather than rediscovered.
 *
 * <p><strong>Why a resolver rather than a catch-all advice.</strong> A resolver is asked about every
 * exception and may decline by returning {@code null}, which is how a framework error keeps the status it
 * deserves: an unparseable group id must stay a {@code 400}, and an advice registered for
 * {@code RuntimeException} would take precedence over the handler that produces it and turn it into a
 * {@code 500}.
 *
 * <p>An exception implementing {@link DomainError} is a <em>programming fault</em> by the kernel's own
 * distinction ("expected failures are values, not exceptions"), so it is a {@code 500} with the code in
 * the log and in the response. Expected failures travel as {@code Result} values and are mapped where they
 * are unwrapped, which is a separate path with no cases yet.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DomainErrorResolver implements HandlerExceptionResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DomainErrorResolver.class);

    private final ObjectMapper json;

    public DomainErrorResolver(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {

        if (!(failure instanceof DomainError domainError)) {
            // Not ours. Returning null lets Spring's resolvers answer, which is the whole reason this is a
            // resolver and not an advice.
            return null;
        }

        LOG.warn("domain fault {} on {}: {}",
                domainError.code(), request.getRequestURI(), domainError.message());

        ProblemDetail problem = ProblemTranslator.toProblem(
                domainError, HttpStatus.INTERNAL_SERVER_ERROR, request.getRequestURI());

        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        try {
            json.writeValue(response.getOutputStream(), problem);
        } catch (IOException unwritable) {
            throw new IllegalStateException("failed to write a problem response", unwritable);
        }

        // Non-null means "handled": Spring will not look further, and an empty ModelAndView renders
        // nothing because the body is already written.
        return new ModelAndView();
    }
}
