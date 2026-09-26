package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.Sessions;

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
import java.util.Optional;
import java.util.UUID;

/**
 * {@code sessions}, as jOOQ: this module's own table. Every statement names one table.
 *
 * <p><strong>{@link #rotate} is where rotation's promise is kept, and the {@code WHERE} clause is the promise.</strong>
 * The row is found by the old refresh token's hash *and* a refresh window that has not closed, and only the two token
 * hashes and the access expiry are rewritten: {@code refresh_expires_at} is left exactly where it was, which is what
 * stops a rotation from extending a session. A replay of the old refresh token finds no row - so it cannot produce a
 * second live pair - and two rotations racing on one token produce one winner, because the loser's {@code WHERE} no
 * longer matches.
 */
@Repository
public class JooqSessions implements Sessions {

    private static final Table<Record> SESSIONS = DSL.table("sessions");

    private static final Field<String> ACCESS_TOKEN_HASH = DSL.field("access_token_hash", String.class);
    private static final Field<String> REFRESH_TOKEN_HASH = DSL.field("refresh_token_hash", String.class);
    private static final Field<UUID> USER_ID = DSL.field("user_id", UUID.class);
    private static final Field<String> DEVICE_ID = DSL.field("device_id", String.class);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> ACCESS_EXPIRES_AT = DSL.field("access_expires_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> REFRESH_EXPIRES_AT = DSL.field("refresh_expires_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqSessions(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void create(NewSession session) {
        Objects.requireNonNull(session, "session");

        dsl.insertInto(SESSIONS)
                .columns(ACCESS_TOKEN_HASH, REFRESH_TOKEN_HASH, USER_ID, DEVICE_ID, CREATED_AT, ACCESS_EXPIRES_AT,
                        REFRESH_EXPIRES_AT)
                .values(session.accessTokenHash(), session.refreshTokenHash(), session.userId(), session.deviceId(),
                        at(session.createdAt()), at(session.accessExpiresAt()), at(session.refreshExpiresAt()))
                .execute();
    }

    @Override
    public Optional<AuthenticatedSession> findByAccessTokenHash(String accessTokenHash) {
        Objects.requireNonNull(accessTokenHash, "accessTokenHash");

        // The primary key is this hash, so this is a key lookup: every authenticated request in this product is one
        // index hit, which is the whole reason the token is not a JWT (ADR 0042).
        return dsl.select(USER_ID, ACCESS_EXPIRES_AT, REFRESH_EXPIRES_AT)
                .from(SESSIONS)
                .where(ACCESS_TOKEN_HASH.eq(accessTokenHash))
                .fetchOptional()
                .map(row -> new AuthenticatedSession(
                        row.get(USER_ID),
                        row.get(ACCESS_EXPIRES_AT).toInstant(),
                        row.get(REFRESH_EXPIRES_AT).toInstant()));
    }

    @Override
    public boolean rotate(String refreshTokenHash, RotatedTokens replacement) {
        Objects.requireNonNull(refreshTokenHash, "refreshTokenHash");
        Objects.requireNonNull(replacement, "replacement");

        int rotated = dsl.update(SESSIONS)
                .set(ACCESS_TOKEN_HASH, replacement.accessTokenHash())
                .set(REFRESH_TOKEN_HASH, replacement.refreshTokenHash())
                .set(ACCESS_EXPIRES_AT, at(replacement.accessExpiresAt()))
                .where(REFRESH_TOKEN_HASH.eq(refreshTokenHash))
                .and(REFRESH_EXPIRES_AT.gt(at(replacement.now())))
                .execute();

        return rotated > 0;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
