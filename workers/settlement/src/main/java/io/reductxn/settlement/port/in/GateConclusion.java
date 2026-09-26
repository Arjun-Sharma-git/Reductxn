package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a run's gates concluded, and when the worker last looked.
 *
 * <p><strong>This exists because the reader of a run is in another deployable.</strong> A member asking "why is
 * this waiting?" is asking {@code apps/api}'s question about this module's arithmetic: quorum needs the run's
 * participants and who owes what, funding needs the plan's clearing positions and what has arrived. Neither can be
 * answered from the run's rows by a reader that is not allowed to re-derive the rule (ADR 0021), so the module that
 * owns the rule writes its verdict down and the reader projects it (ADR 0023). That is the whole shape of this
 * type: not "the state of the run", which is {@link SettlementRun#status()}, but "what the gates said about it the
 * last time anybody asked".
 *
 * <p><strong>Derived from {@link ComputedRun}, never judged here.</strong> The verdicts are {@link Quorum#met()}
 * and {@link Funding#satisfied()} and nothing else: {@link #of} reads them rather than deciding them, so there is
 * one implementation of each rule in the process - the one the gate itself uses. A conclusion computed by a second
 * piece of arithmetic would be a second answer about whether a group may settle.
 *
 * <p><strong>{@code status} travels with the verdict.</strong> A projection that outlives its subject has to carry
 * the subject's version, or a run that was waiting when the gates were read and is finished now would have a
 * conclusion saying it is one approval short. A reader compares this with the run's current status and knows
 * whether it is reading the present or the past; {@code concludedAt} says how long ago the look was.
 *
 * @param runId       the run these gates were read for, which is what the conclusion is written against
 * @param status      the run's status when the gates were read, as the column spells it
 * @param quorum      RULE S1's verdict, with the arithmetic behind it
 * @param funding     RULE S4's verdict, with the amounts still missing
 * @param concludedAt when the worker read the gates: "last looked", not "first concluded"
 */
public record GateConclusion(
        UUID runId,
        RunStatus status,
        QuorumVerdict quorum,
        FundingVerdict funding,
        Instant concludedAt) {

    public GateConclusion {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(quorum, "quorum");
        Objects.requireNonNull(funding, "funding");
        Objects.requireNonNull(concludedAt, "concludedAt");
    }

    /**
     * Quorum's verdict and the arithmetic a reader needs to explain it.
     *
     * <p>{@code counted} and {@code required} are what makes "one approval short" sayable without a second
     * implementation of the majority rule, and {@code debtorsStillToConfirm} is what makes "and it has to be one of
     * the members who owes money" sayable. Who that member is would be a member-level fact, and the member set is
     * {@code net_positions}' rather than this record's - said here so the absence is a decision rather than an
     * oversight.
     *
     * @param counted                 how many members' approvals the run's arithmetic counted
     * @param required                how many a majority needs: strictly more than half of the members
     * @param debtorsStillToConfirm   how many members who owe a net amount have not confirmed personally
     */
    public record QuorumVerdict(boolean met, int counted, int required, int debtorsStillToConfirm) {

        public QuorumVerdict {
            if (counted < 0 || required < 0 || debtorsStillToConfirm < 0) {
                throw new IllegalArgumentException(
                        "a verdict's counts are magnitudes, but this one is met=" + met + ", counted=" + counted
                                + ", required=" + required + ", debtors=" + debtorsStillToConfirm + ". A negative "
                                + "count is not a fact about a run");
            }
        }
    }

    /**
     * Funding's verdict and the gap behind it.
     *
     * <p>{@code outstanding} holds only the currencies that are still short, in the plan's currency order: a
     * covered currency is absent rather than present with a zero, which is the same choice {@link Funding} makes and
     * the reason a reader does not have to filter zeros out to find what a group has to do.
     */
    public record FundingVerdict(boolean satisfied, List<Money> outstanding) {

        public FundingVerdict {
            Objects.requireNonNull(outstanding, "outstanding");
            outstanding = List.copyOf(outstanding);
        }
    }

    /**
     * The verdicts of the run as it stands, and the instant they were read.
     *
     * @param concludedAt when the caller looked at the run, from the caller's clock (RULE B21)
     */
    public static GateConclusion of(ComputedRun run, Instant concludedAt) {
        Objects.requireNonNull(run, "run");

        Quorum quorum = run.quorum();
        Funding funding = run.funding();

        return new GateConclusion(
                run.run().id(),
                run.run().status(),
                new QuorumVerdict(quorum.met(), quorum.countedApprovals().size(), quorum.required(),
                        quorum.outstandingDebtors().size()),
                new FundingVerdict(funding.satisfied(), funding.outstanding()),
                concludedAt);
    }
}
