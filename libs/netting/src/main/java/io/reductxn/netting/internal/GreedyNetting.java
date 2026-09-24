package io.reductxn.netting.internal;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NettingEngine;
import io.reductxn.netting.api.NettingRequest;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.Obligation;
import io.reductxn.netting.api.TaxRule;
import io.reductxn.netting.api.TransferInstruction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tier 1 of the two-tier netting engine (ADR 0005): the greedy baseline.
 *
 * <p>Repeatedly matches the largest remaining debtor with the largest remaining creditor. This
 * always terminates, is always correct, and never needs more than N-1 transfers for N members with
 * a non-zero position.
 *
 * <p>It exists as a guaranteed floor rather than as an optimisation: the solver tier may time out,
 * so a settlement must never depend on it succeeding.
 */
public final class GreedyNetting implements NettingEngine {

    @Override
    public NettingResult net(NettingRequest request) {
        Objects.requireNonNull(request, "request");

        Currency currency = request.currency();
        TaxRule taxRule = request.taxRule();

        List<NetPosition> positions = computePositions(request);
        List<TransferInstruction> transfers = computeTransfers(positions, taxRule, currency);

        List<Money> obligationAmounts = request.obligations().stream().map(Obligation::amount).toList();
        List<Money> transferAmounts = transfers.stream().map(TransferInstruction::amount).toList();
        List<Money> transferTaxes = transfers.stream().map(TransferInstruction::tax).toList();
        List<Money> pairwiseTaxes = obligationAmounts.stream().map(taxRule::assess).toList();

        Money gross = sum(obligationAmounts, currency);
        Money net = sum(transferAmounts, currency);
        Money tax = sum(transferTaxes, currency);
        Money taxSaved = sum(pairwiseTaxes, currency).minus(tax);

        return new NettingResult(
                gross,
                net,
                tax,
                taxSaved,
                request.obligations().size(),
                positions,
                transfers,
                NettingResult.Algorithm.GREEDY,
                false);
    }

    /**
     * Reduces obligations to one signed balance per member.
     *
     * <p>The invariant that makes netting work: the balances sum to exactly zero, because every
     * obligation credits one member and debits another by the same amount.
     */
    static List<NetPosition> computePositions(NettingRequest request) {
        Map<UUID, Long> balances = new LinkedHashMap<>();
        for (Obligation obligation : request.obligations()) {
            long minor = obligation.amount().amountMinor();
            balances.merge(obligation.debtorId(), -minor, Long::sum);
            balances.merge(obligation.creditorId(), minor, Long::sum);
        }

        List<NetPosition> positions = new ArrayList<>(balances.size());
        balances.forEach((userId, minor) -> positions.add(new NetPosition(userId, Money.of(minor, request.currency()))));
        positions.sort(Comparator.comparing(NetPosition::userId));
        return List.copyOf(positions);
    }

    /**
     * Greedy bilateral settlement of the net positions.
     *
     * <p>Ordering is by descending magnitude with a tie-break on member id, so the result is
     * deterministic and therefore reproducible by an auditor.
     */
    static List<TransferInstruction> computeTransfers(List<NetPosition> positions, TaxRule taxRule, Currency currency) {
        List<Bucket> debtors = new ArrayList<>();
        List<Bucket> creditors = new ArrayList<>();

        for (NetPosition position : positions) {
            long minor = position.net().amountMinor();
            if (minor < 0) {
                debtors.add(new Bucket(position.userId(), -minor));
            } else if (minor > 0) {
                creditors.add(new Bucket(position.userId(), minor));
            }
        }

        Comparator<Bucket> largestFirst =
                Comparator.comparingLong(Bucket::remaining).reversed().thenComparing(Bucket::userId);
        debtors.sort(largestFirst);
        creditors.sort(largestFirst);

        if (debtors.isEmpty() || creditors.isEmpty()) {
            return List.of();
        }

        List<TransferInstruction> transfers = new ArrayList<>();
        int debtorIndex = 0;
        int creditorIndex = 0;
        long debtorRemaining = debtors.get(debtorIndex).remaining();
        long creditorRemaining = creditors.get(creditorIndex).remaining();

        // Each iteration retires at least one member, so the loop is bounded by the member count.
        // The guard makes that bound explicit and makes a future regression fail loudly instead of
        // spinning forever inside a settlement run.
        int guard = (debtors.size() + creditors.size()) * 2 + 2;

        while (debtorIndex < debtors.size() && creditorIndex < creditors.size() && guard-- > 0) {
            long amount = Math.min(debtorRemaining, creditorRemaining);
            if (amount > 0) {
                Money money = Money.of(amount, currency);
                transfers.add(new TransferInstruction(
                        debtors.get(debtorIndex).userId(),
                        creditors.get(creditorIndex).userId(),
                        money,
                        taxRule.assess(money)));
                debtorRemaining -= amount;
                creditorRemaining -= amount;
            }
            if (debtorRemaining == 0) {
                debtorIndex++;
                if (debtorIndex < debtors.size()) {
                    debtorRemaining = debtors.get(debtorIndex).remaining();
                }
            }
            if (creditorRemaining == 0) {
                creditorIndex++;
                if (creditorIndex < creditors.size()) {
                    creditorRemaining = creditors.get(creditorIndex).remaining();
                }
            }
        }

        return List.copyOf(transfers);
    }

    private static Money sum(List<Money> amounts, Currency currency) {
        long total = 0L;
        for (Money amount : amounts) {
            total = Math.addExact(total, amount.amountMinor());
        }
        return Money.of(total, currency);
    }

    /** A member's remaining obligation during matching, as a positive magnitude. */
    private record Bucket(UUID userId, long remaining) {
    }
}
