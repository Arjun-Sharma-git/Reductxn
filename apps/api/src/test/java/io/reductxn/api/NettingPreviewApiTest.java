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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract's preview endpoint, through every layer, against a real database.
 *
 * <p>These tests have now run in two stages, and the difference is the point. They began over in-memory
 * doubles, when no store existed: they proved the mapping and the layering, and could not prove that
 * anything was persisted. Rows are now written with SQL into a container and read back through jOOQ, so a
 * broken migration, a misnamed column and a timezone mistake all fail here rather than in an environment.
 *
 * <p>The assertions are on the wire format the contract defines, which is why they survived both stages
 * unchanged - the shape a client sees was never the part that was provisional.
 */
@SpringBootTest
class NettingPreviewApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);

    /** The flat charge per transfer the fixture seeds, in minor units of whichever currency is assessed. */
    private static final long FLAT_TAX_MINOR = 50L;

    private static final String AS_OF = "2026-09-25T10:00:00Z";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /**
     * Built from the framework rather than annotated, because Spring Boot 4 no longer ships
     * {@code @AutoConfigureMockMvc} - it is not in any of the module's artifacts. This goes through the
     * same {@code WebApplicationContext} the application uses, so the controller, the resolver and the
     * problem-details advice are all the real ones.
     */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void previewsASingleCurrencyGroupInTheContractShape() throws Exception {
        // Two opposing obligations net to one transfer of 6,000. Pairwise settlement would have been two
        // transfers, so with a flat per-transfer tax the bill falls from 100 to 50.
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, BORIS, ALICE, 4_000L, "INR", 2);

        preview(GROUP)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.grossTax.amountMinor").value(2 * FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.netTax.amountMinor").value(FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.taxSaved.amountMinor").value(FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.partitions", hasSize(1)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[0].grossTransferCount").value(2))
                .andExpect(jsonPath("$.partitions[0].netTransferCount").value(1))
                .andExpect(jsonPath("$.partitions[0].algorithm").value("greedy"))
                .andExpect(jsonPath("$.bestCase.positions", hasSize(2)))
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(1)))
                .andExpect(jsonPath("$.bestCase.transfers[0].fromUserId").value(ALICE.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toUserId").value(BORIS.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toAccountType").value("member"))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(6_000L))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[0].status").value("pending"))
                // Absent rather than zero: collapsing currencies needs a reporting rate (ADR 0012 point 7),
                // and a zero here would read as "this plan is free".
                .andExpect(jsonPath("$.reportingTotals").doesNotExist())
                .andExpect(jsonPath("$.sameCurrencyAlternative").doesNotExist());
    }

    @Test
    void aMulticurrencyGroupReportsPartitionsRatherThanChoosingACurrency() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, ALICE, BORIS, 10_000L, "JPY", 0);

        preview(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(2)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[1].currency").value("JPY"))
                // The single-currency fields are absent rather than collapsed into one of the two
                // currencies, which is the choice ADR 0012 refuses to make silently.
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.taxSaved").doesNotExist())
                // The detail view still carries every leg, each with its own currency, so no base
                // currency has to be chosen to read it.
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(2)))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[1].amount.currency").value("JPY"));
    }

    @Test
    void aGroupWithNothingOpenPreviewsAnEmptyPlanRatherThanFailing() throws Exception {
        preview(new UUID(0L, 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(0)))
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(0)))
                .andExpect(jsonPath("$.bestCase.positions", hasSize(0)))
                // No plan means no optimality claim to make, so the flag is absent rather than true: an
                // empty set does not get to be provably optimal.
                .andExpect(jsonPath("$.provablyOptimal").doesNotExist());
    }

    @Test
    void frameworkErrorsAreProblemsToo() throws Exception {
        // A group id that is not a UUID never reaches the controller. Without
        // `spring.mvc.problemdetails.enabled` this is an empty body with a 400, which a client cannot
        // branch on - so this asserts a configuration line, not a framework behaviour.
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void anObligationVoidedAfterTheInstantWasStillOpenAtIt() throws Exception {
        // Voided on 20 September. At 10:00 on 15 September it was open, so a preview of that instant must
        // still see it: the filter is `voided_at > asOf`, not `voided_at IS NULL`. A preview that dropped it
        // would understate a past cycle's cost by exactly what has been voided since, which is the kind of
        // error that never looks like an error.
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        voidObligationOf(ALICE, "2026-09-20 00:00:00+00");

        preview(GROUP, "2026-09-15T10:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(1)))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(10_000L));

        // And at the later instant it is gone, which is what makes the first half a claim about time rather
        // than about voiding being ignored.
        preview(GROUP, AS_OF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(0)))
                .andExpect(jsonPath("$.partitions", hasSize(0)));
    }

    private ResultActions preview(UUID groupId) throws Exception {
        return preview(groupId, AS_OF);
    }

    private ResultActions preview(UUID groupId, String asOf) throws Exception {
        return mvc.perform(get("/v1/groups/{groupId}/netting/preview", groupId).param("asOf", asOf));
    }
}
