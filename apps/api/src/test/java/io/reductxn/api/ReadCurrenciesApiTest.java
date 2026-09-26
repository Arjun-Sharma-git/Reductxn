package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The currency catalogue, through every layer.
 *
 * <p><strong>The second of the three reads ADR 0035's audit found, and the one whose absence was hardest to see.</strong>
 * The client's catalogue query is what every amount on every screen resolves its exponent from - the contract says
 * so - so a failed read here does not show a uuid instead of a name, it stops money rendering anywhere. Every screen
 * that shows an amount was affected and no suite could tell: the client stubs the API from the contract's own types.
 */
@SpringBootTest
class ReadCurrenciesApiTest extends DatabaseTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void servesEachCurrencyWithTheExponentItsAmountsAreWrittenIn() throws Exception {
        // JPY has no minor unit and INR has two, which is the whole point of the endpoint: a client that assumed two
        // would render a yen amount a hundred times too large, and P1 exists to make that impossible.
        mvc.perform(get("/v1/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'INR')].minorUnitExponent").value(2))
                .andExpect(jsonPath("$[?(@.code == 'JPY')].minorUnitExponent").value(0))
                .andExpect(jsonPath("$[?(@.code == 'USD')].minorUnitExponent").value(2));
    }

    @Test
    void isOrderedByCodeRatherThanByWhenTheRowWasWritten() throws Exception {
        // The catalogue's port answers a `Set`, whose iteration order is a container detail. A currency that sorts
        // before every seeded one is inserted here, so an implementation that served insertion order fails this.
        jdbc.update("INSERT INTO currencies (code, minor_unit_exponent, is_cbdc) VALUES ('AED', 2, false)");

        mvc.perform(get("/v1/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("AED"))
                .andExpect(jsonPath("$[1].code").value("INR"))
                .andExpect(jsonPath("$[2].code").value("JPY"))
                .andExpect(jsonPath("$[3].code").value("USD"));
    }

    @Test
    void servesTheTwoFieldsAClientNeedsAndNotTheOnesNothingConsumes() throws Exception {
        // `is_cbdc` and `jurisdiction` are columns and are deliberately not served: no screen consumes either, and a
        // field nobody consumes is a field nobody notices is wrong. Pinned because an omission is a decision, and a
        // decision nothing asserts is a decision that quietly reverses.
        mvc.perform(get("/v1/currencies"))
                .andExpect(jsonPath("$[0].isCbdc").doesNotExist())
                .andExpect(jsonPath("$[0].jurisdiction").doesNotExist());
    }
}
