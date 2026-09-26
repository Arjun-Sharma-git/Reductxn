package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.OtpChallenges;

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
 * {@code otp_challenges}, as jOOQ: this module's own table.
 *
 * <p><strong>Every statement in this class names exactly one table</strong>, which is why the column constants are
 * unqualified - the property {@code JooqGroupMembers} documents as the thing to check before reusing them elsewhere.
 * Nothing here joins; if something ever does, the fields move with it.
 *
 * <p><strong>{@link #newestUnspent} orders by request time and takes one, and that ordering is the rule.</strong> Two
 * codes in flight for one number is ordinary - somebody asked twice - and the code that should work is the one that
 * arrived last, because that is the one the person is reading off their phone. It does *not* filter by expiry: the
 * service judges that, because a store that filtered would make an expired code look like no code at all, and the refusal
 * deliberately keeps those the same answer. The same reasoning puts {@code attempts} in the answer rather than in the
 * {@code WHERE} clause: the count is a fact, and the rule about it is not this layer's.
 */
@Repository
public class JooqOtpChallenges implements OtpChallenges {

    private static final Table<Record> CHALLENGES = DSL.table("otp_challenges");

    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<String> PHONE_NUMBER = DSL.field("phone_number", String.class);
    private static final Field<byte[]> CODE_HASH = DSL.field("code_hash", byte[].class);
    private static final Field<byte[]> SALT = DSL.field("salt", byte[].class);
    private static final Field<Integer> ATTEMPTS = DSL.field("attempts", Integer.class);
    private static final Field<OffsetDateTime> REQUESTED_AT = DSL.field("requested_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> EXPIRES_AT = DSL.field("expires_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> SPENT_AT = DSL.field("spent_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqOtpChallenges(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void record(NewChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge");

        dsl.insertInto(CHALLENGES)
                .columns(ID, PHONE_NUMBER, CODE_HASH, SALT, REQUESTED_AT, EXPIRES_AT)
                .values(challenge.id(), challenge.phoneNumber(), challenge.codeHash(), challenge.salt(),
                        at(challenge.requestedAt()), at(challenge.expiresAt()))
                .execute();
    }

    @Override
    public Optional<StoredChallenge> newestUnspent(String phoneNumber) {
        Objects.requireNonNull(phoneNumber, "phoneNumber");

        return dsl.select(ID, CODE_HASH, SALT, ATTEMPTS, EXPIRES_AT)
                .from(CHALLENGES)
                .where(PHONE_NUMBER.eq(phoneNumber))
                .and(SPENT_AT.isNull())
                .orderBy(REQUESTED_AT.desc())
                .limit(1)
                .fetchOptional()
                .map(row -> new StoredChallenge(
                        row.get(ID),
                        row.get(CODE_HASH),
                        row.get(SALT),
                        row.get(ATTEMPTS),
                        row.get(EXPIRES_AT).toInstant()));
    }

    @Override
    public void spend(UUID id, Instant at) {
        Objects.requireNonNull(id, "id");

        // Conditional on being unspent, so two verifications racing on one code produce one session: the loser updates
        // nothing. The service has already decided this code is good; this is what makes that decision hold under
        // concurrency rather than only in one request.
        dsl.update(CHALLENGES)
                .set(SPENT_AT, at(at))
                .where(ID.eq(id))
                .and(SPENT_AT.isNull())
                .execute();
    }

    @Override
    public void registerAttempt(UUID id) {
        Objects.requireNonNull(id, "id");

        dsl.update(CHALLENGES)
                .set(ATTEMPTS, ATTEMPTS.plus(1))
                .where(ID.eq(id))
                .and(SPENT_AT.isNull())
                .execute();
    }

    @Override
    public int countRequestedSince(String phoneNumber, Instant since) {
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(since, "since");

        return dsl.fetchCount(dsl.selectOne()
                .from(CHALLENGES)
                .where(PHONE_NUMBER.eq(phoneNumber))
                .and(REQUESTED_AT.gt(at(since))));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
