package io.reductxn.settlement.adapter.persistence;

import io.reductxn.rails.api.PaymentRail;
import io.reductxn.settlement.port.out.RailCallbacks;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/**
 * A rail's delivered answer, read back as the rail's own type.
 *
 * <p><strong>The table is written by another module and read here, which is the direction ADR 0020 describes.</strong>
 * A callback arrives at {@code apps/api} - the only deployable a rail can reach - and that module writes
 * {@code settlement_rail_callbacks} the way it writes {@code settlement_cycles}: a row one deployable writes and the
 * other reads (ADR 0051). This adapter is the other end of that, and it is deliberately a single indexed read: the
 * driver looks up one reference per transfer it is about to ask a rail about.
 *
 * <p><strong>The mapping is exhaustive over the stored vocabulary, and its {@code default} is unreachable.</strong>
 * The table's own CHECK names the four states, so a row outside them cannot exist and this cannot throw for data that
 * came through the boundary - the branch is there because a `String` column cannot be switch-exhaustive, and a
 * missing case would otherwise be a silent null rather than a failure.
 */
@Repository
public class JooqRailCallbacks implements RailCallbacks {

    private static final Table<Record> CALLBACKS = DSL.table("settlement_rail_callbacks");
    private static final Field<String> CALLBACK_REF = DSL.field("rail_ref", String.class);
    private static final Field<String> CALLBACK_STATE = DSL.field("state", String.class);
    private static final Field<String> CALLBACK_DETAIL = DSL.field("detail", String.class);

    private final DSLContext dsl;

    public JooqRailCallbacks(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<PaymentRail.RailStatus> answerFor(String railRef) {
        Objects.requireNonNull(railRef, "railRef");

        // The reference is the primary key, so this is one lookup. No `ORDER BY` and no `LIMIT`: a redelivery
        // replaces the row rather than adding one (V513), so the table holds at most one answer per reference.
        return dsl.select(CALLBACK_STATE, CALLBACK_DETAIL)
                .from(CALLBACKS)
                .where(CALLBACK_REF.eq(railRef))
                .fetchOptional(row -> new PaymentRail.RailStatus(
                        railRef, stateOf(row.get(CALLBACK_STATE)), row.get(CALLBACK_DETAIL)));
    }

    private static PaymentRail.RailStatus.State stateOf(String stored) {
        return switch (stored) {
            case "accepted" -> PaymentRail.RailStatus.State.ACCEPTED;
            case "pending" -> PaymentRail.RailStatus.State.PENDING;
            case "confirmed" -> PaymentRail.RailStatus.State.CONFIRMED;
            case "failed" -> PaymentRail.RailStatus.State.FAILED;
            default -> throw new IllegalStateException("settlement_rail_callbacks.state holds " + stored
                    + ", which its CHECK does not allow: either the constraint was dropped or a writer bypassed it");
        };
    }
}
