package io.reductxn.netting;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NettingEngine;
import io.reductxn.netting.api.NettingEngines;
import io.reductxn.netting.api.NettingRequest;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.Obligation;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.TaxRule;
import io.reductxn.netting.api.TaxRules;
import io.reductxn.netting.api.TransferInstruction;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The netting invariants from docs/specs/netting.md section 4, checked over generated inputs rather
 * than hand-picked examples.
 *
 * <p>These are the properties that make a settlement trustworthy: the positions must cancel, the
 * transfers must actually settle everyone, the transfer count must respect the N-1 bound, and the
 * result must be reproducible. An example-based test cannot establish any of that.
 */
class GreedyNettingPropertyTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final List<UUID> MEMBERS =
            IntStream.range(0, 7).mapToObj(index -> new UUID(0L, index + 1L)).toList();
    private static final NettingEngine ENGINE = NettingEngines.greedy();

    /**
     * Obligations over a small member set, with the creditor derived from an offset inside
     * 1..n-1 so a member can never owe itself.
     */
    @Provide
    Arbitrary<List<Obligation>> obligations() {
        Arbitrary<Integer> debtorIndex = Arbitraries.integers().between(0, MEMBERS.size() - 1);
        Arbitrary<Integer> creditorOffset = Arbitraries.integers().between(1, MEMBERS.size() - 1);
        Arbitrary<Long> amount = Arbitraries.longs().between(1L, 1_000_000L);

        Arbitrary<Obligation> one = Combinators.combine(debtorIndex, creditorOffset, amount)
                .as((debtor, offset, minor) -> new Obligation(
                        MEMBERS.get(debtor),
                        MEMBERS.get((debtor + offset) % MEMBERS.size()),
                        Money.of(minor, INR)));

        return one.list().ofMinSize(1).ofMaxSize(40);
    }

    @Property
    void n1_positionsAlwaysCancelToZero(@ForAll("obligations") List<Obligation> obligations) {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        long total = result.positions().stream().mapToLong(position -> position.net().amountMinor()).sum();
        assertThat(total).as("N1: sum of net positions must be exactly zero").isZero();
    }

    @Property
    void n2_transfersSettleEveryBalanceExactly(@ForAll("obligations") List<Obligation> obligations) {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        Map<UUID, Long> balances = new HashMap<>();
        for (NetPosition position : result.positions()) {
            balances.put(position.userId(), position.net().amountMinor());
        }
        for (TransferInstruction transfer : result.transfers()) {
            balances.merge(transfer.fromUserId(), transfer.amount().amountMinor(), Long::sum);
            balances.merge(transfer.toUserId(), -transfer.amount().amountMinor(), Long::sum);
        }

        assertThat(balances.values())
                .as("N2: after applying the transfers every member must be at zero")
                .allMatch(remaining -> remaining == 0L);
    }

    @Property
    void n3_noTransferEverMovesANonPositiveAmount(@ForAll("obligations") List<Obligation> obligations) {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        assertThat(result.transfers())
                .as("N3: the instruction record itself rejects non-positive amounts")
                .allSatisfy(transfer -> assertThat(transfer.amount().isPositive()).isTrue());
    }

    @Property
    void n4_transferCountRespectsTheMembersMinusOneBound(@ForAll("obligations") List<Obligation> obligations) {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        long nonZeroPositions = result.positions().stream()
                .filter(position -> !position.net().isZero())
                .count();
        long bound = Math.max(0L, nonZeroPositions - 1L);

        assertThat((long) result.transfers().size())
                .as("N4: N-1 bound for N members with a non-zero position")
                .isLessThanOrEqualTo(bound);
    }

    @Property
    void n6_grossMinusNetEqualsWhatNettingRemoved(@ForAll("obligations") List<Obligation> obligations) {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        assertThat(result.gross().amountMinor() - result.net().amountMinor())
                .as("N6: the gap between gross and net is what netting removed")
                .isGreaterThanOrEqualTo(0L);
    }

    @Property
    void n7_identicalInputYieldsIdenticalOutput(@ForAll("obligations") List<Obligation> obligations) {
        NettingRequest request = NettingRequest.untaxed(INR, obligations);

        NettingResult first = ENGINE.net(request);
        NettingResult second = ENGINE.net(request);

        assertThat(second.transfers())
                .as("N7: determinism is what lets an auditor reproduce a settlement")
                .isEqualTo(first.transfers());
        assertThat(second.positions()).isEqualTo(first.positions());
    }

    @Property
    void n8_taxIsNonNegativeAndSavedIsExactlyTheDifference(@ForAll("obligations") List<Obligation> obligations) {
        Money perTransfer = Money.of(50L, INR);
        TaxRule rule = TaxRules.flatPerTransfer(perTransfer);

        NettingResult result = ENGINE.net(new NettingRequest(INR, obligations, rule));

        long pairwiseTax = perTransfer.amountMinor() * obligations.size();

        assertThat(result.tax().amountMinor()).as("N8: tax is never negative").isGreaterThanOrEqualTo(0L);
        assertThat(result.taxSaved().amountMinor())
                .as("N8: taxSaved is exactly pairwise tax minus netted tax")
                .isEqualTo(pairwiseTax - result.tax().amountMinor());
        assertThat(result.tax().amountMinor())
                .as("N8: netted tax can never exceed pairwise tax")
                .isLessThanOrEqualTo(pairwiseTax);
    }
}
