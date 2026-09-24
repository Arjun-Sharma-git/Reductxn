package io.reductxn.netting;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NettingEngine;
import io.reductxn.netting.api.NettingEngines;
import io.reductxn.netting.api.NettingRequest;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.Obligation;
import io.reductxn.netting.api.TaxRules;
import io.reductxn.netting.api.TransferInstruction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worked example from docs/specs/netting.md section 5, as an executable check.
 *
 * <p>Four members, four obligations, one currency. Naive pairwise settlement needs 4 transfers and
 * incurs 4 units of per-transfer tax; netting needs 3 and incurs 3. That single avoided transfer is
 * the whole product thesis, so it is pinned down here as a test.
 */
class GreedyNettingTest {

    private static final Currency INR = Currency.of("INR", 2);

    private static final UUID A = new UUID(0L, 1L);
    private static final UUID B = new UUID(0L, 2L);
    private static final UUID C = new UUID(0L, 3L);
    private static final UUID D = new UUID(0L, 4L);

    private static final NettingEngine ENGINE = NettingEngines.greedy();
    private static final Money PER_TRANSFER_TAX = Money.of(50L, INR);

    private static Obligation owes(UUID debtor, UUID creditor, long amountMinor) {
        return new Obligation(debtor, creditor, Money.of(amountMinor, INR));
    }

    @Test
    void reducesFourTransfersToThree() {
        List<Obligation> obligations = List.of(
                owes(A, B, 300_000L),
                owes(A, C, 100_000L),
                owes(D, C, 200_000L),
                owes(B, C, 50_000L));

        NettingResult result = ENGINE.net(new NettingRequest(INR, obligations, TaxRules.none()));

        assertThat(result.grossTransferCount()).isEqualTo(4);
        assertThat(result.netTransferCount()).isEqualTo(3);
        assertThat(result.transfersAvoided()).isEqualTo(1);
        assertThat(result.algorithm()).isEqualTo(NettingResult.Algorithm.GREEDY);
        assertThat(result.provablyOptimal()).isFalse();

        // Net positions: A pays 4000, D pays 2000, C receives 3500, B receives 2500.
        assertThat(netOf(result, A)).isEqualTo(-400_000L);
        assertThat(netOf(result, D)).isEqualTo(-200_000L);
        assertThat(netOf(result, C)).isEqualTo(350_000L);
        assertThat(netOf(result, B)).isEqualTo(250_000L);

        // Largest debtor pays the largest creditor first, deterministically.
        assertThat(result.transfers()).extracting(TransferInstruction::fromUserId)
                .containsExactly(A, A, D);
        assertThat(result.transfers()).extracting(TransferInstruction::toUserId)
                .containsExactly(C, B, B);
        assertThat(result.transfers()).extracting(t -> t.amount().amountMinor())
                .containsExactly(350_000L, 50_000L, 200_000L);
    }

    @Test
    void reportsTheTaxSavedByNetting() {
        List<Obligation> obligations = List.of(
                owes(A, B, 300_000L),
                owes(A, C, 100_000L),
                owes(D, C, 200_000L),
                owes(B, C, 50_000L));

        NettingResult result = ENGINE.net(new NettingRequest(
                INR, obligations, TaxRules.flatPerTransfer(PER_TRANSFER_TAX)));

        // Pairwise would have been taxed 4 times; netting pays tax on only 3 transfers.
        assertThat(result.tax().amountMinor()).isEqualTo(3L * 50L);
        assertThat(result.taxSaved().amountMinor()).isEqualTo(1L * 50L);
    }

    @Test
    void membersWhoAreFlatNeitherPayNorReceive() {
        List<Obligation> obligations = List.of(
                owes(A, B, 100_000L),
                owes(B, A, 100_000L));

        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, obligations));

        assertThat(result.netTransferCount()).isZero();
        assertThat(result.positions()).allMatch(position -> position.net().isZero());
    }

    @Test
    void emptyGroupProducesNothingRatherThanFailing() {
        NettingResult result = ENGINE.net(NettingRequest.untaxed(INR, List.of()));

        assertThat(result.transfers()).isEmpty();
        assertThat(result.positions()).isEmpty();
        assertThat(result.gross().amountMinor()).isZero();
        assertThat(result.net().amountMinor()).isZero();
        assertThat(result.taxSaved().amountMinor()).isZero();
    }

    private static long netOf(NettingResult result, UUID userId) {
        return result.positions().stream()
                .filter(position -> position.userId().equals(userId))
                .findFirst()
                .orElseThrow()
                .net()
                .amountMinor();
    }
}
