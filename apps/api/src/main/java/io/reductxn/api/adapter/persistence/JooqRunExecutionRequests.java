package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.RunExecutionRequests;

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
 * A member's request to execute a run, written into the run's own row.
 *
 * <p><strong>This module writes two columns of a table it does not own, and that is the cost ADR 0020 accepts rather
 * than an accident.</strong> {@code settlement_runs} belongs to {@code workers/settlement} (its migrations are the
 * {@code 5xx} range); the API is the HTTP surface a member reaches and the worker owns the rail, so the intent travels
 * as a row the worker reads on its next pass - the same route {@code settlement_cycles} takes from this module to the
 * cadence that computes it (ADR 0050). No jOOQ type leaves this package (RULE B16), so the two columns are named here
 * and nowhere else.
 *
 * <p><strong>It writes a flag and a record, and it reads nothing back.</strong> Whether the request is still pending is
 * a question for the driver - {@code execute_requested_at} is cleared when the run is attempted - and answering it
 * here would be this module forming an opinion about the worker's schedule.
 */
@Repository
public class JooqRunExecutionRequests implements RunExecutionRequests {

    private static final Table<Record> RUNS = DSL.table("settlement_runs");
    private static final Field<UUID> RUN_ID = DSL.field("id", UUID.class);
    private static final Field<OffsetDateTime> RUN_REQUESTED_AT = DSL.field("execute_requested_at",
            OffsetDateTime.class);
    private static final Field<UUID> RUN_REQUESTED_BY = DSL.field("execute_requested_by", UUID.class);

    private final DSLContext dsl;

    public JooqRunExecutionRequests(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void record(UUID runId, UUID requestedBy, Instant requestedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");

        // Both columns in one statement: the flag and the record of who asked are the same fact about the run, and a
        // crash between two updates would leave a request nobody made. The run's own existence is not re-checked -
        // the use case read it a moment ago, and an update that matches no row is the honest outcome of a race with a
        // worker that deleted it rather than something to report as a failure.
        dsl.update(RUNS)
                .set(RUN_REQUESTED_AT, requestedAt.atOffset(ZoneOffset.UTC))
                .set(RUN_REQUESTED_BY, requestedBy)
                .where(RUN_ID.eq(runId))
                .execute();
    }
}
