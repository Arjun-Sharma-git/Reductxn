package io.reductxn.settlement;

import io.reductxn.booking.api.Booking;
import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.Commitment;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.kernel.api.Result;
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
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SingleCurrencyObligation;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.out.ChainHeads;
import io.reductxn.settlement.port.out.JournalEntries;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.assertj.core.api.Assertions.tuple;

/**
 * What a settled run leaves behind: entries in the ledger, and commitments that are no longer open.
 *
 * <p>The two halves are one subject. A run that moved money owes the group's books an entry for each movement
 * (L5: a balance is a projection over postings, so an unrecorded movement is a balance that lies), and it owes
 * the next cycle an answer to "what is still owed" - because the commitments it settled must not be settled
 * again.
 *
 * <p>Every assertion here is made through the ledger's own verifiers - {@link EntryChain} for the chain,
 * {@link Balances} for the positions - rather than by comparing fields a second time. Rebuilding a
 * {@code JournalEntry} from its rows and handing it to the verifier is itself a check: the type refuses to
 * exist unbalanced (L1) or in mixed currencies (L6), so a row that broke either invariant fails here rather
 * than passing a field-by-field comparison.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class})
class SettlementBookkeepingTest extends WorkerDatabaseTest {

    private static final Currency INR = Currency.of("INR", 2);

    @Autowired
    private StubRail rail;

    @Autowired
    private JournalEntries journalEntries;

    @Autowired
    private ChainHeads chainHeads;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    @Test
    void everyConfirmedTransferIsBookedAsASettlementEntry() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        ok(executeRun.execute(run.run().id()));

        List<JournalEntry> chain = chainInOrder(GROUP, "INR");
        PlannedTransfer transfer = run.transfers().get(0);

        assertThat(chain).hasSize(1);
        JournalEntry entry = chain.get(0);

        assertThat(entry.kind())
                .as("what moved is not what was promised: an entry that recorded a transfer as an obligation "
                        + "would double the debt it discharged")
                .isEqualTo(EntryKind.SETTLEMENT);
        assertThat(entry.idempotencyKey())
                .as("the rail's reference for the movement, which is what an auditor follows back to the "
                        + "instruction")
                .isEqualTo(transfer.railRef());
        assertThat(entry.createdBy())
                .as("no member caused this - a rail confirmed it")
                .isNull();
        assertThat(entry.currency()).isEqualTo(INR);
        assertThat(entry.hash()).isEqualTo(entry.hash());
        assertThat(EntryChain.problemsIn(chain))
                .as("the stored chain verifies, which is the ledger's central claim about itself")
                .isEmpty();
        assertThat(entry.postings()).extracting(Posting::accountId, Posting::direction, Posting::amount)
                .containsExactly(
                        tuple(transfer.fromUserId(), Direction.DEBIT, transfer.amount()),
                        tuple(transfer.toUserId(), Direction.CREDIT, transfer.amount()));
    }

    @Test
    void replayingAnExecutionBooksNothingNew() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        ok(executeRun.execute(run.run().id()));
        int entriesAfterTheFirstPass = countIn("journal_entries");

        ok(executeRun.execute(run.run().id()));

        assertThat(countIn("journal_entries"))
                .as("RULE S2 at the ledger: a replay moves nothing, so it books nothing. The entry id is "
                        + "derived from the transfer, which is what makes a second booking a collision rather "
                        + "than a duplicate")
                .isEqualTo(entriesAfterTheFirstPass);
    }

    @Test
    void aRefusedTransferIsNotBookedAndTheConfirmedOnesAre() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer doomed = run.transfers().get(1);
        rail.scripting(doomed.railRef(), StubRail.Behaviour.REFUSE);

        ok(executeRun.execute(run.run().id()));

        assertThat(storedEntries(GROUP, doomed.currency().code()))
                .as("RULE S3 is a promise about the ledger too: the money that did not move is not posted, so "
                        + "the books are not quietly settled around it")
                .isEmpty();
        assertThat(chainInOrder(GROUP, "INR"))
                .as("and the transfer that did move is booked, ruled and chained - a partial settlement is a "
                        + "partial set of entries, not a hole in the chain")
                .hasSize(1);
        assertThat(EntryChain.problemsIn(chainInOrder(GROUP, "INR"))).isEmpty();
    }

    @Test
    void aSettledCycleLeavesTheLedgerFlatAndTheClearingAccountAtZero() {
        // Both shapes are booked the way apps/api books them, because a run's ledger side is only meaningful
        // against a book that already holds the commitment: this fixture is the before, and the run is the
        // after. Without the booking, the settlement's debits would look like money created from nothing.
        recordedCrossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);
        ok(executeRun.execute(run.run().id()));

        List<JournalEntry> inr = chainInOrder(GROUP, "INR");
        List<JournalEntry> jpy = chainInOrder(GROUP, "JPY");

        assertThat(EntryChain.problemsIn(inr))
                .as("the obligation and the settlement are one verifiable chain per currency")
                .isEmpty();
        assertThat(EntryChain.problemsIn(jpy)).isEmpty();
        assertThat(FxLinkage.unpairedLinkIds(concat(inr, jpy)))
                .as("L9: the cross-currency commitment's two sides are still paired after the settlement "
                        + "booked beside them")
                .isEmpty();

        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, INR, Balances.postingsIn(inr, INR)))
                .as("L8: `fx_clearing` is zero per currency at the end of the cycle. The commitment put the "
                        + "rupees into the account and the settlement's leg took them out again; what the group "
                        + "brought in from outside is not in this ledger at all, because reductxn never held it "
                        + "(P2). L8's other branch - a run flagged externally_funded - is the rail-performed-FX "
                        + "case, where a rail converts on the group's behalf")
                .isEqualTo(Money.zero(INR));
        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, JPY, Balances.postingsIn(jpy, JPY)))
                .isEqualTo(Money.zero(JPY));

        for (UUID member : run.quorum().members()) {
            assertThat(Balances.netDebitOf(member, INR, Balances.postingsIn(inr, INR)))
                    .as("N2, measured on the ledger rather than on the plan: member " + member + " is flat in "
                            + "INR once the run has moved what it promised")
                    .isEqualTo(Money.zero(INR));
            assertThat(Balances.netDebitOf(member, JPY, Balances.postingsIn(jpy, JPY)))
                    .isEqualTo(Money.zero(JPY));
        }
    }

    @Test
    void aCommitmentASettledRunDischargedIsNotOpenForALaterCycle() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        ok(executeRun.execute(run.run().id()));

        assertThat(countIn("settlement_run_obligations"))
                .as("the run recorded the commitment it netted, which is what makes the next answer possible")
                .isEqualTo(1);

        Result<ComputedRun> later = computeRun.compute(new RunRequest(GROUP, AS_OF.plusSeconds(86_400)));

        assertThat(refusalCode(later))
                .as("the double settlement: the money for this commitment has moved, so a later cycle over the "
                        + "same group has nothing to settle. Without the link table this run would net the same "
                        + "debt again and move it twice")
                .isEqualTo("settlement.nothing_to_settle");
    }

    @Test
    void aFailedRunSettlesNothingSoItsCommitmentsStayOpen() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer doomed = run.transfers().get(1);
        rail.scripting(doomed.railRef(), StubRail.Behaviour.REFUSE);
        ok(executeRun.execute(run.run().id()));

        assertThat(storedRunStatus(run.run().id())).isEqualTo("failed");
        assertThat(computeRun.compute(new RunRequest(GROUP, AS_OF.plusSeconds(86_400))).isOk())
                .as("RULE S3 in the spec's own words: on a partial failure the net positions are not marked "
                        + "settled. The commitment is still open, so the group can settle it again once it has "
                        + "dealt with what went wrong - which is the opposite of the settled-run case above, and "
                        + "the reason 'settled' is the run's status rather than a flag on the obligation")
                .isTrue();
    }

    /**
     * Records a single-currency obligation <em>and</em> books it, which is what the API does in one transaction.
     *
     * <p>A fixture rather than a product path: it exists so a test about a run's ledger side can start from the
     * state a real deployment has, where the commitment is already in the book. The chain head is read inside
     * the transaction because that is what the lock requires - the same rule the application obeys, which is
     * itself worth exercising.
     */
    private void recordedObligation(UUID debtor, UUID creditor, long amountMinor, Currency currency) {
        UUID obligationId =
                obligation(debtor, creditor, amountMinor, currency.code(), currency.minorUnitExponent());

        inTransaction(() -> journalEntries.append(Bookings.singleCurrency(
                chainHeads.forGroup(GROUP),
                new Commitment(obligationId, GROUP, OBLIGATION_RECORDED_AT, null, null),
                new SingleCurrencyObligation(debtor, creditor, Money.of(amountMinor, currency)))));
    }

    /** Records a cross-currency obligation and books both of its linked legs, as the API does. */
    private void recordedCrossCurrencyObligation(UUID debtor, UUID creditor, long baseMinor, long convertedMinor) {
        FxQuote quote = crossCurrencyObligation(debtor, creditor, baseMinor, convertedMinor);
        UUID obligationId = jdbc.queryForObject(
                "SELECT id FROM obligations WHERE quote_id = ?", UUID.class, quote.id());

        inTransaction(() -> {
            Booking booking = Bookings.crossCurrency(
                    chainHeads.forGroup(GROUP),
                    new Commitment(obligationId, GROUP, OBLIGATION_RECORDED_AT, null, null),
                    new CrossCurrencyObligation(debtor, creditor, quote),
                    FxClearing.ACCOUNT_ID);

            for (JournalEntry entry : booking.entries()) {
                journalEntries.append(entry);
            }
        });
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(state -> work.run());
    }

    private static List<JournalEntry> concat(List<JournalEntry> first, List<JournalEntry> second) {
        List<JournalEntry> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    /**
     * The chain for one group and currency, walked from its genesis along {@code prev_hash}.
     *
     * <p>Walked rather than ordered by time, so this helper cannot agree with the adapter by making the same
     * mistaken assumption - the same helper the API's tests use, for the same reason: if a link is missing the
     * walk is shorter than the rows, and the size assertions are what notice.
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
     */
    private JournalEntry storedEntry(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        String role = row.getString("fx_role");

        return new JournalEntry(
                id,
                row.getObject("group_id", UUID.class),
                Currency.of(row.getString("currency_code"), row.getInt("currency_exponent")),
                // OffsetDateTime rather than Timestamp: a timestamptz read as a Timestamp is interpreted in the
                // JVM's zone, which is the class of mistake these read models avoid on purpose.
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
