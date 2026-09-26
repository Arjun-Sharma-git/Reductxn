package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.RunReport;

import java.util.List;

/**
 * Between the contract's run shapes and the ports' types.
 *
 * <p>The only class that knows both, as with the other mappers in this package: the use case speaks
 * {@link RunReport}, the wire speaks {@link SettlementRunResponse} and {@link TransferInstructionResponse}.
 *
 * <p>Both endpoints are served from one read ({@link RunReport}) rather than one each, so a client that asks for the
 * run and then for its transfers gets two views of one snapshot instead of two snapshots of a moving plan - and the
 * transfers endpoint's shape is a projection of the same read rather than a second query built to match it.
 */
public final class RunMapper {

    private RunMapper() {
    }

    public static SettlementRunResponse toResponse(RunReport run) {
        return new SettlementRunResponse(
                run.id(),
                run.cycleId(),
                run.groupId(),
                run.status(),
                run.partitions().stream().map(RunMapper::toPartition).toList(),
                run.answers().stream().map(RunMapper::toAnswer).toList(),
                run.computedAt(),
                toGates(run.gates()));
    }

    /** Null through, rather than an empty verdict: no conclusion and a satisfied one are different facts. */
    private static SettlementRunResponse.Gates toGates(RunReport.Gates gates) {
        if (gates == null) {
            return null;
        }

        return new SettlementRunResponse.Gates(
                gates.concludedAt(),
                gates.status(),
                new SettlementRunResponse.Gates.Quorum(
                        gates.quorum().met(),
                        gates.quorum().counted(),
                        gates.quorum().required(),
                        gates.quorum().debtorsStillToConfirm()),
                new SettlementRunResponse.Gates.Funding(
                        gates.funding().satisfied(),
                        gates.funding().outstanding().stream()
                                .map(outstanding -> new MoneyDto(outstanding.amountMinor(),
                                        outstanding.currency().code()))
                                .toList()));
    }

    public static List<TransferInstructionResponse> toInstructions(RunReport run) {
        return run.transfers().stream().map(RunMapper::toInstruction).toList();
    }

    private static SettlementRunResponse.Partition toPartition(RunReport.Partition partition) {
        String currency = partition.currency();

        return new SettlementRunResponse.Partition(
                currency,
                new MoneyDto(partition.grossMinor(), currency),
                new MoneyDto(partition.netMinor(), currency),
                new MoneyDto(partition.taxMinor(), currency),
                new MoneyDto(partition.taxSavedMinor(), currency),
                partition.transferCount());
    }

    private static SettlementRunResponse.Answer toAnswer(RunReport.Answer answer) {
        return new SettlementRunResponse.Answer(answer.memberId(), answer.decision(), answer.decidedAt());
    }

    private static TransferInstructionResponse toInstruction(RunReport.Transfer transfer) {
        return new TransferInstructionResponse(
                transfer.fromUserId(),
                transfer.toUserId(),
                transfer.toAccount().wireName(),
                new MoneyDto(transfer.amountMinor(), transfer.currency()),
                transfer.status(),
                transfer.railRef(),
                transfer.railDetail());
    }
}
