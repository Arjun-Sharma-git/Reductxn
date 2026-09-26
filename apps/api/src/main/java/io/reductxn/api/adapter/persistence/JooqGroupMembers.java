package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.GroupMember;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.out.GroupMembers;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code group_members}, as jOOQ: this module's own table (MASTER-PLAN section 5.5), so the statements here read
 * nothing that another deployable owns and no read model has to cross a module boundary.
 *
 * <p><strong>Every statement in this class names exactly one table, which is why the column constants below are
 * unqualified.</strong> That is the property to check before reusing them somewhere else: the cycles adapter keeps a
 * second, qualified set of fields because its reads join the worker's runs, and adding that join to unqualified
 * constants turns {@code id} into a runtime error no compiler can see. Nothing here joins, and if something ever
 * does, the fields move with it.
 */
@Repository
public class JooqGroupMembers implements GroupMembers {

    private static final Table<Record> MEMBERS = DSL.table("group_members");

    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<UUID> USER_ID = DSL.field("user_id", UUID.class);
    private static final Field<String> DISPLAY_NAME = DSL.field("display_name", String.class);
    private static final Field<String> ROLE = DSL.field("role", String.class);
    private static final Field<OffsetDateTime> JOINED_AT = DSL.field("joined_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqGroupMembers(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<GroupMember> listByGroup(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");

        return dsl.select(GROUP_ID, USER_ID, DISPLAY_NAME, ROLE)
                .from(MEMBERS)
                .where(GROUP_ID.eq(groupId))
                // The index the migration created for this read: `group_members_by_group (group_id, joined_at,
                // user_id)`. The tie-break is on the id, so a group whose members joined in the same millisecond -
                // a seeded group, or a test - still has one order rather than whichever the rows came back in.
                .orderBy(JOINED_AT.asc(), USER_ID.asc())
                .fetch()
                .map(JooqGroupMembers::toMember);
    }

    @Override
    public AddedMember add(NewMember member) {
        Objects.requireNonNull(member, "member");

        int inserted = dsl.insertInto(MEMBERS)
                .columns(GROUP_ID, USER_ID, DISPLAY_NAME, ROLE, JOINED_AT)
                .values(member.groupId(), member.userId(), member.displayName(), member.role(),
                        member.joinedAt().atOffset(ZoneOffset.UTC))
                .onConflict(GROUP_ID, USER_ID)
                .doNothing()
                .execute();

        Record row = dsl.select(GROUP_ID, USER_ID, DISPLAY_NAME, ROLE)
                .from(MEMBERS)
                .where(GROUP_ID.eq(member.groupId()))
                .and(USER_ID.eq(member.userId()))
                .fetchOne();

        if (row == null) {
            throw new IllegalStateException(
                    "the membership of " + member.userId() + " in group " + member.groupId() + " was inserted or "
                            + "found and cannot be read back. The pair is the primary key, so this means the row went "
                            + "somewhere else");
        }

        // The request's name and role are deliberately not read back into this answer: the answer is the row, and a
        // second request for a member the group already has must not look like it changed anything (see
        // `GroupMembers.add` for why this is not an upsert).
        return new AddedMember(toMember(row), inserted > 0);
    }

    @Override
    public boolean has(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");

        // One statement, and the primary key `(group_id, user_id)` serves it: this is a lookup on the key rather than a
        // scan of the group, which is the same reason `add` can rely on the pair being unique.
        return 0 < dsl.fetchCount(dsl.selectOne()
                .from(MEMBERS)
                .where(GROUP_ID.eq(groupId))
                .and(USER_ID.eq(userId)));
    }

    private static GroupMember toMember(Record row) {
        return new GroupMember(row.get(USER_ID), row.get(DISPLAY_NAME), row.get(ROLE));
    }
}
