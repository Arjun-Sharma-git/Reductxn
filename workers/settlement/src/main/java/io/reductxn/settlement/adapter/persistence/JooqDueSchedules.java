package io.reductxn.settlement.adapter.persistence;

import io.reductxn.settlement.port.out.GroupSchedules;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code group_schedules}, as SQL: which groups are due, and moving one on (ADR 0057).
 *
 * <p><strong>{@code group_schedules} is written by the API and read and moved by this worker, and that split is the
 * deployment's shape rather than an accident.</strong> A group *states* a cadence through an operation, and the worker
 * *honours* it on a cadence of its own - ADR 0018's rule that the worker drives what it owns, applied to the one thing
 * that decides when there is something to drive. Both sides read the same table with their own adapter, which is how this
 * repository shares a schema between deployables: a port each, no shared class (RULE B15).
 *
 * <p><strong>The advance is one statement with the expectation in its {@code WHERE}.</strong> Read-then-write is safe
 * only because the write refuses to apply when the value it read has changed: two passes that read the same row both call
 * this, and exactly one of them moves it. That is the same conditional-update shape {@code JooqInvitations.spend} uses
 * for a code's last use, for the same reason - a check before a write is a check two callers can both pass.
 *
 * <p>{@code next_due_at <= dueBefore} rather than equality: a worker that was down has to find the periods it missed, and
 * a due instant in the past is exactly the state that means "not yet opened" - the cycle's own uniqueness settles whether
 * it was.
 */
@Component
public class JooqDueSchedules implements GroupSchedules {

    private static final Table<Record> SCHEDULES = DSL.table("group_schedules");
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<Integer> EVERY_DAYS = DSL.field("every_days", Integer.class);
    private static final Field<OffsetDateTime> NEXT_DUE_AT = DSL.field("next_due_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqDueSchedules(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<DueSchedule> dueBefore(Instant dueBefore, int batchSize) {
        Objects.requireNonNull(dueBefore, "dueBefore");

        return dsl.select(GROUP_ID, NEXT_DUE_AT, EVERY_DAYS)
                .from(SCHEDULES)
                .where(NEXT_DUE_AT.le(at(dueBefore)))
                // Soonest first, so a backlog drains in the order the periods happened rather than in whatever order the
                // planner happens to return rows in.
                .orderBy(NEXT_DUE_AT.asc())
                .limit(batchSize)
                .fetch(record -> new DueSchedule(
                        record.get(GROUP_ID),
                        record.get(NEXT_DUE_AT).toInstant(),
                        record.get(EVERY_DAYS)));
    }

    @Override
    public boolean advance(UUID groupId, Instant expectedDueAt, Instant nextDueAt) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(expectedDueAt, "expectedDueAt");
        Objects.requireNonNull(nextDueAt, "nextDueAt");

        return dsl.update(SCHEDULES)
                .set(NEXT_DUE_AT, at(nextDueAt))
                .where(GROUP_ID.eq(groupId))
                .and(NEXT_DUE_AT.eq(at(expectedDueAt)))
                .execute() > 0;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}