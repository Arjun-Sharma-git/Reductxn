package io.reductxn.api;

import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryChain;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recording an obligation: the first write path in the product, through every layer, against a real database.
 *
 * <p><strong>These tests verify the ledger, they do not inspect it.</strong> The rows are read back and
 * rebuilt as {@link JournalEntry} objects, and then handed to the verifiers an offline job would run -
 * {@link EntryChain} for the hash chain and {@link Balances} for the positions. Constructing a
 * {@code JournalEntry} at all is itself a check, because the type refuses to exist unbalanced (L1) or in mixed
 * currencies (L6). So the assertions are about invariants rather than about a column's contents, and a change
 * to the schema that broke one would fail here rather than in an audit.
 *
 * <p>The chain is walked rather than ordered by {@code occurred_at}: an entry's position is its
 * {@code prev_hash}, and ordering by time agrees with the walk only while commitments happen to be recorded
 * in the order they occurred - the assumption {@code JooqChainHeads} refuses to make. A helper that leaned on
 * it would stop being a check on the chain and become a check on the fixture.
 */
@SpringBootTest
class RecordObligationApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 2L);
    private static final UUID ALICE = new UUID(0L, 20L);
    private static final UUID BORIS = new UUID(0L, 21L);

    private static final String KEY = "obligation-1";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void recordsAnObligationAndBooksItToTheLedger() throws Exception {
        record(GROUP, ALICE, BORIS, 12_500L, "INR", KEY)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.amount.amountMinor").value(12_500L))
                .andExpect(jsonPath("$.amount.currency").value("INR"))
                // Present even though it equals the debt currency: the contract makes it optional and it is
                // more useful stated, because it is what the creditor is to be paid.
                .andExpect(jsonPath("$.settleInCurrency").value("INR"))
                .andExpect(jsonPath("$.memo").value("lunch"))
                .andExpect(jsonPath("$.status").value("open"));

        List<JournalEntry> chain = chainInOrder(GROUP, "INR");
        assertThat(chain)
                .as("one commitment books exactly one entry")
                .hasSize(1);

        JournalEntry entry = chain.get(0);
        assertThat(entry.kind()).isEqualTo(EntryKind.OBLIGATION);
        assertThat(entry.prevHash()).as("the first entry of a chain has no predecessor").isNull();
        assertThat(entry.isFxLinked()).as("nothing was converted, so nothing is linked").isFalse();
        assertThat(entry.idempotencyKey()).isEqualTo(KEY);

        assertThat(EntryChain.problemsIn(chain)).as("the stored chain verifies").isEmpty();

        // The direction convention, measured rather than assumed: the creditor holds the claim, so the
        // creditor is debited and the debtor's position is negative - which reads as "this member owes".
        Currency inr = Currency.of("INR", 2);
        assertThat(Balances.netDebitOf(ALICE, inr, entry.postings()))
                .as("the debtor holds the obligation")
                .isEqualTo(Money.of(-12_500L, inr));
        assertThat(Balances.netDebitOf(BORIS, inr, entry.postings()))
                .as("the creditor holds the claim")
                .isEqualTo(Money.of(12_500L, inr));
    }

    @Test
    void aSecondObligationJoinsTheChainRatherThanStartingANewOne() throws Exception {
        record(GROUP, ALICE, BORIS, 12_500L, "INR", "key-a").andExpect(status().isCreated());
        record(GROUP, BORIS, ALICE, 2_500L, "INR", "key-b").andExpect(status().isCreated());

        List<JournalEntry> chain = chainInOrder(GROUP, "INR");
        assertThat(storedEntries(GROUP, "INR"))
                .as("both entries are stored, so a shorter chain below would be a broken link rather than a "
                        + "missing row")
                .hasSize(2);
        assertThat(chain).hasSize(2);

        assertThat(chain.get(1).prevHash())
                .as("the second entry references the first entry's hash")
                .isEqualTo(chain.get(0).hash());

        // The point of a chain: a pair of valid entries is not the claim, a verified chain is.
        assertThat(EntryChain.problemsIn(chain)).isEmpty();
    }

    @Test
    void eachCurrencyIsItsOwnChainSoAGroupCanHaveSeveral() throws Exception {
        record(GROUP, ALICE, BORIS, 12_500L, "INR", "key-inr").andExpect(status().isCreated());
        record(GROUP, ALICE, BORIS, 900L, "JPY", "key-jpy").andExpect(status().isCreated());

        assertThat(chainInOrder(GROUP, "INR")).hasSize(1);
        assertThat(chainInOrder(GROUP, "JPY")).hasSize(1);

        // Both are genesis entries, because a chain is per (group, currency) - and the database allows
        // exactly one genesis per chain, which is what makes this a fact rather than a hope.
        assertThat(chainInOrder(GROUP, "JPY").get(0).prevHash()).isNull();
        assertThat(chainInOrder(GROUP, "JPY").get(0).currency()).isEqualTo(Currency.of("JPY", 0));
    }

    @Test
    void aReplayedIdempotencyKeyAnswersWithTheSameObligationAndBooksNothing() throws Exception {
        String first = record(GROUP, ALICE, BORIS, 12_500L, "INR", "key-replayed")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // The same request again, as a client that never saw the first response would send it.
        record(GROUP, ALICE, BORIS, 12_500L, "INR", "key-replayed")
                .andExpect(status().isCreated())
                .andExpect(content().json(first));

        assertThat(obligationCount()).as("the retry recorded nothing").isEqualTo(1);
        assertThat(chainInOrder(GROUP, "INR"))
                .as("and booked nothing, which is the half that cannot be undone")
                .hasSize(1);
    }

    @Test
    void aDifferentGroupRecordingTheSameKeyIsADifferentObligation() throws Exception {
        UUID otherGroup = new UUID(0L, 3L);

        record(GROUP, ALICE, BORIS, 12_500L, "INR", "batch-7").andExpect(status().isCreated());
        record(otherGroup, ALICE, BORIS, 12_500L, "INR", "batch-7").andExpect(status().isCreated());

        // A key is the client's own string, so two groups using it are describing two operations.
        assertThat(obligationCount()).isEqualTo(2);
        assertThat(chainInOrder(otherGroup, "INR")).hasSize(1);
    }
    @Test
    void refusesAMemberOwingThemselves() throws Exception {
        record(GROUP, ALICE, ALICE, 12_500L, "INR", "key-self")
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("api.obligation_same_party"));

        assertThat(obligationCount()).as("a refused request records nothing").isZero();
        assertThat(chainInOrder(GROUP, "INR")).as("and books nothing").isEmpty();
    }

    @Test
    void refusesAnAmountThatCannotBePosted() throws Exception {
        // A commitment of nothing is not an obligation, and a zero posting cannot exist (L2) - so the
        // refusal is about the commitment rather than about the size of a number.
        record(GROUP, ALICE, BORIS, 0L, "INR", "key-zero")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("api.obligation_amount_not_positive"));

        assertThat(obligationCount()).isZero();
        assertThat(chainInOrder(GROUP, "INR")).isEmpty();
    }

    @Test
    void refusesACurrencyTheCatalogueDoesNotHave() throws Exception {
        // No exponent means no exact amount, so this is refused rather than defaulted to two digits - which
        // would be a guess about how much money the obligation is for (P1).
        record(GROUP, ALICE, BORIS, 12_500L, "XYZ", "key-unknown")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("api.obligation_unknown_currency"));

        assertThat(obligationCount()).isZero();
    }

    @Test
    void aCrossCurrencyRequestFailsClosedBecauseNoRateSourceIsConfigured() throws Exception {
        // 503 rather than 422, and the difference matters: this request would succeed if a rate were
        // available, so the client should retry rather than fix its request. It is also the whole of
        // "no rate means no cross-currency" (MASTER-PLAN section 4).
        recordSettlingIn(GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", "key-cross")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("fx.rate_unavailable"));

        assertThat(obligationCount())
                .as("nothing is recorded, so no obligation exists in the debt currency only - which the "
                        + "preview would read back as a single-currency obligation")
                .isZero();
        assertThat(chainInOrder(GROUP, "INR")).isEmpty();
    }

    @Test
    void refusesAMissingIdempotencyKey() throws Exception {
        mvc.perform(post("/v1/obligations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GROUP, ALICE, BORIS, 12_500L, "INR", "lunch")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.obligation_request_incomplete"));

        assertThat(obligationCount()).isZero();
    }

    @Test
    void refusesAMemoLongerThanTheContractAllows() throws Exception {
        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "key-long-memo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GROUP, ALICE, BORIS, 12_500L, "INR", "x".repeat(281))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.obligation_memo_too_long"));

        assertThat(obligationCount()).isZero();
    }

    @Test
    void theRecordedObligationIsWhatThePreviewThenSees() throws Exception {
        // Recorded with an explicit occurredAt, because the contract makes it optional and a test that relied
        // on the clock would be asserting against whatever second it ran in.
        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "key-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupId": "%s",
                                  "debtorId": "%s",
                                  "creditorId": "%s",
                                  "amount": { "amountMinor": 12500, "currency": "INR" },
                                  "memo": "lunch",
                                  "occurredAt": "2026-09-01T00:00:00Z"
                                }
                                """.formatted(GROUP, ALICE, BORIS)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.occurredAt").value("2026-09-01T00:00:00Z"));

        // The write path and the read path agreeing is not a formality: the preview reads the same table
        // through a different adapter, and a column either of them got wrong would show up here as a group
        // that appears to owe nothing.
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP)
                        .param("asOf", "2026-09-25T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(12_500L))
                .andExpect(jsonPath("$.bestCase.transfers[0].fromUserId").value(ALICE.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toUserId").value(BORIS.toString()));
    }


    private ResultActions record(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency, String key)
            throws Exception {

        return mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(group, debtor, creditor, amountMinor, currency, "lunch")));
    }

    private ResultActions recordSettlingIn(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency,
            String settleInCurrency, String key) throws Exception {

        return mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "groupId": "%s",
                          "debtorId": "%s",
                          "creditorId": "%s",
                          "amount": { "amountMinor": %d, "currency": "%s" },
                          "settleInCurrency": "%s",
                          "memo": "lunch"
                        }
                        """.formatted(group, debtor, creditor, amountMinor, currency, settleInCurrency)));
    }

    private static String body(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency, String memo) {

        return """
                {
                  "groupId": "%s",
                  "debtorId": "%s",
                  "creditorId": "%s",
                  "amount": { "amountMinor": %d, "currency": "%s" },
                  "memo": "%s"
                }
                """.formatted(group, debtor, creditor, amountMinor, currency, memo);
    }

    private int obligationCount() {
        return jdbc.queryForObject("SELECT count(*) FROM obligations", Integer.class);
    }

    /**
     * The chain for one group and currency, walked from its genesis along {@code prev_hash}.
     *
     * <p>Walked rather than ordered by time, so this helper cannot agree with the adapter by making the same
     * mistaken assumption. If a link is missing the walk is shorter than the rows, and the size assertions in
     * the tests above are what notice.
     */
    private List<JournalEntry> chainInOrder(UUID group, String currency) {
        JournalEntry genesis = null;
        Map<String, JournalEntry> byPredecessor = new HashMap<>();

        for (JournalEntry entry : storedEntries(group, currency)) {
            if (entry.prevHash() == null) {
                genesis = entry;
            } else {
                byPredecessor.put(entry.prevHash(), entry);
            }
        }

        List<JournalEntry> chain = new ArrayList<>();
        for (JournalEntry current = genesis; current != null; current = byPredecessor.get(current.hash())) {
            chain.add(current);
        }

        return chain;
    }

    private List<JournalEntry> storedEntries(UUID group, String currency) {
        return jdbc.query("""
                SELECT id, group_id, currency_code, currency_exponent, occurred_at, kind, created_by,
                       idempotency_key, fx_link_id, fx_role, rail_performed_fx, prev_hash
                FROM journal_entries
                WHERE group_id = ? AND currency_code = ?
                """, (row, index) -> storedEntry(row), group, currency);
    }

    /**
     * One stored entry as the ledger's own type.
     *
     * <p>Constructing it is part of the check: {@code JournalEntry} refuses an unbalanced entry (L1) and one
     * whose postings are in another currency (L6), so a row that got past the constraints and still broke
     * either invariant fails here rather than being compared field by field and passing.
     *
     * <p>{@code kind} and {@code fx_role} are derived from the stored text here, unlike in the adapter, where
     * an exhaustive switch is what stops a value the CHECK constraints would reject. In a test the direction
     * is reversed - the stored text is the subject, so deriving from it is the point, and an unmapped value
     * becomes a failure rather than something to map.
     */
    private JournalEntry storedEntry(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        String role = row.getString("fx_role");

        return new JournalEntry(
                id,
                row.getObject("group_id", UUID.class),
                Currency.of(row.getString("currency_code"), row.getInt("currency_exponent")),
                // OffsetDateTime rather than Timestamp: a timestamptz read as a Timestamp is interpreted in
                // the JVM's zone, which is the class of mistake the read models in this module avoid on
                // purpose.
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                EntryKind.valueOf(row.getString("kind").toUpperCase(Locale.ROOT)),
                row.getObject("created_by", UUID.class),
                row.getString("idempotency_key"),
                row.getObject("fx_link_id", UUID.class),
                role == null ? null : FxRole.valueOf(role.toUpperCase(Locale.ROOT)),
                row.getBoolean("rail_performed_fx"),
                storedPostings(id),
                row.getString("prev_hash"));
    }

    private List<Posting> storedPostings(UUID entryId) {
        return jdbc.query("""
                SELECT account_id, direction, amount_minor, currency_code, currency_exponent
                FROM postings
                WHERE entry_id = ?
                ORDER BY position
                """, (row, index) -> new Posting(
                        row.getObject("account_id", UUID.class),
                        "D".equals(row.getString("direction")) ? Direction.DEBIT : Direction.CREDIT,
                        Money.of(row.getLong("amount_minor"),
                                Currency.of(row.getString("currency_code"),
                                        row.getInt("currency_exponent")))),
                entryId);
    }


}
