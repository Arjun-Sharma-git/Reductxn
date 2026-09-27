package io.reductxn.api;

import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryChain;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxLinkage;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;
import io.reductxn.planning.api.FxClearing;

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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
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

    /** A rate's window, and a commitment inside it. Fixed instants, so nothing here depends on the clock. */
    private static final Instant QUOTED_AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-24T10:01:00Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-24T10:00:30Z");

    private static final String CALLER_PHONE = "+919812345678";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private String caller;
    private UUID callerId;


    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
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

        // The caller is in that group too: recording is a member's act in whichever group the commitment names (ADR 0049).
        memberOf(otherGroup);

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
    void aCrossCurrencyObligationBooksALinkedPair() throws Exception {
        // ADR 0013's rate, the other way round: 1 INR is 1.78878 JPY, so INR 125.00 is 223.5975 JPY and rounds
        // to 224. The debtor's side stays exact because that is what they agreed to pay; the creditor's side is
        // the converted amount because that is what they agreed to receive.
        rate("INR", 2, "JPY", 0, 178_878L, 5, QUOTED_AT, EXPIRES_AT);

        recordSettlingIn(GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", OCCURRED_AT, "key-cross")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settleInCurrency").value("JPY"))
                .andExpect(jsonPath("$.fxQuote.baseAmount.amountMinor").value(12_500L))
                .andExpect(jsonPath("$.fxQuote.baseAmount.currency").value("INR"))
                .andExpect(jsonPath("$.fxQuote.convertedAmount.amountMinor").value(224L))
                .andExpect(jsonPath("$.fxQuote.convertedAmount.currency").value("JPY"))
                .andExpect(jsonPath("$.fxQuote.rate.base").value("INR"))
                .andExpect(jsonPath("$.fxQuote.rate.quote").value("JPY"))
                .andExpect(jsonPath("$.fxQuote.rate.rateScaled").value(178_878L))
                .andExpect(jsonPath("$.fxQuote.rate.rateScale").value(5))
                .andExpect(jsonPath("$.fxQuote.rate.source").value("test fixture"))
                // The residual is sent as the fraction it is. It is what the two sides differ by, and it is not
                // a whole minor unit, which is exactly why it is not booked (see Bookings' residual note). The
                // sign is FxConversion's convention: negative when rounding rounded up, which is what happened
                // here - 223.5975 became 224 - so -4025/10^4 is -0.4025 of a yen.
                .andExpect(jsonPath("$.fxQuote.residualScaled").value(-4_025L))
                .andExpect(jsonPath("$.fxQuote.residualScale").value(4))
                // Absent rather than false, per the contract: it is true only when the rail converted.
                .andExpect(jsonPath("$.fxQuote.railPerformedFx").doesNotExist());

        List<JournalEntry> inr = chainInOrder(GROUP, "INR");
        List<JournalEntry> jpy = chainInOrder(GROUP, "JPY");

        assertThat(inr).as("the debt book gets one entry").hasSize(1);
        assertThat(jpy).as("the settlement book gets the other").hasSize(1);

        // Each chain verifies on its own, which is the half EntryChain can prove (L1, L4, L6).
        assertThat(EntryChain.problemsIn(inr)).isEmpty();
        assertThat(EntryChain.problemsIn(jpy)).isEmpty();

        // And the pairing is verified across them with the verifier an offline job would run (L9): a valid
        // chain in one currency proves nothing about its partner existing.
        List<JournalEntry> both = new ArrayList<>(inr);
        both.addAll(jpy);
        assertThat(FxLinkage.unpairedLinkIds(both))
                .as("both halves of the conversion are stored and share one link")
                .isEmpty();
        assertThat(inr.get(0).fxRole()).isEqualTo(FxRole.BASE);
        assertThat(jpy.get(0).fxRole()).isEqualTo(FxRole.QUOTE);

        // L8's measurement, from the rows: the clearing account holds what the debtor owes in the debt
        // currency, and owes the creditor the converted amount in the settlement currency.
        Currency inrCurrency = Currency.of("INR", 2);
        Currency jpyCurrency = Currency.of("JPY", 0);
        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, inrCurrency, inr.get(0).postings()))
                .isEqualTo(Money.of(12_500L, inrCurrency));
        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, jpyCurrency, jpy.get(0).postings()))
                .isEqualTo(Money.of(-224L, jpyCurrency));

        // The read path, reconstructing the obligation from its quote: a preview has to see the conversion,
        // not the debt side alone - which is what it would have seen before V103 and the quote's storage.
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP).header("Authorization", "Bearer " + caller)
                        .param("asOf", "2026-09-25T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(2)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[1].currency").value("JPY"))
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(2)))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(12_500L))
                .andExpect(jsonPath("$.bestCase.transfers[1].amount.amountMinor").value(224L));
    }

    @Test
    void aStaleRateIsRefusedAndNothingIsWritten() throws Exception {
        // Quoted and expired hours before the commitment was made. A 422 rather than a 503: the request is
        // answerable and the answer is no, because locking it would promise the creditor an amount computed
        // from a price the market has moved away from (ADR 0013 point 6).
        rate("INR", 2, "JPY", 0, 178_878L, 5,
                Instant.parse("2026-09-24T06:00:00Z"), Instant.parse("2026-09-24T06:01:00Z"));

        recordSettlingIn(GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", OCCURRED_AT, "key-stale")
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("fx.rate_stale"));

        assertNothingWasRecorded();
    }

    @Test
    void aCrossCurrencyRequestReplayedAnswersWithTheSameQuoteAndBooksNothing() throws Exception {
        rate("INR", 2, "JPY", 0, 178_878L, 5, QUOTED_AT, EXPIRES_AT);

        String first = recordSettlingIn(
                        GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", OCCURRED_AT, "key-x-replay")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        recordSettlingIn(GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", OCCURRED_AT, "key-x-replay")
                .andExpect(status().isCreated())
                .andExpect(content().json(first));

        assertThat(obligationCount()).isEqualTo(1);
        assertThat(quoteCount())
                .as("the retry locked nothing, so the creditor was promised one rate")
                .isEqualTo(1);
        assertThat(chainInOrder(GROUP, "INR")).hasSize(1);
        assertThat(chainInOrder(GROUP, "JPY")).hasSize(1);
    }

    @Test
    void aPairWithNoRateAtAllIsUnavailableRatherThanStale() throws Exception {
        // 503 rather than 422, and the difference is what the client does next: nothing has ever been quoted
        // for this pair, so a retry may succeed once something has. A client that conflated the two would
        // retry forever or give up too early, which is why the contract distinguishes them and why the provider
        // returns the newest rate and judges it rather than filtering to usable ones.
        recordSettlingIn(GROUP, ALICE, BORIS, 12_500L, "INR", "JPY", OCCURRED_AT, "key-no-rate")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("fx.rate_unavailable"));

        assertNothingWasRecorded();
    }

    @Test
    void refusesAMissingIdempotencyKey() throws Exception {
        mvc.perform(post("/v1/obligations")
                        .header("Authorization", "Bearer " + caller)
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
                        .header("Authorization", "Bearer " + caller)
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
                        .header("Authorization", "Bearer " + caller)
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
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP).header("Authorization", "Bearer " + caller)
                        .param("asOf", "2026-09-25T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(12_500L))
                .andExpect(jsonPath("$.bestCase.transfers[0].fromUserId").value(ALICE.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toUserId").value(BORIS.toString()));
    }


    /**
     * The attribution this slice added, asserted on the ledger's own row rather than on the response.
     *
     * <p>{@code Commitment.createdBy} was {@code null} for every recorded obligation, with the comment "no identity
     * module yet, so nothing can say who submitted it". Identity arrived in ADR 0042, so the comment described a
     * deployment that no longer existed - and since {@code createdBy} is part of the entry's hash (L4), what this test
     * reads back is what the chain commits to, not a column stored beside it.
     */
    @Test
    void theEntryNamesTheMemberWhoRecordedIt() throws Exception {
        record(GROUP, ALICE, BORIS, 12_500L, "INR", "attributed-1").andExpect(status().isCreated());

        UUID createdBy = jdbc.queryForObject(
                "SELECT created_by FROM journal_entries WHERE group_id = ?", UUID.class, GROUP);

        assertThat(createdBy).isEqualTo(callerId);
    }

    /**
     * The rule itself, from both sides: somebody signed in who is not in the group, and somebody not signed in at all.
     *
     * <p>{@code 404} rather than {@code 403}, which is what this operation's own void has answered since ADR 0045: one
     * answer for "no such group" and "not yours", so a guessed id cannot be tested by comparing refusals.
     */
    @Test
    void aStrangerCannotRecordIntoAGroup() throws Exception {
        String stranger = signedIn("+919899999999");

        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "stranger-1")
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GROUP, ALICE, BORIS, 12_500L, "INR", "lunch")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        // Refused before anything is read or written: no obligation row, and nothing booked - which is the half a
        // refusal could not undo if it happened later.
        assertThat(obligationCount()).isZero();
        assertThat(chainInOrder(GROUP, "INR")).as("and booked nothing").isEmpty();
    }

    @Test
    void aRecordingWithNoSessionIsRefusedWith401() throws Exception {
        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "anonymous-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(GROUP, ALICE, BORIS, 12_500L, "INR", "lunch")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        assertThat(obligationCount()).isZero();
    }

    private ResultActions record(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency, String key)
            throws Exception {

        return mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", key)
                .header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(group, debtor, creditor, amountMinor, currency, "lunch")));
    }

    private ResultActions recordSettlingIn(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency,
            String settleInCurrency, Instant occurredAt, String key) throws Exception {

        return mvc.perform(post("/v1/obligations")
                .header("Idempotency-Key", key)
                .header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "groupId": "%s",
                          "debtorId": "%s",
                          "creditorId": "%s",
                          "amount": { "amountMinor": %d, "currency": "%s" },
                          "settleInCurrency": "%s",
                          "memo": "lunch",
                          "occurredAt": "%s"
                        }
                        """.formatted(group, debtor, creditor, amountMinor, currency, settleInCurrency,
                        occurredAt)));
    }

    /**
     * Nothing at all was written: no obligation, no quote, no entry.
     *
     * <p>Asserted as one thing because it is one promise. A refusal that recorded the obligation and failed to
     * book it would leave a commitment the ledger does not know about, and a refusal that locked a quote and
     * then failed would leave a conversion nobody made - which is why the three are checked together rather
     * than relying on the status code.
     */
    private void assertNothingWasRecorded() {
        assertThat(obligationCount()).isZero();
        assertThat(quoteCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM postings", Integer.class)).isZero();
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

    private int quoteCount() {
        return jdbc.queryForObject("SELECT count(*) FROM fx_quotes", Integer.class);
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
