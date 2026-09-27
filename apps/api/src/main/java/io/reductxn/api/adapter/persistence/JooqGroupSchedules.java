package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.CycleSchedule;
import io.reductxn.api.port.out.GroupSchedules;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code group_schedules}, as SQL: one row per group, written by an upsert (ADR 0057).
 *
 * <p><strong>The upsert is the whole interface, and the primary key is why.</strong> "Set a schedule" and "change a
 * schedule" are one operation, because a group has one - so this is an insert with an update on conflict rather than a
 * check-then-insert pair. Two members setting a schedule at the same instant leave one of them in force, which is what
 * one row per group means, and no transaction is needed to say so: the database's own conflict handling is the
 * statement.
 *
 * <p><strong>Instants are written as {@code OffsetDateTime} at UTC</strong>, the convention every adapter in this module
 * uses, and read back through {@code toInstant()} so nothing above the adapter sees an offset.
 */
@Component
public class JooqGroupSchedules implements GroupSchedules {

    private static final Table<Record> SCHEDULES = DSL.table("group_schedules");
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<Integer> EVERY_DAYS = DSL.field("every_days", Integer.class);
    private static final Field<OffsetDateTime> NEXT_DUE_AT = DSL.field("next_due_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> SET_AT = DSL.field("set_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqGroupSchedules(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public CycleSchedule put(UUID groupId, int everyDays, Instant nextDueAt, Instant setAt) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nextDueAt, "nextDueAt");
        Objects.requireNonNull(setAt, "setAt");

        dsl.insertInto(SCHEDULES)
                .columns(GROUP_ID, EVERY_DAYS, NEXT_DUE_AT, SET_AT)
                .values(groupId, everyDays, at(nextDueAt), at(setAt))
                .onConflict(GROUP_ID)
                .doUpdate()
                .set(EVERY_DAYS, everyDays)
                .set(NEXT_DUE_AT, at(nextDueAt))
                .set(SET_AT, at(setAt))
                .execute();

        // Answered from the values that were written rather than by reading the row back: this statement is the whole
        // truth about it, and a second query would be a second chance to disagree with what this call just did.
        return new CycleSchedule(everyDays, nextDueAt, setAt);
    }

    @Override
    public Optional<CycleSchedule> read(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");

        Record row = dsl.select(EVERY_DAYS, NEXT_DUE_AT, SET_AT)
                .from(SCHEDULES)
                .where(GROUP_ID.eq(groupId))
                .fetchOne();

        if (row == null) {
            return Optional.empty();
        }

        return Optional.of(new CycleSchedule(
                row.get(EVERY_DAYS),
                row.get(NEXT_DUE_AT).toInstant(),
                row.get(SET_AT).toInstant()));
    }

    @Override
    public boolean clear(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");

        return dsl.deleteFrom(SCHEDULES).where(GROUP_ID.eq(groupId)).execute() > 0;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}