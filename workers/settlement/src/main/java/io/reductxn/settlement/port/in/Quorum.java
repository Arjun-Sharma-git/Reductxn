package io.reductxn.settlement.port.in;

import io.reductxn.netting.api.NetPosition;
import io.reductxn.planning.api.FxClearing;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Who has to agree before a run may execute, and who has agreed (RULE S1).
 *
 * <p>The policy is arithmetic over three sets, and all three are derived from the run's own rows:
 *
 * <ul>
 *   <li><strong>Members</strong> are the participants in the run's plan, minus {@code fx_clearing}. The
 *       clearing account is a participant so that a cross-currency cycle nets correctly (ADR 0012 point 3),
 *       but it is not a person and cannot approve anything. Counting it would let a group reach quorum with
 *       its own clearing account's assent.</li>
 *   <li><strong>Debtors</strong> are members with a negative position in any currency. The spec's rule is
 *       sharper than a majority on its own: <em>every member who owes a net amount must confirm
 *       personally</em>. A majority that excluded everyone who pays would be a group authorising money from
 *       accounts it does not hold.</li>
 *   <li><strong>Approvers</strong> and <strong>rejecters</strong> are what members have decided. A rejection
 *       is recorded and ends the run (see {@link #rejected()}), rather than being the absence of an
 *       approval - "no" and "not yet" are different answers and the spec has a state for each.</li>
 * </ul>
 *
 * <p><strong>Why membership is taken from the plan rather than from a members table.</strong> There is no
 * such table: groups and their membership are {@code apps/api}'s, and no migration creates them yet, so a
 * quorum over "the members of the group" would be a quorum over nothing - which passes. The plan's
 * participants are the people whose money this run moves, they are already stored with the run, and asking
 * exactly those people is defensible rather than merely convenient. What it is not is the spec's sentence
 * read literally, and that difference is a decision, so it is recorded as ADR 0016 and named as the thing
 * that changes when membership exists. It does not make quorum easier to reach: a run's netting can touch
 * fewer people than the group has, and the people it does touch are the ones whose balances it changes.
 *
 * @param members   every member the run concerns, in a stable order
 * @param debtors   those of them who owe a net amount in at least one currency
 * @param approvers who has approved
 * @param rejecters who has rejected
 */
public record Quorum(Set<UUID> members, Set<UUID> debtors, Set<UUID> approvers, Set<UUID> rejecters) {

    public Quorum {
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(debtors, "debtors");
        Objects.requireNonNull(approvers, "approvers");
        Objects.requireNonNull(rejecters, "rejecters");

        // Sorted, and as unmodifiable *sorted* sets rather than Set.copyOf: `Set.copyOf` gives no iteration
        // order at all, so two readings of the same rows would list the same members in different orders and a
        // report an auditor diffed would look different every time. A TreeSet keeps the order stable.
        members = sorted(members);
        debtors = sorted(debtors);
        approvers = sorted(approvers);
        rejecters = sorted(rejecters);

        for (UUID debtor : debtors) {
            if (!members.contains(debtor)) {
                throw new IllegalArgumentException(
                        "debtor " + debtor + " is not one of the run's members, so the requirement that every "
                                + "debtor confirms personally could never be satisfied");
            }
        }
    }

    private static Set<UUID> sorted(Set<UUID> members) {
        return Collections.unmodifiableSet(new TreeSet<>(members));
    }

    /**
     * The rule, from the run's own rows: who is in the run, told apart from the clearing account.
     *
     * <p>A pure function over stored positions, so it is testable without a database and so the answer to
     * "who has to approve" cannot depend on how a row was fetched.
     *
     * @param positions every net position of the run, across every currency
     * @param approvers who has approved
     * @param rejecters who has rejected
     */
    public static Quorum of(List<NetPosition> positions, Set<UUID> approvers, Set<UUID> rejecters) {
        Objects.requireNonNull(positions, "positions");

        Set<UUID> members = new LinkedHashSet<>();
        Set<UUID> debtors = new LinkedHashSet<>();

        for (NetPosition position : positions) {
            // The clearing account is a participant and not a member. Its position is the group's currency
            // gap, and it has no one to ask.
            if (FxClearing.isClearing(position)) {
                continue;
            }

            members.add(position.userId());

            if (position.net().isNegative()) {
                debtors.add(position.userId());
            }
        }

        return new Quorum(members, debtors, approvers == null ? Set.of() : approvers,
                rejecters == null ? Set.of() : rejecters);
    }

    /**
     * How many approvals a majority needs: strictly more than half.
     *
     * <p>For an empty run this is one, which is why {@link #met()} refuses an empty member set separately
     * rather than leaning on the arithmetic - "1 of 0 approved" is not a statement anyone should read as
     * satisfied.
     */
    public int required() {
        return Math.floorDiv(members.size(), 2) + 1;
    }

    /** Debtors who have not approved: the reason a majority is not enough. */
    public Set<UUID> outstandingDebtors() {
        Set<UUID> outstanding = new TreeSet<>(debtors);
        outstanding.removeAll(approvers);
        return Collections.unmodifiableSet(outstanding);
    }

    /**
     * The approvals that count: those of members.
     *
     * <p><strong>An approval from someone who is not a member is reported and never counted.</strong> The use case
     * refuses such an answer before it is recorded, so this should be empty of surprises - but the guard that
     * matters is the one that holds if the rows are wrong, and a quorum that a hand-written row could satisfy is
     * not a quorum. The stranger stays visible in {@link #approvers()} rather than being filtered out of the
     * report: a fact that is hidden is a fact nobody fixes.
     */
    public Set<UUID> countedApprovals() {
        Set<UUID> counted = new TreeSet<>(approvers);
        counted.retainAll(members);
        return Collections.unmodifiableSet(counted);
    }

    /** Whether the run has quorum (RULE S1): a majority of members, and every debtor personally. */
    public boolean met() {
        if (members.isEmpty()) {
            return false;
        }
        return countedApprovals().size() >= required() && outstandingDebtors().isEmpty();
    }
    /** Everyone who has answered, either way. A member who has answered is not asked twice. */
    public Set<UUID> decided() {
        Set<UUID> decided = new TreeSet<>(approvers);
        decided.addAll(rejecters);
        return Collections.unmodifiableSet(decided);
    }

    /**
     * The rejections that count: those of members.
     *
     * <p><strong>The same intersection as {@link #countedApprovals()}, and it matters more here.</strong> An
     * approval from somebody the run does not concern is inert - the run still needs its own members - but a
     * rejection is *terminal*: it ends the run. Reading the raw rows would let anybody stop any group's settlement
     * by writing one decision row, and until identity exists the row's {@code member_id} is a claim a client makes
     * rather than a fact the server knows (ADR 0016, and the same reason a stranger's approval is shown and not
     * counted). The asymmetry with approvals is exactly why this is not the same method: an unguarded approval
     * makes a quorum harder, an unguarded rejection makes a settlement impossible.
     *
     * <p>{@link #rejecters()} still reports every row that was written, for the reason its sibling gives: a fact
     * that is hidden is a fact nobody fixes.
     */
    public Set<UUID> countedRejections() {
        Set<UUID> counted = new TreeSet<>(rejecters);
        counted.retainAll(members);
        return Collections.unmodifiableSet(counted);
    }

    /**
     * Whether a member has refused, which ends the run.
     *
     * <p>A single rejection is enough, and that is a decision rather than an oversight. The spec says approval
     * is granted once for the whole run - the group approves one settlement of one set of commitments, not a
     * per-currency series of them - so the run is all-or-nothing, and a member saying "not this" is not a
     * minority whose objection is outvoted. What it must not do is *execute*: nothing has moved, and the run
     * goes to {@code rejected}, where the group can open another cycle.
     */
    public boolean rejected() {
        return !countedRejections().isEmpty();
    }
}
