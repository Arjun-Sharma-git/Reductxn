package io.reductxn.api.web.dto;

import io.reductxn.api.web.dto.NettingPreviewResponse.BestCase;
import io.reductxn.api.web.dto.NettingPreviewResponse.MoneyDto;
import io.reductxn.api.web.dto.NettingPreviewResponse.Partition;
import io.reductxn.api.web.dto.NettingPreviewResponse.Position;
import io.reductxn.api.web.dto.NettingPreviewResponse.Transfer;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetDirection;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.NettingResult.Algorithm;
import io.reductxn.netting.api.TransferInstruction;
import io.reductxn.planning.api.CurrencyPlan;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SettlementPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a settlement plan onto the wire format the contract defines.
 *
 * <p>This is the only place in the module that knows both shapes, which is deliberate: the use case
 * returns the plan, and if the wire format changes it is this file that changes rather than every
 * caller. It is also the reason the mapper is static and has no state - it is a function from a plan to
 * a response, and a mapper that needed a collaborator would be a sign that a use case was missing.
 */
public final class NettingPreviewMapper {

    private NettingPreviewMapper() {
    }

    public static NettingPreviewResponse toResponse(SettlementPlan plan) {
        List<Partition> partitions = new ArrayList<>();
        for (CurrencyPlan partition : plan.partitions()) {
            partitions.add(partition(partition));
        }

        // The single-currency fields are present only when there is exactly one partition. For a
        // multicurrency group the top-level figures would have to be expressed in some currency, and
        // choosing one silently is the collapse ADR 0012 refuses - so they are absent, and `partitions`
        // carries the figures in the currencies they are actually in.
        CurrencyPlan only = plan.partitions().size() == 1 ? plan.partitions().get(0) : null;

        return new NettingPreviewResponse(
                only == null ? null : only.currency().code(),
                only == null ? null : money(grossTaxOf(only.netting())),
                only == null ? null : money(only.netting().tax()),
                only == null ? null : money(only.netting().taxSaved()),
                plan.isEmpty() ? null : plan.partitions().stream()
                        .allMatch(partition -> partition.netting().provablyOptimal()),
                List.copyOf(partitions),
                bestCase(plan));
    }

    private static Partition partition(CurrencyPlan plan) {
        NettingResult netting = plan.netting();

        return new Partition(
                plan.currency().code(),
                netting.grossTransferCount(),
                netting.netTransferCount(),
                money(grossTaxOf(netting)),
                money(netting.tax()),
                money(netting.taxSaved()),
                algorithmName(netting.algorithm()));
    }

    /**
     * The tax that settling every obligation directly would have cost.
     *
     * <p>Not stored anywhere: {@code taxSaved} is defined as that figure minus the tax actually assessed,
     * so adding them back recovers it. Recomputing rather than adding a field is what keeps the two from
     * ever disagreeing.
     */
    private static Money grossTaxOf(NettingResult netting) {
        return netting.tax().plus(netting.taxSaved());
    }

    private static BestCase bestCase(SettlementPlan plan) {
        List<Position> positions = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();

        for (CurrencyPlan partition : plan.partitions()) {
            partition.netting().positions().forEach(position -> positions.add(position(position)));
            partition.netting().transfers().forEach(transfer -> transfers.add(transfer(transfer)));
        }

        return new BestCase(List.copyOf(positions), List.copyOf(transfers));
    }

    private static Position position(NetPosition position) {
        return new Position(position.userId(), directionName(position.direction()), money(position.net()));
    }

    /**
     * The engine's enum to the contract's, spelled out rather than derived from the enum name.
     *
     * <p>Deriving it - {@code name().toLowerCase()} - would need a locale to be correct, and a server that
     * must not localize anything should not be reaching for one. It would also mean a rename inside
     * {@code libs/netting} silently changed a wire value; an exhaustive switch makes that a compile error,
     * and makes adding an algorithm a deliberate act rather than an omission.
     */
    private static String algorithmName(Algorithm algorithm) {
        return switch (algorithm) {
            case GREEDY -> "greedy";
            case PAIRWISE -> "pairwise";
            case SUBSET_PARTITION -> "subset_partition";
            case MIP -> "mip";
        };
    }

    /** Same reasoning as {@link #algorithmName}: the wire value is stated, not derived. */
    private static String directionName(NetDirection direction) {
        return switch (direction) {
            case PAY -> "pay";
            case RECEIVE -> "receive";
            case FLAT -> "flat";
        };
    }

    private static Transfer transfer(TransferInstruction instruction) {
        return new Transfer(
                instruction.fromUserId(),
                instruction.toUserId(),
                // Asking libs/planning rather than comparing against a sentinel here. That module owns the
                // only model of the clearing account (RULE B21), and a second copy of the id would be a
                // second thing to keep in step.
                FxClearing.isClearing(instruction.toUserId()) ? "fx_clearing" : "member",
                money(instruction.amount()),
                money(instruction.tax()),
                "pending");
    }

    private static MoneyDto money(Money money) {
        return new MoneyDto(money.amountMinor(), money.currency().code());
    }
}
