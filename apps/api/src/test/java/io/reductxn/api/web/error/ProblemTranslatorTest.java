package io.reductxn.api.web.error;

import io.reductxn.kernel.api.DomainError;
import io.reductxn.ledger.api.CurrencyMismatchException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RULE B20 at the boundary: what a client receives when something fails.
 *
 * <p>The rule is "the API never returns localized prose". What can be asserted is narrower and still the
 * right thing: the response carries a stable well-formed code, and it carries nothing a client should
 * render. The ArchUnit rule {@code b20_api_cannot_localise} covers the other half - that the module has
 * no locale or message source to compose prose with in the first place.
 */
class ProblemTranslatorTest {

    @Test
    void carriesTheStableCodeAndNothingAClientShouldRender() {
        ProblemDetail problem = ProblemTranslator.toProblem(
                new CurrencyMismatchException("INR", "JPY"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "/v1/groups/1/netting/preview");

        assertThat(problem.getProperties())
                .as("the code is the client's contract, and it is the domain's code rather than one "
                        + "written in the web layer")
                .containsEntry("code", "ledger.currency_mismatch");
        assertThat(problem.getType())
                .as("about:blank says no specific type is registered, which is true; a URI that "
                        + "resolves to nothing would look more precise while being less honest")
                .isEqualTo(URI.create("about:blank"));
        assertThat(problem.getTitle())
                .as("title and detail are developer text for logs, and the contract says so")
                .isEqualTo("Internal Server Error");
        assertThat(problem.getInstance()).isEqualTo(URI.create("/v1/groups/1/netting/preview"));
    }

    @Test
    void refusesToEmitAMalformedCode() {
        DomainError malformed = new DomainError() {

            @Override
            public String code() {
                return "CURRENCY_MISMATCH";
            }

            @Override
            public String message() {
                return "unnamespaced, and therefore unattributable and unlocalizable";
            }
        };

        assertThatThrownBy(() -> ProblemTranslator.toProblem(
                malformed, HttpStatus.INTERNAL_SERVER_ERROR, "/v1/groups/1/netting/preview"))
                .as("an un-namespaced code cannot be attributed to a module or enumerated by the "
                        + "error-code parity gate, so it must not reach a client that would have to "
                        + "match on prose instead")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
