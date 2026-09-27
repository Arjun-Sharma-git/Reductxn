package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ActivityEvent;
import io.reductxn.api.port.out.ActivityFeed;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group's history, read from the three tables that recorded it.
 *
 * <p><strong>Three statements merged in memory, not one `UNION ALL`.</strong> The sources do not have the same columns - a
 * membership has a name, a decision has an answer, an invitation has a use count - so a union would pad them into one shape
 * in SQL and what makes each row what it is would become a null pattern rather than a column. Three reads, each clamped to
 * the requested page, merged by {@link ActivityEvent#NEWEST_FIRST} and truncated: the newest overall event cannot be known
 * without asking each source for its newest, and the page size bounds the work rather than the table sizes.
 *
 * <p><strong>Qualified fields everywhere, because the trap is real here.</strong> Two of the three reads are single-table,
 * but they share a class with one that joins - and a field written without its table's name beside a join is ambiguous at
 * runtime rather than at compile time (the trap {@code JooqCycles} and {@code JooqGroups} both document).
 */
@Repository
public class JooqActivityFeed implements ActivityFeed {

    private static final Table<?> MEMBERS = DSL.table("group_members");
    private static final Table<?> INVITATIONS = DSL.table("invitations");
    private static final Table<?> DECISIONS = DSL.table("settlement_decisions").as("d");
    private static final Table<?> RUNS = DSL.table("settlement_runs").as("r");

    private static final Field<UUID> MEMBER_GROUP = DSL.field(DSL.name("group_members", "group_id"), UUID.class);
    private static final Field<UUID> MEMBER_ID = DSL.field(DSL.name("group_members", "user_id"), UUID.class);
    private static final Field<String> MEMBER_NAME = DSL.field(DSL.name("group_members", "display_name"), String.class);
    private static final Field<String> MEMBER_ROLE = DSL.field(DSL.name("group_members", "role"), String.class);
    private static final Field<OffsetDateTime> MEMBER_JOINED_AT =
            DSL.field(DSL.name("group_members", "joined_at"), OffsetDateTime.class);

    private static final Field<UUID> INVITE_GROUP = DSL.field(DSL.name("invitations", "group_id"), UUID.class);
    private static final Field<Integer> INVITE_USES = DSL.field(DSL.name("invitations", "uses"), Integer.class);
    private static final Field<Integer> INVITE_MAX_USES = DSL.field(DSL.name("invitations", "max_uses"), Integer.class);
    private static final Field<OffsetDateTime> INVITE_CREATED_AT =
            DSL.field(DSL.name("invitations", "created_at"), OffsetDateTime.class);

    private static final Field<UUID> DECISION_RUN = DSL.field(DSL.name("d", "run_id"), UUID.class);
    private static final Field<UUID> DECISION_MEMBER = DSL.field(DSL.name("d", "member_id"), UUID.class);
    private static final Field<String> DECISION_ANSWER = DSL.field(DSL.name("d", "decision"), String.class);
    private static final Field<OffsetDateTime> DECISION_AT =
            DSL.field(DSL.name("d", "decided_at"), OffsetDateTime.class);

    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("r", "id"), UUID.class);
    private static final Field<UUID> RUN_GROUP = DSL.field(DSL.name("r", "group_id"), UUID.class);

    private final DSLContext dsl;

    public JooqActivityFeed(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<ActivityEvent> recent(UUID groupId, int limit) {
        Objects.requireNonNull(groupId, "groupId");

        List<ActivityEvent> events = new ArrayList<>(joins(groupId, limit));

        events.addAll(decisions(groupId, limit));
        events.addAll(invitations(groupId, limit));

        return events.stream()
                .sorted(ActivityEvent.NEWEST_FIRST)
                .limit(limit)
                .toList();
    }

    private static Instant instant(OffsetDateTime at) {
        return at.toInstant();
    }

    /** Somebody joined: the membership row is the record, and its own instant is when it happened. */
    private List<ActivityEvent> joins(UUID groupId, int limit) {
        return dsl.select(MEMBER_ID, MEMBER_NAME, MEMBER_ROLE, MEMBER_JOINED_AT)
                .from(MEMBERS)
                .where(MEMBER_GROUP.eq(groupId))
                .orderBy(MEMBER_JOINED_AT.desc(), MEMBER_ID.asc())
                .limit(limit)
                .fetch(row -> ActivityEvent.memberJoined(
                        row.get(MEMBER_ID),
                        row.get(MEMBER_NAME),
                        row.get(MEMBER_ROLE),
                        instant(row.get(MEMBER_JOINED_AT))));
    }

    /**
     * Somebody answered a settlement run.
     *
     * <p>The join is the point: a decision names a run and a member and never a group, so this is where a decision becomes
     * the group's business. A run knows its group directly - which is why it is one join, not the two a reader might expect
     * from "a run belongs to a cycle" (ADR 0044).
     */
    private List<ActivityEvent> decisions(UUID groupId, int limit) {
        return dsl.select(DECISION_MEMBER, DECISION_ANSWER, DECISION_AT)
                .from(DECISIONS)
                .join(RUNS)
                .on(RUN_ID.eq(DECISION_RUN))
                .where(RUN_GROUP.eq(groupId))
                .orderBy(DECISION_AT.desc(), DECISION_MEMBER.asc())
                .limit(limit)
                .fetch(row -> ActivityEvent.decisionRecorded(
                        row.get(DECISION_MEMBER),
                        row.get(DECISION_ANSWER),
                        instant(row.get(DECISION_AT))));
    }

    /**
     * A code was created.
     *
     * <p>No member, and no code either: the row names nobody - the create operation is unauthenticated in this build
     * (ADR 0041) - and a code is a secret the group shares, so a feed listing live codes would be a page somebody's shoulder
     * could read. What a member needs is that one exists and how much of it is left.
     */
    private List<ActivityEvent> invitations(UUID groupId, int limit) {
        return dsl.select(INVITE_USES, INVITE_MAX_USES, INVITE_CREATED_AT)
                .from(INVITATIONS)
                .where(INVITE_GROUP.eq(groupId))
                .orderBy(INVITE_CREATED_AT.desc())
                .limit(limit)
                .fetch(row -> ActivityEvent.invitationCreated(
                        row.get(INVITE_USES),
                        row.get(INVITE_MAX_USES),
                        instant(row.get(INVITE_CREATED_AT))));
    }
}
