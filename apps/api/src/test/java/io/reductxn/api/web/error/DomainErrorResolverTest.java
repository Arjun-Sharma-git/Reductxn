package io.reductxn.api.web.error;

import io.reductxn.ledger.api.CurrencyMismatchException;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of a resolver: it claims domain failures, and it declines everything else.
 *
 * <p>The second test is the one that matters. A resolver that claimed more than it should would shadow
 * Spring's own handling, and the visible symptom would be a framework error reported with the wrong
 * status - which is the sort of bug that gets blamed on the framework.
 */
class DomainErrorResolverTest {

    private final DomainErrorResolver resolver = new DomainErrorResolver(new ObjectMapper());

    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/v1/groups/1/netting/preview");

    @Test
    void answersADomainFaultWithItsCode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView handled = resolver.resolveException(
                request, response, null, new CurrencyMismatchException("INR", "JPY"));

        assertThat(handled)
                .as("non-null means handled, so Spring does not look for another resolver")
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"ledger.currency_mismatch\"")
                .contains("\"instance\":\"/v1/groups/1/netting/preview\"");
    }

    @Test
    void declinesAnythingThatIsNotADomainFailure() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView handled = resolver.resolveException(
                request, response, null, new IllegalStateException("not a domain failure"));

        assertThat(handled)
                .as("null means declined, which is what preserves a framework error's own status: an "
                        + "unparseable group id must stay a 400 rather than becoming a 500")
                .isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
