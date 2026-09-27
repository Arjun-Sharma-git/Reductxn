package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/webhooks/rails/{provider}}, through every layer, against a real database.
 *
 * <p><strong>The only boundary here with no session, and the tests are shaped by that.</strong> What authenticates a
 * callback is a signature over the bytes of its body, so this class computes that signature itself - with
 * {@code javax.crypto} rather than by calling the production verifier, because a test that asked the signer whether its
 * own signature was good would prove nothing. Where the deployment's secret comes from is a property, so the test can
 * say what "the right secret" is and can also present a wrong one.
 *
 * <p><strong>What it can assert is the record, and only the record.</strong> The transfer this answer is about is moved
 * by {@code workers/settlement}'s driver on its next pass, so "the money moved" is not a claim this class can make;
 * {@code DriveRunsTest} makes it, from the other side of the same row.
 */
@SpringBootTest(properties = "reductxn.rails.webhook-secret=test-secret")
class RailWebhookApiTest extends DatabaseTest {

    private static final String SECRET = "test-secret";
    private static final String REF = "run-7-transfer-1";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aSignedCallbackIsRecordedWhereTheWorkerReadsIt() throws Exception {
        String body = callback("confirmed", "settled at 14:02");

        deliver("mock", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.railRef").value(REF))
                .andExpect(jsonPath("$.state").value("confirmed"))
                .andExpect(jsonPath("$.receivedAt").isNotEmpty());

        // The receipt is the row: `state` and `detail` are what the driver reads, and the detail is the rail's own
        // words rather than anything this deployment derived.
        assertThat(stored("state")).isEqualTo("confirmed");
        assertThat(stored("detail")).isEqualTo("settled at 14:02");
    }

    /**
     * A redelivery replaces the rail's word rather than adding a second one, which is the promised idempotency.
     *
     * <p>Rails retry callbacks, so the same reference arriving twice is the normal case rather than an edge one - and
     * "how many opinions does this deployment hold about one transfer" is the question the assertion answers.
     */
    @Test
    void aRedeliveryReplacesTheRailsWordRatherThanAddingOne() throws Exception {
        deliver("mock", callback("accepted", "at the rail")).andExpect(status().isAccepted());
        deliver("mock", callback("confirmed", "settled")).andExpect(status().isAccepted());

        assertThat(stored("state")).isEqualTo("confirmed");
        assertThat(stored("detail")).isEqualTo("settled");
        assertThat(rowsForTheReference()).isEqualTo(1);
    }

    /** A caller that has no secret gets nothing, and writes nothing, whichever way it gets the signature wrong. */
    @Test
    void aCallbackThatDoesNotVerifyIsRefusedAndNothingIsWritten() throws Exception {
        String body = callback("confirmed", "settled");

        mvc.perform(post("/v1/webhooks/rails/{provider}", "mock").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.invalid_signature"));

        mvc.perform(post("/v1/webhooks/rails/{provider}", "mock")
                        .header("X-Rail-Signature", sign(body, "not-the-secret"))
                        .content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/v1/webhooks/rails/{provider}", "mock")
                        .header("X-Rail-Signature", sign(body, SECRET))
                        .content(body.replace("confirmed", "failed")))
                .andExpect(status().isUnauthorized());

        assertThat(rowsForTheReference()).as("nothing was recorded for any of the three").isZero();
    }

    /** A signed callback this build cannot act on is a `400` naming the field, and it is not stored. */
    @Test
    void aSignedCallbackWithAStateThisBuildDoesNotKnowIsRefused() throws Exception {
        deliver("mock", callback("settled", "the rail's word for something this build has not been taught"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.callback_not_understood"));

        assertThat(rowsForTheReference()).isZero();
    }

    /**
     * The order is observable, and this is the test that observes it: the same malformed body is a `400` when it is
     * signed and a `401` when it is not.
     *
     * <p>That is the whole reason the controller takes bytes rather than a bound DTO. If the body were bound first, an
     * unverified body would be parsed and reported on - and a request this deployment does not trust would be answering
     * questions about its own contents.
     */
    @Test
    void anUnverifiedBodyIsNeverParsed() throws Exception {
        String notJson = "this is not json at all";

        mvc.perform(post("/v1/webhooks/rails/{provider}", "mock")
                        .header("X-Rail-Signature", sign(notJson, SECRET))
                        .content(notJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.callback_not_understood"));

        mvc.perform(post("/v1/webhooks/rails/{provider}", "mock")
                        .header("X-Rail-Signature", sign(notJson, "not-the-secret"))
                        .content(notJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.invalid_signature"));
    }

    private ResultActions deliver(String provider, String body) throws Exception {
        return mvc.perform(post("/v1/webhooks/rails/{provider}", provider)
                .header("X-Rail-Signature", sign(body, SECRET))
                .content(body));
    }

    /** The callback body the contract declares, with a fixed reference so the assertions can name the row. */
    private static String callback(String state, String detail) {
        return "{\"railRef\":\"" + REF + "\",\"state\":\"" + state + "\",\"detail\":\"" + detail + "\"}";
    }

    /** What a rail signs: HMAC-SHA256 over the exact bytes, in the format the contract promises. */
    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String stored(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM settlement_rail_callbacks WHERE rail_ref = ?",
                String.class, REF);
    }

    private int rowsForTheReference() {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_rail_callbacks WHERE rail_ref = ?",
                Integer.class, REF);
    }
}
