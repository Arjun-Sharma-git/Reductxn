package io.reductxn.api;

import io.reductxn.api.adapter.feed.EcbFeedDocument;
import io.reductxn.api.adapter.feed.EcbReferenceRateFeed;
import io.reductxn.api.port.in.IngestRates;
import io.reductxn.api.port.in.RateIngestion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate ingestion against a real database, and the obligation it makes possible.
 *
 * <p>The claim being tested is not "rows appear in a table". It is the one this feature exists for: before it,
 * every cross-currency obligation was refused because nothing wrote {@code fx_rates} - and the test therefore
 * records the refusal first, ingests the bank's own document from a file, and records the same commitment again
 * through the HTTP API. Both halves are the point: a fail-closed path that was never fail-closed is not a
 * fail-closed path, and a rate that is stored but not read is a row.
 */
@SpringBootTest
class RateIngestionApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 3L);
    private static final UUID ALICE = new UUID(0L, 30L);
    private static final UUID BORIS = new UUID(0L, 31L);

    /** Inside the publication's window: quoted 14:00 UTC on 25 September 2026, and usable for four days. */
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-25T15:00:00Z");

    /** 50,000 JPY, the same amount the spec's own worked example converts - and 0 exponent, so 50000 units. */
    private static final long FIFTY_THOUSAND_YEN = 50_000L;

    private static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";

    private static final String CALLER_PHONE = "+919812345678";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private IngestRates ingestRates;

    private MockMvc mvc;

    private String caller;
    private UUID callerId;

    private Path feedFile;

    @BeforeEach
    void writeTheFeedToAFile() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        // The file form of the feed, which is the form a deployment without outbound access uses and the form
        // that makes this test independent of the bank being up.
        feedFile = Files.createTempFile("eurofxref-daily", ".xml");
        Files.writeString(feedFile, EcbFeedDocument.DAILY);
    }

    /**
     * A signed-in member of the group, because a member of the group is who may write to its books (ADR 0049).
     *
     * <p>The membership is written as a row: it is a precondition of the requests these tests make rather than their
     * subject, and the tests that assert the rule itself belong to the classes that own each write.
     */
    @BeforeEach
    void signInACaller() {
        caller = signedIn(CALLER_PHONE);
        callerId = user(CALLER_PHONE);
        memberOf(GROUP);
    }

    /** A membership as a row, because {@code groups.isMember} reads exactly this. */
    private void memberOf(UUID group) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, 'Caller', 'admin', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, group, callerId);
    }

    @Test
    void aCrossCurrencyObligationIsRefusedUntilARateIsIngestedAndAcceptedAfterwards() throws Exception {
        obligateInYenSettledInRupees("before-the-feed")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("fx.rate_unavailable"));

        RateIngestion report = ingestRates.ingest(new EcbReferenceRateFeed(feedFile.toString())).orElseThrow();

        assertThat(report.pairsOffered()).isEqualTo(6);
        assertThat(report.pairsWritten()).isEqualTo(6);
        assertThat(report.unquotedCurrencies()).isEmpty();

        // 109.2605 / 179.70 = 0.60801614, applied to 50,000 yen: 30,400.807 rupees, rounded to 30,401 minor
        // units, with the 0.193 of a rupee that rounding created carried as the residual.
        obligateInYenSettledInRupees("after-the-feed")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settleInCurrency").value("INR"))
                .andExpect(jsonPath("$.fxQuote.rate.base").value("JPY"))
                .andExpect(jsonPath("$.fxQuote.rate.quote").value("INR"))
                .andExpect(jsonPath("$.fxQuote.rate.rateScaled").value(60_801_614L))
                .andExpect(jsonPath("$.fxQuote.rate.rateScale").value(8))
                .andExpect(jsonPath("$.fxQuote.rate.source").value("ecb-cross"))
                .andExpect(jsonPath("$.fxQuote.convertedAmount.amountMinor").value(3_040_081L))
                .andExpect(jsonPath("$.fxQuote.convertedAmount.currency").value("INR"))
                // 50,000 yen is 30,400.807 rupees, which is 3,040,080.7 minor units: the conversion rounds to
                // 3,040,081 and carries the 0.3 of a minor unit it created as the residual, reduced to 3/10.
                .andExpect(jsonPath("$.fxQuote.residualScaled").value(-3L))
                .andExpect(jsonPath("$.fxQuote.residualScale").value(1));

        assertThat(quoteCount())
                .as("the refused attempt locked nothing, and the accepted one locked exactly one conversion")
                .isEqualTo(1);
    }

    @Test
    void aSecondRefreshOfTheSamePublicationStoresNothingAndSaysSo() {
        EcbReferenceRateFeed feed = new EcbReferenceRateFeed(feedFile.toString());

        RateIngestion first = ingestRates.ingest(feed).orElseThrow();
        RateIngestion second = ingestRates.ingest(feed).orElseThrow();

        assertThat(first.pairsWritten()).isEqualTo(6);
        assertThat(second.pairsWritten())
                .as("the second pass offered the same six rates and the database accepted none of them")
                .isZero();
        assertThat(rateCount())
                .as("a refresh that runs twice - a restart, an overlap, an operator - does not duplicate the "
                        + "market data every reader then has to resolve by recency")
                .isEqualTo(6);
    }

    @Test
    void storesThePublicationInstantAndTheWindowTheDeploymentAllows() {
        ingestRates.ingest(new EcbReferenceRateFeed(feedFile.toString())).orElseThrow();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fx_rates
                WHERE source = 'ecb-cross'
                  AND quoted_at = TIMESTAMPTZ '2026-09-25 14:00:00+00'
                  AND expires_at = TIMESTAMPTZ '2026-09-29 14:00:00+00'
                  AND spread_bps = 0
                  AND mid_rate_scaled = rate_scaled
                """, Integer.class))
                .as("six cross rates, quoted when the bank published them and expiring four days later - the "
                        + "default that lets Friday's rate carry a weekend")
                .isEqualTo(6);
    }

    @Test
    void aCurrencyThisDeploymentTransactsButTheBankDoesNotQuoteIsReportedRatherThanSilent() throws Exception {
        jdbc.execute(currencyInTheCatalogue("RUB"));

        RateIngestion report = ingestRates.ingest(new EcbReferenceRateFeed(feedFile.toString())).orElseThrow();

        assertThat(report.unquotedCurrencies()).containsExactly("RUB");
        assertThat(report.pairsOffered())
                .as("no pair involving the rouble can be derived, so none is stored")
                .isEqualTo(6);

        // And the consequence, end to end: an obligation in roubles settled in rupees has no rate and is
        // refused rather than converted at a guess. The report is what tells an operator why.
        org.springframework.test.web.servlet.ResultActions refused = mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", "rouble")
                .header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "groupId": "%s",
                          "debtorId": "%s",
                          "creditorId": "%s",
                          "amount": { "amountMinor": 100000, "currency": "RUB" },
                          "settleInCurrency": "INR",
                          "memo": "a rouble commitment, from a provider that stopped pricing it",
                          "occurredAt": "%s"
                        }
                        """.formatted(GROUP, ALICE, BORIS, OCCURRED_AT)));

        refused.andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("fx.rate_unavailable"));

        assertThat(quoteCount()).as("a refused conversion locks nothing").isZero();
    }

    @Test
    void theEurosOwnPairsAppearOnlyWhenTheDeploymentKnowsTheEuro() throws Exception {
        assertThat(quotedCodes("EUR"))
                .as("the catalogue holds INR, JPY and USD, so the euro is a code the feed quotes and this "
                        + "deployment does not know")
                .isZero();

        jdbc.execute(currencyInTheCatalogue("EUR"));

        ingestRates.ingest(new EcbReferenceRateFeed(feedFile.toString())).orElseThrow();

        assertThat(quotedCodes("EUR"))
                .as("adding a currency is an insert rather than a release, and the next refresh prices its "
                        + "pairs")
                .isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fx_rates WHERE base_code = 'EUR' AND source = 'ecb'", Integer.class))
                .as("a pair quoted against the publication's own base carries the provider's name, because it "
                        + "is the provider's own number")
                .isEqualTo(3);
    }

    /** Two of the fixtures add a currency to the catalogue, which is global: put it back after each test. */
    @AfterEach
    void forgetTheCurrenciesThisAdded() {
        jdbc.execute("DELETE FROM currencies WHERE code IN ('EUR', 'RUB')");
    }

    private static String currencyInTheCatalogue(String code) {
        return "INSERT INTO currencies (code, minor_unit_exponent, is_cbdc) VALUES ('" + code
                + "', 2, false) ON CONFLICT (code) DO NOTHING";
    }

    // --- the fixture's helpers --------------------------------------------------------------------

    /** A commitment of yen settled in rupees: the pair the provider publishes no rate for. */
    private org.springframework.test.web.servlet.ResultActions obligateInYenSettledInRupees(String key)
            throws Exception {

        return mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", key)
                .header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "groupId": "%s",
                          "debtorId": "%s",
                          "creditorId": "%s",
                          "amount": { "amountMinor": %d, "currency": "JPY" },
                          "settleInCurrency": "INR",
                          "memo": "lunch",
                          "occurredAt": "%s"
                        }
                        """.formatted(GROUP, ALICE, BORIS, FIFTY_THOUSAND_YEN, OCCURRED_AT)));
    }

    private int rateCount() {
        return jdbc.queryForObject("SELECT count(*) FROM fx_rates", Integer.class);
    }

    private int quoteCount() {
        return jdbc.queryForObject("SELECT count(*) FROM fx_quotes", Integer.class);
    }

    private int quotedCodes(String code) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM fx_rates WHERE base_code = ? OR quote_code = ?", Integer.class,
                code, code);
    }
}
