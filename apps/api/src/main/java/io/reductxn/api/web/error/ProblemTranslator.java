package io.reductxn.api.web.error;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

/**
 * Domain failures and HTTP statuses, mapped in one place.
 *
 * <p><strong>The domain does not know HTTP.</strong> A failure says what went wrong, not which status
 * code conveys it, so the translation has to happen exactly once and at the boundary - if it happened in
 * two places, the same failure would eventually be two statuses.
 *
 * <p><strong>The client branches on {@code code}, and nothing else.</strong> RULE B20 keeps localized
 * prose out of responses: there is no field a client should render, and the fields that do carry English -
 * {@code title}, {@code detail} - are marked in the contract as developer text for logs and support.
 * {@code type} is {@code about:blank}, which RFC 9457 defines as "no specific type registered"; inventing
 * URIs that resolve to nothing would look more precise while being less honest. When per-code
 * documentation exists, this is the one line that changes.
 *
 * <p><strong>The code is validated on the way out.</strong> A malformed or un-namespaced code cannot be
 * attributed or localized, so it fails here rather than reaching a client that would have to guess. It
 * fails as a {@code 500}, deliberately: the alternative is a client trying to match prose.
 */
public final class ProblemTranslator {

    /** RFC 9457's escape hatch, used because no per-code documentation page exists yet. */
    private static final URI NO_SPECIFIC_TYPE = URI.create("about:blank");

    private ProblemTranslator() {
    }

    /**
     * Turns a domain failure into an RFC 9457 problem.
     *
     * @param status the status the boundary has decided this failure deserves
     */
    public static ProblemDetail toProblem(DomainError failure, HttpStatus status, String instance) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(NO_SPECIFIC_TYPE);
        problem.setTitle(status.getReasonPhrase());
        problem.setDetail(failure.message());
        problem.setInstance(URI.create(instance));
        problem.setProperty("code", DomainCodes.requireWellFormed(failure.code()));
        return problem;
    }
}
