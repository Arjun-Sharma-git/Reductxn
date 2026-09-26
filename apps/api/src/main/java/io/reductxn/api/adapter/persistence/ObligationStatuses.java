package io.reductxn.api.adapter.persistence;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An obligation's status, as SQL, derived in one place for every reader.
 *
 * <p><strong>Why this is a class rather than an expression copied twice.</strong> Two readers need the same answer:
 * the list read serves it as a column, and the idempotent-replay path reports it on an obligation it already has. A
 * second copy of a four-branch CASE would be the second description of one fact that voids ADR 0021 - and the drift
 * would be silent, because both copies would look right on their own. The status is derived here once, and
 * {@code JooqObligations} and {@code JooqObligationStore} both read it.
 *
 * <p><strong>The four states and where each comes from.</strong> {@code voided} is this module's own column -
 * voiding sets {@code voided_at} rather than deleting (ADR 0004). {@code settled} and {@code netted} are facts about
 * the settlement worker's rows, which is a read model over another module's data rather than a dependency on it
 * (RULE B17): a link row from {@code settlement_run_obligations} means a run has taken the commitment, and the run's
 * own state says whether it kept it. {@code open} is the absence of all three.
 *
 * <p><strong>A run that ended without settling does not hold its commitments.</strong> The run states are the
 * worker's own vocabulary (its migration's {@code CHECK}), and they split three ways rather than two: {@code done}
 * settled, {@code rejected} and {@code failed} released, and the five while-it-is-happening states hold. That last
 * distinction is the one worth stating, because the obvious rule - "a link means netted unless the run is done" -
 * would call a commitment netted forever after a run failed, and a commitment nothing is settling is open. It is the
 * same reading {@code JooqOpenObligations} uses to decide what a preview may offer: the two must agree, or a group
 * previews one thing and the list says another.
 *
 * <p><strong>Every field here is qualified.</strong> The derivation contains correlated subqueries over two other
 * tables, so an unqualified {@code id} - which is what this package's simplest adapters use - would be ambiguous at
 * runtime, the failure {@code JooqCycles} records. Callers pass the obligation table's own aliased columns.
 */
final class ObligationStatuses {

    private ObligationStatuses() {
    }

    /**
     * The run states in which a run still holds its commitments: everything except the three a run ends in.
     *
     * <p>Listed rather than derived from "not terminal", deliberately. A state a later worker adds is then *not* a
     * holding state until somebody decides it is, which fails towards calling a commitment open - the safe direction,
     * since calling it netted would hide it from the group's next preview.
     */
    static final List<String> HOLDING_STATES = List.of(
            "pending", "computing", "awaiting_approval", "awaiting_fx_funding", "executing");

    /** {@code settlement_run_obligations} and the run it points at, aliased: this is a read model, not a join in use. */
    private static final Table<Record> LINKS = DSL.table("settlement_run_obligations").as("held");
    private static final Field<UUID> LINK_RUN = DSL.field(DSL.name("held", "run_id"), UUID.class);
    private static final Field<UUID> LINK_OBLIGATION = DSL.field(DSL.name("held", "obligation_id"), UUID.class);

    private static final Table<Record> RUNS = DSL.table("settlement_runs").as("held_run");
    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("held_run", "id"), UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field(DSL.name("held_run", "status"), String.class);

    /**
     * The contract's {@code status} for one obligation row.
     *
     * @param obligationId the obligation's own id, qualified by its table's alias
     * @param voidedAt     the obligation's {@code voided_at}, from the same alias
     */
    static Field<String> of(Field<UUID> obligationId, Field<OffsetDateTime> voidedAt) {
        return DSL.case_()
                .when(voidedAt.isNotNull(), DSL.val("voided"))
                .when(DSL.exists(linkTo(obligationId, List.of("done"))), DSL.val("settled"))
                .when(DSL.exists(linkTo(obligationId, HOLDING_STATES)), DSL.val("netted"))
                .else_(DSL.val("open"));
    }

    private static Select<?> linkTo(Field<UUID> obligationId, List<String> runStates) {
        return DSL.selectOne()
                .from(LINKS)
                .join(RUNS).on(RUN_ID.eq(LINK_RUN))
                .where(LINK_OBLIGATION.eq(obligationId))
                .and(RUN_STATUS.in(runStates));
    }
}
