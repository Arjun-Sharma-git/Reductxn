package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.NewRailCallback;
import io.reductxn.api.port.out.RailCallbackInbox;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A rail's callback, written into the inbox the settlement worker reads.
 *
 * <p><strong>The upsert is the whole file, and the conflict target is the reason.</strong> A rail's callback is an
 * at-least-once signal: the same delivery arriving twice is normal, and the endpoint's promise is that it is
 * idempotent. Making the reference the primary key means a redelivery cannot mean two things about one transfer, so
 * idempotency is a property of the schema rather than a check somebody has to remember - the same choice
 * {@code settlement_run_gates} made for a run's verdict.
 *
 * <p><strong>No jOOQ type leaves this package</strong> (RULE B16), and the four columns are named here and nowhere
 * else. The state word is written exactly as the rail sent it, because the contract's vocabulary and the table's
 * CHECK are the same four words and inserting a translation would be a third one.
 */
@Repository
public class JooqRailCallbackInbox implements RailCallbackInbox {

    private static final Table<Record> CALLBACKS = DSL.table("settlement_rail_callbacks");
    private static final Field<String> CALLBACK_REF = DSL.field("rail_ref", String.class);
    private static final Field<String> CALLBACK_PROVIDER = DSL.field("provider", String.class);
    private static final Field<String> CALLBACK_STATE = DSL.field("state", String.class);
    private static final Field<String> CALLBACK_DETAIL = DSL.field("detail", String.class);
    private static final Field<OffsetDateTime> CALLBACK_RECEIVED = DSL.field("received_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqRailCallbackInbox(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void record(NewRailCallback callback) {
        Objects.requireNonNull(callback, "callback");

        dsl.insertInto(CALLBACKS)
                .columns(CALLBACK_REF, CALLBACK_PROVIDER, CALLBACK_STATE, CALLBACK_DETAIL, CALLBACK_RECEIVED)
                .values(callback.railRef(), callback.provider(), callback.state(), callback.detail(),
                        callback.receivedAt().atOffset(ZoneOffset.UTC))
                .onConflict(CALLBACK_REF)
                .doUpdate()
                .set(CALLBACK_STATE, callback.state())
                .set(CALLBACK_DETAIL, callback.detail())
                .set(CALLBACK_RECEIVED, callback.receivedAt().atOffset(ZoneOffset.UTC))
                .execute();
    }
}
