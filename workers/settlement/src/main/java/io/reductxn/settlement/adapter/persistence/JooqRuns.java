package io.reductxn.settlement.adapter.persistence;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.TransferInstruction;
import io.reductxn.planning.api.CurrencyPlan;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.PartitionOutcome;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.out.Runs;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The runs, in this module's five tables.
 *
 * <p>Writes the plan as it was handed over and reads it back for the answer, so there is one derivation of the
 * report rather than two - and it is exercised on the way out of every write. Everything read here is a row:
 * nothing re-runs the engine to describe a run, because a run is a record of what was decided rather than a
 * question to be asked again.
 *
 * <p>The rail references are derived from each transfer's own coordinates, which is RULE S2's requirement that
 * a re-submission be a no-op. They belong in a rail adapter, and there is none yet - so the derivation lives
 * here, in one place, with the schema's {@code UNIQUE} constraint as its second half. When {@code libs/rails}
 * arrives this is where its first caller will find the format.
 */
@Component
public class JooqRuns implements Runs {

    private static final Table<Record> CYCLES = DSL.table("settlement_cycles");
    private static final Table<Record> RUNS = DSL.table("settlement_runs");
    private static final Table<Record> PARTITIONS = DSL.table("settlement_partitions");
    private static final Table<Record> POSITIONS = DSL.table("net_positions");
    private static final Table<Record> TRANSFERS = DSL.table("settlement_transfers");

    private static final Field<UUID> CYCLE_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> CYCLE_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<OffsetDateTime> CYCLE_AS_OF = DSL.field("as_of", OffsetDateTime.class);
    private static final Field<OffsetDateTime> CYCLE_CREATED = DSL.field("created_at", OffsetDateTime.class);

    private static final Field<UUID> RUN_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> RUN_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<UUID> RUN_CYCLE = DSL.field("cycle_id", UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field("status", String.class);
    private static final Field<OffsetDateTime> RUN_COMPUTED = DSL.field("computed_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> RUN_CREATED = DSL.field("created_at", OffsetDateTime.class);

    private static final Field<UUID> PARTITION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> PARTITION_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> PARTITION_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<Long> PARTITION_GROSS = DSL.field("gross_minor", Long.class);
    private static final Field<Long> PARTITION_NET = DSL.field("net_minor", Long.class);
    private static final Field<Long> PARTITION_TAX = DSL.field("tax_minor", Long.class);
    private static final Field<Long> PARTITION_TAX_SAVED = DSL.field("tax_saved_minor", Long.class);

    private static final Field<UUID> POSITION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> POSITION_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<UUID> POSITION_PARTICIPANT = DSL.field("participant_id", UUID.class);
    private static final Field<Long> POSITION_NET = DSL.field("net_minor", Long.class);

    private static final Field<UUID> TRANSFER_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> TRANSFER_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> TRANSFER_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> TRANSFER_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<UUID> TRANSFER_FROM = DSL.field("from_user_id", UUID.class);
    private static final Field<UUID> TRANSFER_TO = DSL.field("to_user_id", UUID.class);
    private static final Field<Long> TRANSFER_AMOUNT = DSL.field("amount_minor", Long.class);
    private static final Field<String> TRANSFER_RAIL_REF = DSL.field("rail_ref", String.class);
    private static final Field<String> TRANSFER_STATUS = DSL.field("status", String.class);
    private static final Field<OffsetDateTime> TRANSFER_CREATED = DSL.field("created_at", OffsetDateTime.class);

    private static final String PENDING = "pending";

    private final DSLContext dsl;

    public JooqRuns(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<ComputedRun> findRun(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        Record cycle = dsl.select(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .from(CYCLES)
                .where(CYCLE_GROUP.eq(groupId))
                .and(CYCLE_AS_OF.eq(asOf.atOffset(ZoneOffset.UTC)))
                .fetchOne();

        return cycle == null ? Optional.empty() : Optional.of(describe(cycle));
    }

    @Override
    public ComputedRun open(SettlementRun run, SettlementPlan plan) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(plan, "plan");

        OffsetDateTime asOf = run.asOf().atOffset(ZoneOffset.UTC);

        // The cycle first, and `do nothing` on conflict rather than a check-then-insert: two processes
        // computing the same cycle at once is precisely the race the derived ids cannot settle, and this is the
        // statement that settles it. The loser reads the winner's cycle and run - which is why `findRun` is the
        // recovery path rather than a wish.
        dsl.insertInto(CYCLES)
                .columns(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .values(run.cycleId(), run.groupId(), asOf, run.createdAt().atOffset(ZoneOffset.UTC))
                .onConflict(CYCLE_GROUP, CYCLE_AS_OF)
                .doNothing()
                .execute();

        UUID cycleId = dsl.select(CYCLE_ID)
                .from(CYCLES)
                .where(CYCLE_GROUP.eq(run.groupId()))
                .and(CYCLE_AS_OF.eq(asOf))
                .fetchOne(CYCLE_ID);

        dsl.insertInto(RUNS)
                .columns(RUN_ID, RUN_GROUP, RUN_CYCLE, RUN_STATUS, RUN_COMPUTED, RUN_CREATED)
                .values(run.id(), run.groupId(), cycleId, run.status().wireName(),
                        run.computedAt().atOffset(ZoneOffset.UTC), run.createdAt().atOffset(ZoneOffset.UTC))
                .onConflict(RUN_CYCLE)
                .doNothing()
                .execute();

        for (CurrencyPlan partition : plan.partitions()) {
            writePartition(run, partition);
        }

        return findRun(run.groupId(), run.asOf())
                .orElseThrow(() -> new IllegalStateException(
                        "run " + run.id() + " was opened and cannot be read back. The write and the read are "
                                + "in one transaction, so this means the rows went somewhere else"));
    }

    /** The partition's summary, its positions and its transfers. One partition, one currency, no totals. */
    private void writePartition(SettlementRun run, CurrencyPlan partition) {
        Currency currency = partition.currency();
        NettingResult netting = partition.netting();
        OffsetDateTime now = run.createdAt().atOffset(ZoneOffset.UTC);

        dsl.insertInto(PARTITIONS)
                .columns(PARTITION_RUN, PARTITION_CURRENCY, PARTITION_EXPONENT, PARTITION_GROSS, PARTITION_NET,
                        PARTITION_TAX, PARTITION_TAX_SAVED)
                .values(run.id(), currency.code(), (short) currency.minorUnitExponent(),
                        netting.gross().amountMinor(), netting.net().amountMinor(),
                        netting.tax().amountMinor(), netting.taxSaved().amountMinor())
                .execute();

        for (NetPosition position : netting.positions()) {
            dsl.insertInto(POSITIONS)
                    .columns(POSITION_RUN, POSITION_CURRENCY, DSL.field("currency_exponent", Short.class),
                            POSITION_PARTICIPANT, POSITION_NET)
                    .values(run.id(), currency.code(), (short) currency.minorUnitExponent(),
                            position.userId(), position.net().amountMinor())
                    .execute();
        }

        for (TransferInstruction transfer : netting.transfers()) {
            String railRef = railRefOf(run.id(), currency, transfer);

            dsl.insertInto(TRANSFERS)
                    .columns(TRANSFER_ID, TRANSFER_RUN, TRANSFER_CURRENCY, TRANSFER_EXPONENT, TRANSFER_FROM,
                            TRANSFER_TO, TRANSFER_AMOUNT, TRANSFER_RAIL_REF, TRANSFER_STATUS, TRANSFER_CREATED)
                    .values(transferIdOf(railRef), run.id(), currency.code(),
                            (short) currency.minorUnitExponent(), transfer.fromUserId(), transfer.toUserId(),
                            transfer.amount().amountMinor(), railRef, PENDING, now)
                    .execute();
        }
    }

    /**
     * RULE S2's rail reference: derived from the transfer's own coordinates, so a re-submission is the same
     * reference rather than a second movement.
     *
     * <p>The spec's tuple is {@code (run_id, from, to, amount)} and the currency is added here. Within one run
     * two currencies can produce the same three values - INR 100 and JPY 100 between the same pair - and they
     * are two different movements with two different rail references.
     */
    static String railRefOf(UUID runId, Currency currency, TransferInstruction transfer) {
        return "reductxn/" + runId + "/" + currency.code() + "/" + transfer.fromUserId() + "/"
                + transfer.toUserId() + "/" + transfer.amount().amountMinor();
    }

    /**
     * The transfer's identity, derived from its rail reference rather than drawn at random.
     *
     * <p>The schema's {@code UNIQUE (rail_ref)} is what actually forbids a double movement; deriving the id from
     * the same coordinates means a duplicate insert collides on the primary key too, so the failure is a
     * duplicate rather than two rows that differ only in their ids.
     */
    static UUID transferIdOf(String railRef) {
        return UUID.nameUUIDFromBytes(railRef.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The run's plan as stored, per currency.
     *
     * <p>Two queries for the whole run plus one for its partitions, and the per-currency gathering happens here
     * rather than in SQL: the transfers and positions are read by run and grouped by currency in a map, which
     * keeps the statement simple and the grouping visible. The alternative is a query per currency, which is
     * fine for a group with two currencies and not for one with twenty.
     */
    private ComputedRun describe(Record cycle) {
        UUID cycleId = cycle.get(CYCLE_ID);

        Record runRow = dsl.select(RUN_ID, RUN_STATUS, RUN_COMPUTED, RUN_CREATED)
                .from(RUNS)
                .where(RUN_CYCLE.eq(cycleId))
                .fetchOne();

        if (runRow == null) {
            // Both rows are written in one transaction, so this is not a state the code can reach - and it is
            // still an error rather than an empty answer, because a cycle with no run would be reported as
            // "nothing to settle" when the truth is that a plan was lost.
            throw new IllegalStateException(
                    "cycle " + cycleId + " exists with no run over it. The two are written together, so this "
                            + "means a run row was deleted rather than that a cycle was opened without one");
        }

        UUID runId = runRow.get(RUN_ID);

        Map<String, List<Record>> transfersByCurrency = new HashMap<>();
        for (Record row : dsl.select(TRANSFER_CURRENCY, TRANSFER_FROM, TRANSFER_TO, TRANSFER_AMOUNT)
                .from(TRANSFERS)
                .where(TRANSFER_RUN.eq(runId))
                .orderBy(TRANSFER_RAIL_REF.asc())
                .fetch()) {
            transfersByCurrency.computeIfAbsent(row.get(TRANSFER_CURRENCY), ignored -> new ArrayList<>()).add(row);
        }

        Map<String, Long> clearingByCurrency = new HashMap<>();
        for (Record row : dsl.select(POSITION_CURRENCY, POSITION_NET)
                .from(POSITIONS)
                .where(POSITION_RUN.eq(runId))
                .and(POSITION_PARTICIPANT.eq(FxClearing.ACCOUNT_ID))
                .fetch()) {
            clearingByCurrency.put(row.get(POSITION_CURRENCY), row.get(POSITION_NET));
        }

        List<PartitionOutcome> partitions = new ArrayList<>();
        for (Record row : dsl.select(PARTITION_CURRENCY, PARTITION_EXPONENT, PARTITION_GROSS, PARTITION_NET,
                        PARTITION_TAX, PARTITION_TAX_SAVED)
                .from(PARTITIONS)
                .where(PARTITION_RUN.eq(runId))
                .orderBy(PARTITION_CURRENCY.asc())
                .fetch()) {

            partitions.add(partitionOutcome(row, transfersByCurrency, clearingByCurrency));
        }

        SettlementRun run = new SettlementRun(
                runId,
                cycle.get(CYCLE_GROUP),
                cycleId,
                RunStatus.ofWireName(runRow.get(RUN_STATUS)),
                cycle.get(CYCLE_AS_OF).toInstant(),
                runRow.get(RUN_COMPUTED).toInstant(),
                runRow.get(RUN_CREATED).toInstant());

        return new ComputedRun(run, partitions);
    }

    /** One partition's summary, with its transfers counted and split, and its clearing position from the rows. */
    private PartitionOutcome partitionOutcome(
            Record partition, Map<String, List<Record>> transfersByCurrency, Map<String, Long> clearingByCurrency) {

        Currency currency = Currency.of(partition.get(PARTITION_CURRENCY), partition.get(PARTITION_EXPONENT));
        String code = currency.code();

        List<Record> transfers = transfersByCurrency.getOrDefault(code, List.of());

        int memberCount = 0;
        int clearingCount = 0;
        Money memberTotal = Money.zero(currency);

        for (Record transfer : transfers) {
            boolean touchesClearing = FxClearing.isClearing(transfer.get(TRANSFER_FROM))
                    || FxClearing.isClearing(transfer.get(TRANSFER_TO));

            if (touchesClearing) {
                clearingCount++;
            } else {
                memberCount++;
                memberTotal = memberTotal.plus(Money.of(transfer.get(TRANSFER_AMOUNT), currency));
            }
        }

        // The clearing position comes from the positions rather than from the transfers, because a currency
        // whose clearing position is non-zero is what RULE S4 gates on - and the clearing account's absence
        // from the plan means zero, which is the ordinary case rather than a missing row.
        Money clearingPosition = Money.of(clearingByCurrency.getOrDefault(code, 0L), currency);

        return new PartitionOutcome(
                currency,
                Money.of(partition.get(PARTITION_GROSS), currency),
                Money.of(partition.get(PARTITION_NET), currency),
                Money.of(partition.get(PARTITION_TAX), currency),
                Money.of(partition.get(PARTITION_TAX_SAVED), currency),
                clearingPosition,
                clearingPosition.absolute(),
                memberTotal,
                memberCount,
                clearingCount);
    }
}

