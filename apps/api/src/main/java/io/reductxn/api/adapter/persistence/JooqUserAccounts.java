package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.UserAccounts;

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
 * {@code users}, as jOOQ: this module's own table (MASTER-PLAN section 5.5).
 *
 * <p><strong>The find-or-create is one statement and a read, and the constraint is what makes it true.</strong>
 * {@code users_phone_numbers_are_unique} settles the race two verifications of one number can have: both insert, one does
 * nothing, and the read that follows answers with the row that exists. Asking "does this number exist" first would be the
 * check-then-insert that both loses the race and reports it as a failure - the same shape {@code JooqGroupMembers.add}
 * documents for a membership, and the same one {@code ON CONFLICT DO NOTHING} is for.
 *
 * <p><strong>The id is generated here rather than passed in.</strong> It is the row's own identity and nothing outside
 * needs to know it before the row exists; the caller gets it back in the answer. This is where an invite's code is drawn
 * for the same reason: what has to be unique has to be drawn beside the constraint that makes it unique.
 */
@Repository
public class JooqUserAccounts implements UserAccounts {

    private static final Table<Record> USERS = DSL.table("users");

    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<String> PHONE_NUMBER = DSL.field("phone_number", String.class);
    private static final Field<String> DISPLAY_NAME = DSL.field("display_name", String.class);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqUserAccounts(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Account findOrCreate(String phoneNumber, String displayName, Instant createdAt) {
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(createdAt, "createdAt");

        dsl.insertInto(USERS)
                .columns(ID, PHONE_NUMBER, DISPLAY_NAME, CREATED_AT)
                .values(UUID.randomUUID(), phoneNumber, displayName, at(createdAt))
                .onConflict(PHONE_NUMBER)
                .doNothing()
                .execute();

        Record row = dsl.select(ID, PHONE_NUMBER, DISPLAY_NAME)
                .from(USERS)
                .where(PHONE_NUMBER.eq(phoneNumber))
                .fetchOne();

        if (row == null) {
            throw new IllegalStateException(
                    "the account for " + phoneNumber + " was inserted or found and cannot be read back. The number is "
                            + "unique, so this means the row went somewhere else");
        }

        // The display name of an existing row is deliberately not updated: a second sign-in is not a rename, and this
        // build has no operation that renames, so a request must not be able to.
        return new Account(row.get(ID), row.get(PHONE_NUMBER), row.get(DISPLAY_NAME));
    }

    @Override
    public Optional<Account> findById(UUID id) {
        Objects.requireNonNull(id, "id");

        return dsl.select(ID, PHONE_NUMBER, DISPLAY_NAME)
                .from(USERS)
                .where(ID.eq(id))
                .fetchOptional()
                .map(row -> new Account(row.get(ID), row.get(PHONE_NUMBER), row.get(DISPLAY_NAME)));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
