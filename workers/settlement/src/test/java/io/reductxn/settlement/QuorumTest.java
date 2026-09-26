package io.reductxn.settlement;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.settlement.port.in.Quorum;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE S1, without a database and without Spring.
 *
 * <p>This is the half of the quorum rule that can be tested as arithmetic, and it is deliberately tested that
 * way: the rule is what a run's rows mean, and a test that had to start a container to ask "is one approval
 * enough for three members" would make the rule look like a property of a query. The container tests in
 * {@code DecideOnRunTest} then check that the right rows reach this code.
 */
class QuorumTest {

    private static final UUID ALICE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID BORIS = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID CAROL = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID DAVE = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID ERIN = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID STRANGER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private static final Currency INR = Currency.of("INR", 2);

    @Test
    void aMajorityOfTheRunsMembersIsRequired() {
        List<NetPosition> positions = List.of(position(ALICE, -10_000L), position(BORIS, 10_000L));

        assertThat(quorum(positions, Set.of()).required())
                .as("two members need two approvals, because a majority of two is more than one - and a "
                        + "settlement between two people that either of them could wave through is not an "
                        + "agreement")
                .isEqualTo(2);

        assertThat(quorum(positions, Set.of(ALICE)).met()).isFalse();
        assertThat(quorum(positions, Set.of(ALICE, BORIS)).met()).isTrue();
    }

    @Test
    void everyDebtorMustConfirmPersonally() {
        // Five members, two of whom owe. A majority is three, and three creditors agreeing is not enough.
        List<NetPosition> positions = List.of(
                position(ALICE, -10_000L),
                position(BORIS, -5_000L),
                position(CAROL, 6_000L),
                position(DAVE, 5_000L),
                position(ERIN, 4_000L));

        assertThat(quorum(positions, Set.of()).required()).isEqualTo(3);

        Quorum creditorsOnly = quorum(positions, Set.of(CAROL, DAVE, ERIN));
        assertThat(creditorsOnly.met())
                .as("three of five is a majority, and the settlement would still move money out of two accounts "
                        + "whose owners never agreed to it")
                .isFalse();
        assertThat(creditorsOnly.outstandingDebtors()).containsExactlyInAnyOrder(ALICE, BORIS);

        assertThat(quorum(positions, Set.of(CAROL, DAVE, ERIN, ALICE)).outstandingDebtors())
                .as("one debtor agreeing does not cover the other")
                .containsExactly(BORIS);

        assertThat(quorum(positions, Set.of(CAROL, DAVE, ERIN, ALICE, BORIS)).met()).isTrue();
    }

    @Test
    void aDebtorInOneCurrencyOwesEvenIfTheyAreOwedInAnother() {
        // The same member, net positive in rupees and net negative in yen. A run settles both, so they are a
        // debtor for quorum purposes: their account is going to be debited in one of the two.
        List<NetPosition> positions = List.of(
                position(ALICE, 1_000L),
                position(ALICE, -2_000L, Currency.of("JPY", 0)),
                position(BORIS, -1_000L),
                position(BORIS, 2_000L, Currency.of("JPY", 0)));

        Quorum quorum = quorum(positions, Set.of(ALICE, BORIS));

        assertThat(quorum.members())
                .as("one member, not one per currency: the sets are over people")
                .containsExactlyInAnyOrder(ALICE, BORIS);
        assertThat(quorum.debtors()).containsExactlyInAnyOrder(ALICE, BORIS);
        assertThat(quorum.met()).isTrue();
    }

    @Test
    void theClearingAccountIsAParticipantButNotAMember() {
        List<NetPosition> positions = List.of(
                position(ALICE, -10_000L),
                position(BORIS, 10_000L),
                position(FxClearing.ACCOUNT_ID, -224L, Currency.of("JPY", 0)));

        Quorum quorum = quorum(positions, Set.of(ALICE, BORIS));

        assertThat(quorum.members())
                .as("the clearing account is netted like a participant and cannot agree to anything - counting "
                        + "it would let a group reach quorum with its own account's assent, in exactly the runs "
                        + "where the amounts are largest")
                .containsExactlyInAnyOrder(ALICE, BORIS);
        assertThat(quorum.required()).isEqualTo(2);
        assertThat(quorum.met()).isTrue();
    }

    @Test
    void anApprovalFromSomeoneTheRunDoesNotConcernIsReportedAndNotCounted() {
        List<NetPosition> positions = List.of(position(ALICE, -10_000L), position(BORIS, 10_000L));

        Quorum quorum = quorum(positions, Set.of(ALICE, STRANGER));

        assertThat(quorum.approvers()).contains(STRANGER);
        assertThat(quorum.countedApprovals()).containsExactly(ALICE);
        assertThat(quorum.met())
                .as("a quorum that anyone could satisfy by asking is not a quorum, so a stranger's approval is "
                        + "shown and never counted")
                .isFalse();
    }

    @Test
    void aRunWithNoMembersCannotReachQuorum() {
        Quorum quorum = Quorum.of(List.of(), Set.of(), Set.of());

        assertThat(quorum.members()).isEmpty();
        assertThat(quorum.required())
                .as("one, from the arithmetic - which is why met() refuses an empty member set separately rather "
                        + "than leaning on it")
                .isEqualTo(1);
        assertThat(quorum.met()).isFalse();
    }

    @Test
    void aRejectionIsAStateRatherThanTheAbsenceOfAnApproval() {
        // Three members, and the refusal comes from one of them who owes nothing - so this run has the approvals
        // the rule asks for (a majority, and its one debtor personally) *and* a refusal. That is the case the
        // asymmetry exists for, and the reason execution reads the decisions as well as the quorum.
        List<NetPosition> positions = List.of(
                position(ALICE, -10_000L), position(BORIS, 10_000L), position(CAROL, 1L));

        Quorum rejected = Quorum.of(positions, Set.of(ALICE, BORIS), Set.of(CAROL));

        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.decided()).containsExactlyInAnyOrder(ALICE, BORIS, CAROL);
        assertThat(rejected.met())
                .as("quorum and consent are different questions: this run has the approvals it needs and a "
                        + "refusal, which is why execution checks the decisions as well as this")
                .isTrue();
    }

    @Test
    void aRejectionFromSomeoneTheRunDoesNotConcernIsReportedAndNotCounted() {
        List<NetPosition> positions = List.of(position(ALICE, -10_000L), position(BORIS, 10_000L));

        Quorum quorum = Quorum.of(positions, Set.of(), Set.of(STRANGER));

        assertThat(quorum.rejecters()).contains(STRANGER);
        assertThat(quorum.countedRejections()).isEmpty();
        assertThat(quorum.rejected())
                .as("and this is the half that has to be guarded rather than merely reported: an approval that does "
                        + "not count leaves a run short of a quorum, while a rejection that does not count ends it. "
                        + "A row anybody can write must not be able to stop a group's settlement - least of all "
                        + "while a member id is a claim a client makes rather than a fact the server knows")
                .isFalse();
    }

    @Test
    void theReportIsInAStableOrder() {
        List<NetPosition> positions = List.of(
                position(ERIN, -1L), position(DAVE, 1L), position(CAROL, -1L), position(BORIS, 1L));

        Quorum quorum = quorum(positions, Set.of(CAROL, DAVE));

        assertThat(List.copyOf(quorum.members()))
                .as("sorted, so an auditor diffing two readings of the same run sees the same report. Set.copyOf "
                        + "would give no order at all, which is what this asserts against")
                .isSorted()
                .containsExactly(BORIS, CAROL, DAVE, ERIN);
        assertThat(List.copyOf(quorum.approvers())).isSorted();
        assertThat(List.copyOf(quorum.outstandingDebtors())).isSorted();
    }

    private static Quorum quorum(List<NetPosition> positions, Set<UUID> approvers) {
        return Quorum.of(positions, approvers, Set.of());
    }

    private static NetPosition position(UUID member, long netMinor) {
        return position(member, netMinor, INR);
    }

    private static NetPosition position(UUID member, long netMinor, Currency currency) {
        return new NetPosition(member, Money.of(netMinor, currency));
    }
}
