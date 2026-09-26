package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.OpenedCycle;
import io.reductxn.api.port.out.CycleToOpen;
import io.reductxn.api.port.out.Cycles;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The group's cycle rows, written from {@code apps/api}.
 *
 * <p><strong>Another module's table, and the two things this class owes it: the shape, and the truth about what
 * happened.</strong> The columns are {@code workers/settlement}'s ({@code V501}). No jOOQ type leaves this package
 * (RULE B16), so the row is built and read as {@code Record} and the SQL here is the contract - which is why
 * {@code ComputeOpenedCyclesTest} in the worker writes the same four columns and computes what this writes.
 *
 * <p><strong>The insert is {@code do nothing} on conflict, and the count of what it did is the answer.</strong> An
 * insert that finds a row for the pair is not an error and not a second cycle: the caller wanted a cycle for this
 * instant to exist, and it does. The row is then read back rather than assumed, because on the conflict path the id
 * and the creation instant are the *stored* ones - this request never saw them, and answering with what it minted
 * would tell a client about a row that does not exist.
 *
 * <p><strong>Two statements, no transaction.</strong> The pair keys the row and only the pair, so a write arriving
 * concurrently can only be the same cycle: whichever insert wins, both readers see one row with one id. A
 * transaction would add a lock around a unique constraint that already settles the race.
 */
@Repository
public class JooqCycles implements Cycles {

    /** The cycle rows. Owned by {@code workers/settlement}; written here and computed there. */
    private static final Table<Record> CYCLES = DSL.table("settlement_cycles");

    private static final Field<UUID> CYCLE_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> CYCLE_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<OffsetDateTime> CYCLE_AS_OF = DSL.field("as_of", OffsetDateTime.class);
    private static final Field<OffsetDateTime> CYCLE_CREATED = DSL.field("created_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqCycles(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public OpenedCycle open(CycleToOpen cycle) {
        Objects.requireNonNull(cycle, "cycle");

        OffsetDateTime asOf = toOffset(cycle.asOf());

        int inserted = dsl.insertInto(CYCLES)
                .columns(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .values(cycle.id(), cycle.groupId(), asOf, toOffset(cycle.at()))
                .onConflict(CYCLE_GROUP, CYCLE_AS_OF)
                .doNothing()
                .execute();

        Record row = dsl.select(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .from(CYCLES)
                .where(CYCLE_GROUP.eq(cycle.groupId()))
                .and(CYCLE_AS_OF.eq(asOf))
                .fetchOne();

        if (row == null) {
            throw new IllegalStateException(
                    "the cycle for group " + cycle.groupId() + " as of " + cycle.asOf() + " was inserted or found "
                            + "and cannot be read back. The insert and this read are in one transaction, so this "
                            + "means the row went somewhere else");
        }

        return new OpenedCycle(
                row.get(CYCLE_ID),
                row.get(CYCLE_GROUP),
                row.get(CYCLE_AS_OF).toInstant(),
                row.get(CYCLE_CREATED).toInstant(),
                inserted > 0);
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
