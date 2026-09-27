package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.Groups;
import io.reductxn.ledger.api.Currency;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.SelectWhereStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code groups}, as jOOQ: this module's own table, joined to the memberships that decide who can see a row.
 *
 * <p><strong>Two sets of column constants, and the reason is a trap this repository has already been caught by.</strong>
 * An insert's columns must be *unqualified* - jOOQ writes {@code INSERT INTO groups (name, …)} and a qualified name there
 * is not valid - while a join's fields must be *qualified*, because an unqualified {@code field("id")} beside another
 * table's columns becomes ambiguous at runtime rather than at compile time (the failure {@code JooqCycles} documents, and
 * the reason its constants move with its joins). The bare names are used by the one insert here; the {@code g.}-qualified
 * ones by every read.
 *
 * <p><strong>Counting a group's members needs a second join.</strong> The read answers "the groups I am in, each with how
 * many members it has", and those are two different relationships to {@code group_members}: the caller's membership
 * selects the group ({@code mine}), and the group's membership set is what the count is over ({@code everyone}). One join
 * cannot do both - filtering by it and counting it would count only the caller. The count is {@code count(everyone.user_id)}
 * rather than {@code count(*)} because with no members the left-joined row still exists and {@code count(*)} would answer
 * one.
 *
 * <p><strong>A replay is settled by the unique index, not by a read.</strong> {@code ON CONFLICT (created_by,
 * idempotency_key) DO NOTHING} means two retries racing on one key produce one row and one recognisable replay, and the
 * read-back answers with whichever row exists. Asking "has this key been used" first would lose that race and report it as
 * a failure.
 */
@Repository
public class JooqGroups implements Groups {

    private static final Table<Record> GROUP_ROWS = DSL.table("groups");

    // Unqualified: the insert's columns.
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<String> NAME = DSL.field("name", String.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<Short> CURRENCY_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<String> REPORTING_CODE = DSL.field("reporting_currency_code", String.class);
    private static final Field<Short> REPORTING_EXPONENT = DSL.field("reporting_currency_exponent", Short.class);
    private static final Field<String> SETTLEMENT_TIMEZONE = DSL.field("settlement_timezone", String.class);
    private static final Field<Integer> MAX_MEMBERS = DSL.field("max_members", Integer.class);
    private static final Field<UUID> CREATED_BY = DSL.field("created_by", UUID.class);
    private static final Field<String> IDEMPOTENCY_KEY = DSL.field("idempotency_key", String.class);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", OffsetDateTime.class);

    // Qualified, and aliased: the read joins two tables, so an unqualified field would be ambiguous at runtime rather than
    // at compile time - the failure JooqCycles documents. `mine` is the caller's membership, which selects the group;
    // `everyone` is the group's whole membership, which is what the count is over.
    private static final Table<Record> GROUPS = DSL.table("groups").as("g");
    private static final Table<Record> MINE = DSL.table("group_members").as("mine");
    private static final Table<Record> EVERYONE = DSL.table("group_members").as("everyone");

    private static final Field<UUID> GROUP_ID = DSL.field(DSL.name("g", "id"), UUID.class);
    private static final Field<String> GROUP_NAME = DSL.field(DSL.name("g", "name"), String.class);
    private static final Field<String> GROUP_CURRENCY_CODE = DSL.field(DSL.name("g", "currency_code"), String.class);
    private static final Field<Short> GROUP_CURRENCY_EXPONENT =
            DSL.field(DSL.name("g", "currency_exponent"), Short.class);
    private static final Field<String> GROUP_REPORTING_CODE =
            DSL.field(DSL.name("g", "reporting_currency_code"), String.class);
    private static final Field<Short> GROUP_REPORTING_EXPONENT =
            DSL.field(DSL.name("g", "reporting_currency_exponent"), Short.class);
    private static final Field<String> GROUP_TIMEZONE = DSL.field(DSL.name("g", "settlement_timezone"), String.class);
    private static final Field<Integer> GROUP_MAX_MEMBERS = DSL.field(DSL.name("g", "max_members"), Integer.class);
    private static final Field<OffsetDateTime> GROUP_CREATED_AT =
            DSL.field(DSL.name("g", "created_at"), OffsetDateTime.class);
    private static final Field<UUID> CREATED_BY_QUALIFIED = DSL.field(DSL.name("g", "created_by"), UUID.class);
    private static final Field<String> IDEMPOTENCY_KEY_QUALIFIED =
            DSL.field(DSL.name("g", "idempotency_key"), String.class);

    private static final Field<UUID> MY_MEMBERSHIP = DSL.field(DSL.name("mine", "group_id"), UUID.class);
    private static final Field<UUID> MY_USER = DSL.field(DSL.name("mine", "user_id"), UUID.class);
    private static final Field<OffsetDateTime> MY_JOINED_AT =
            DSL.field(DSL.name("mine", "joined_at"), OffsetDateTime.class);

    private static final Field<UUID> THEIR_GROUP = DSL.field(DSL.name("everyone", "group_id"), UUID.class);
    /**
     * How many members a group has, as a correlated subquery rather than a second join.
     *
     * <p><strong>It has to be a subquery, and the reason is the create path.</strong> The read that finds a group by its
     * creator's key runs *before* the creator's membership is written - the group and the membership are two writes, and the
     * group comes first - so a read that required a membership could never find the row it had just inserted, which is
     * exactly what the first version of this adapter did. A correlated count answers zero members for a group nobody has
     * joined yet, which is the truth at that instant, and the same field serves the two reads that do join memberships.
     */
    private static final Field<Integer> MEMBER_COUNT =
            DSL.field(DSL.select(DSL.count()).from(EVERYONE).where(THEIR_GROUP.eq(GROUP_ID)));

    private final DSLContext dsl;

    public JooqGroups(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Created create(NewGroupRow row) {
        Objects.requireNonNull(row, "row");

        int inserted = dsl.insertInto(GROUP_ROWS)
                .columns(ID, NAME, CURRENCY_CODE, CURRENCY_EXPONENT, REPORTING_CODE, REPORTING_EXPONENT,
                        SETTLEMENT_TIMEZONE, MAX_MEMBERS, CREATED_BY, IDEMPOTENCY_KEY, CREATED_AT)
                .values(row.id(), row.name(), row.currency().code(), (short) row.currency().minorUnitExponent(),
                        row.reportingCurrency().code(), (short) row.reportingCurrency().minorUnitExponent(),
                        row.settlementTimezone(), row.maxMembers(), row.createdBy(), row.idempotencyKey(),
                        at(row.createdAt()))
                .onConflict(CREATED_BY, IDEMPOTENCY_KEY)
                .doNothing()
                .execute();

        // The read-back is by the key rather than by the id this call generated: on a replay the row is the one the first
        // attempt wrote, and its id is the answer the caller is owed.
        Optional<StoredGroup> stored = byCreatorKey(row.createdBy(), row.idempotencyKey());

        if (stored.isEmpty()) {
            throw new IllegalStateException(
                    "the group created under key " + row.idempotencyKey() + " was inserted or found and cannot be read "
                            + "back. The key is unique per creator, so this means the row went somewhere else");
        }

        return new Created(stored.get(), inserted > 0);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * The read's fields, in the one place: the group's row and the count of everybody in it.
     *
     * <p>The caller's membership is an inner join, so a group this person is not in simply is not a row - the narrowing
     * happens in the database rather than in the service, which is what keeps "is this group yours" from being a check
     * somebody could forget to write.
     */
    private static SelectJoinStep<Record> membership(DSLContext dsl) {
        return dsl.select(projection()).from(GROUPS).join(MINE).on(MY_MEMBERSHIP.eq(GROUP_ID));
    }

    /** A group's row on its own, which is what the create path's read-back needs: no membership exists yet. */
    private static SelectWhereStep<Record> row(DSLContext dsl) {
        return dsl.select(projection()).from(GROUPS);
    }

    /**
     * What the read selects, as an untyped field list.
     *
     * <p>The list rather than the varargs form on purpose: jOOQ's varargs overload returns a typed record whose arity is
     * part of its type, so a projection of ten columns makes every helper that builds on it spell out
     * {@code SelectOnConditionStep<Record10<UUID, String, …>>}. A {@code List<Field<?>>} keeps the row a {@code Record}, and
     * the reader's {@code row.get(field)} calls are checked by the same constants that produced it.
     */
    private static List<Field<?>> projection() {
        return List.of(GROUP_ID, GROUP_NAME, GROUP_CURRENCY_CODE, GROUP_CURRENCY_EXPONENT, GROUP_REPORTING_CODE,
                GROUP_REPORTING_EXPONENT, GROUP_TIMEZONE, GROUP_MAX_MEMBERS, GROUP_CREATED_AT, MEMBER_COUNT);
    }

    @Override
    public List<StoredGroup> ofMember(UUID userId) {
        Objects.requireNonNull(userId, "userId");

        return membership(dsl)
                .where(MY_USER.eq(userId))
                // Most recently joined first, because the group somebody just created should be the first thing they see.
                // The id breaks the tie, so a page is total rather than dependent on how the database felt.
                .orderBy(MY_JOINED_AT.desc(), GROUP_ID.asc())
                .fetch(JooqGroups::toStored);
    }

    @Override
    public Optional<StoredGroup> forMember(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");

        return membership(dsl)
                .where(GROUP_ID.eq(groupId))
                .and(MY_USER.eq(userId))
                .fetchOptional(JooqGroups::toStored);
    }

    /**
     * The row this creator's key already made, with no membership in the query.
     *
     * <p>It cannot require one: on the create path this read runs before the creator's membership is written, and on a
     * replay it runs after - and it has to answer the same shape of row in both. The count is the correlated subquery, so it
     * reports zero for a group nobody has joined yet and one for one whose creator has.
     */
    private Optional<StoredGroup> byCreatorKey(UUID createdBy, String idempotencyKey) {
        return row(dsl)
                .where(CREATED_BY_QUALIFIED.eq(createdBy))
                .and(IDEMPOTENCY_KEY_QUALIFIED.eq(idempotencyKey))
                .fetchOptional(JooqGroups::toStored);
    }

    /**
     * One row, as the API's group.
     *
     * <p>{@code settlement_timezone} is read as a nullable string and kept null rather than defaulted: "this group has not
     * chosen a zone" is the state the contract makes optional, and a default here would be this layer inventing a decision
     * about which cycle an obligation falls in (ADR 0014, point 6).
     */
    private static StoredGroup toStored(Record row) {
        return new StoredGroup(
                row.get(GROUP_ID),
                row.get(GROUP_NAME),
                Currency.of(row.get(GROUP_CURRENCY_CODE), row.get(GROUP_CURRENCY_EXPONENT)),
                Currency.of(row.get(GROUP_REPORTING_CODE), row.get(GROUP_REPORTING_EXPONENT)),
                row.get(GROUP_TIMEZONE),
                row.get(MEMBER_COUNT),
                row.get(GROUP_MAX_MEMBERS),
                row.get(GROUP_CREATED_AT).toInstant());
    }
}
