package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract's tax rules endpoint, through every layer, against a real database.
 *
 * <p>The endpoint is "reference data (no PII)", and that phrase hid three decisions this suite makes explicit: that a
 * rule's parameters and its statute travel with it, because a list of rates nobody can justify is not reference data;
 * that "active" means in force at an instant, which is a window test rather than a boolean; and that the rules read and
 * the settlement path resolve a rule by the **same condition** - asserted end to end, because two copies of a window
 * would agree everywhere except where it mattered.
 *
 * <p>Each test seeds its own rules. {@link DatabaseTest} seeds one flat rule per currency for the tests that need a
 * rule to exist at all, and a suite about which rules are in force has to control that table itself.
 */
@SpringBootTest
class ReadTaxRulesApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Built from the framework rather than annotated: Spring Boot 4 no longer ships {@code @AutoConfigureMockMvc}. */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportsTheRulesInForceWithTheirParametersAndStatute() throws Exception {
        onlyRuleIn("USD", "flat", "{\"perTransferMinor\": 250}", "2020-01-01 00:00:00+00", null, "FY2020 s.7(3)");

        rules()
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].jurisdiction").value("IN"))
                .andExpect(jsonPath("$[0].kind").value("flat"))
                // The parameters are the rule's own JSON, passed through: a rate without its number is not a rate, and
                // a `flat` rule that arrived as `"flat": true` would be a rule no reader could act on.
                .andExpect(jsonPath("$[0].params.perTransferMinor").value(250))
                .andExpect(jsonPath("$[0].sourceRef").value("FY2020 s.7(3)"))
                .andExpect(jsonPath("$[0].effectiveFrom").exists())
                // Present and null rather than absent, because the schema declares it nullable: "does this rule have
                // an end" is a question with two answers, not a key that sometimes is not there.
                .andExpect(jsonPath("$[0].effectiveTo").value(nullValue()));
    }

    @Test
    void omitsARuleWhoseWindowHasNotOpenedOrHasAlreadyClosed() throws Exception {
        onlyRuleIn("USD", "flat", "{\"perTransferMinor\": 250}", "2020-01-01 00:00:00+00", null, "in force");
        ruleIn("USD", "flat", "{\"perTransferMinor\": 100}", "2030-01-01 00:00:00+00", null, "not yet effective");
        ruleIn("USD", "flat", "{\"perTransferMinor\": 999}", "2019-01-01 00:00:00+00",
                "2019-12-31 00:00:00+00", "closed");

        // One row, not three: a window is a test rather than a column a reader interprets, and a superseded rate shown
        // as current is the one mistake this read cannot afford.
        rules()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceRef").value("in force"));
    }

    /**
     * The rules read and the settlement path ask the same question, and this is the assertion that says so.
     *
     * <p>Two rules for one currency, one closed and one in force, with different rates: the read must show the one in
     * force, and a preview over an obligation in that currency must charge the same rule's rate. The two use the same
     * condition ({@code TaxRuleWindow}), which is why a copy of it in either place would fail here rather than only in
     * production.
     */
    @Test
    void reportsTheRulesTheSettlementPathWouldActuallyUse() throws Exception {
        jdbc.execute("DELETE FROM tax_rules");

        ruleIn("INR", "flat", "{\"perTransferMinor\": 50}", "2020-01-01 00:00:00+00", null, "in force");
        ruleIn("INR", "flat", "{\"perTransferMinor\": 9999}", "2019-01-01 00:00:00+00",
                "2019-12-31 00:00:00+00", "closed");

        rules()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].params.perTransferMinor").value(50));

        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);

        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP))
                .andExpect(status().isOk())
                // The rate the rule list showed, charged by the plan: one transfer, so one flat charge of 50.
                .andExpect(jsonPath("$.partitions[0].netTax.amountMinor").value(50))
                .andExpect(jsonPath("$.partitions[0].grossTax.amountMinor").value(50));
    }

    @Test
    void aDeploymentWithNoRuleInForceAnswersAnEmptyListRatherThanFailing() throws Exception {
        jdbc.execute("DELETE FROM tax_rules");

        rules()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    private ResultActions rules() throws Exception {
        return mvc.perform(get("/v1/tax/rules"));
    }

    /** Removes the seeded rules first, so a suite about windows is not competing with the fixture's own rule. */
    private void onlyRuleIn(
            String currency, String kind, String paramsJson,
            String effectiveFrom, String effectiveTo, String sourceRef) {

        jdbc.execute("DELETE FROM tax_rules");
        ruleIn(currency, kind, paramsJson, effectiveFrom, effectiveTo, sourceRef);
    }

    /**
     * A rule row, written with SQL because inserting a rule is an administrative act rather than an API call - the
     * contract has no endpoint for it, and the port says why (who may insert a rule, and how it is validated against
     * the statute, is a governance question this build does not answer).
     */
    private void ruleIn(
            String currency, String kind, String paramsJson,
            String effectiveFrom, String effectiveTo, String sourceRef) {

        jdbc.update("""
                INSERT INTO tax_rules (id, jurisdiction, currency_code, kind, params_json,
                                       effective_from, effective_to, source_ref)
                VALUES (?, 'IN', ?, ?, ?::jsonb, ?::timestamptz, ?::timestamptz, ?)
                """,
                UUID.randomUUID(), currency, kind, paramsJson, effectiveFrom, effectiveTo, sourceRef);
    }
}
