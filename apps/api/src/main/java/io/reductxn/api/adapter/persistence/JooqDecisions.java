package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.NewApproval;
import io.reductxn.api.port.out.Approvals;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * The run's decision rows, written from {@code apps/api}.
 *
 * <p><strong>Another module's table, and the two things this class owes it: the shape, and the truth about
 * refusals.</strong> The columns are {@code workers/settlement}'s ({@code V502}). No jOOQ type leaves this package
 * (RULE B16), so the row is built and read as {@code Record}, and the SQL here is the contract - which is why
 * {@code RecordedApprovalsTest} in the worker writes the same statement to pin the other side of it.
 *
 * <p><strong>The word {@code 'approve'} is written here and nowhere else in this module.</strong> That is
 * deliberate: it is the run's vocabulary, fixed by the migration's check constraint and read by the run's quorum,
 * and this deployable is a writer of rows rather than an owner of words. The endpoint that reaches this class is
 * {@code /approve}, so no caller of the API needs the string; when members can say no as well, the word arrives
 * from a request field and this is still the only place it becomes SQL.
 *
 * <p><strong>A refusal from the database is a value, not an exception.</strong> Two constraints can legitimately
 * refuse an insert - the primary key on {@code (run_id, member_id)}, which is how "one answer per member" survives
 * two requests arriving together, and the run's check constraints, which decide what an answer can be. Translating
 * them by name is the difference between a client getting a code and a client getting a {@code 500}, and the name
 * is read from Postgres's own error rather than guessed at from a message: a constraint this class has never heard
 * of is re-thrown, because it means something is wrong that this class has no honest answer for.
 */
@Repository
public class JooqApprovals implements Approvals {

    /** The run whose answers these are. Read here, written by the worker. */
    private static final Table<Record> RUNS = DSL.table("settlement_runs");

    private static final Field<UUID> RUN_ID = DSL.field("id", UUID.class);

    /** The decision rows. */
    private static final Table<Record> DECISIONS = DSL.table("settlement_decisions");

    private static final Field<UUID> DECISION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<UUID> DECISION_MEMBER = DSL.field("member_id", UUID.class);
    private static final Field<String> DECISION_WORD = DSL.field("decision", String.class);
    private static final Field<OffsetDateTime> DECISION_AT = DSL.field("decided_at", OffsetDateTime.class);

    /** The answer this endpoint records: the run's vocabulary, stated once. */
    private static final String APPROVE = "approve";

    private final DSLContext dsl;

    public JooqApprovals(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public boolean hasRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        return dsl.fetchExists(dsl.selectOne().from(RUNS).where(RUN_ID.eq(runId)));
    }

    @Override
    public Written record(NewApproval approval) {
        Objects.requireNonNull(approval, "approval");

        try {
            dsl.insertInto(DECISIONS)
                    .columns(DECISION_RUN, DECISION_MEMBER, DECISION_WORD, DECISION_AT)
                    .values(approval.runId(), approval.memberId(), APPROVE,
                            toOffset(approval.recordedAt()))
                    .execute();

            return Written.RECORDED;
        } catch (DuplicateKeyException alreadyAnswered) {
            // One answer per member, which Postgres reports as a unique violation on the primary key.
            return Written.ALREADY_ANSWERED;
        } catch (DataIntegrityViolationException refusedByTheRun) {
            // The run's other constraints: a check that says what an answer can be, or the foreign key to a run
            // that is not there. Every one of them is the run's judgement of an answer, which is what the caller is
            // told - and it is a value rather than an exception because a legal request produced it.
            return Written.REFUSED_BY_THE_RUN;
        }
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
