package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The run domain's expected failures, as values.
 *
 * <p>One type per code, each declaring {@code public static final String CODE}, because
 * {@code DomainErrorCodeTest} requires it of every concrete {@code DomainError} - the error-code parity gate
 * (I3) has to be able to enumerate every code the server can emit.
 *
 * <p><strong>The namespace is {@code settlement}, and it agrees with the contract here.</strong> In
 * {@code apps/api} the two written rules disagreed, because one deployable owns several domains and the
 * mechanical rule derives the namespace from the package segment. This module owns exactly one domain and its
 * package segment is {@code settlement}, so the contract's reserved {@code settlement.*} namespace and the
 * guard's derivation produce the same string.
 *
 * <p><strong>{@code settlement.fx_unfunded} is here now, and it was the code this file predicted.</strong> The
 * spec's RULE S4 and MASTER-PLAN both name it as the funding gate's code, and until the gate existed it was a
 * string in a document: a run simply sat in {@code awaiting_fx_funding} and nothing ever asked whether it could
 * proceed. The gate is asked at execution, so the code is emitted by {@link ExecuteRun} - with the outstanding
 * amount per currency, because "unfunded" without a figure is a message a group cannot act on.
 *
 * <p>The rest arrived with quorum and execution. Each is a refusal rather than a thrown exception: every one is
 * a state a real caller can reach, and a {@code Result.Failure} makes the caller handle it instead of hoping.
 */
public final class RunRefusals {

    private RunRefusals() {
    }

    /**
     * The cycle contains no obligations, so there is nothing to settle.
     *
     * <p>A refusal rather than an empty run, and the difference is not cosmetic. An empty plan settles nothing
     * and zeroes nothing, but it *looks* like a completed cycle: a run in {@code awaiting_approval} with no
     * partitions would let a group approve a settlement that does not exist, and its positions would then be
     * "settled" in the only place that records them. Refusing says what is true - there was nothing to do -
     * rather than recording a decision that was never made.
     */
    public record NothingToSettle(UUID groupId, Instant asOf) implements DomainError {

        public static final String CODE = "settlement.nothing_to_settle";

        public NothingToSettle {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "group " + groupId + " has no open obligations as of " + asOf + ", so there is no cycle to "
                    + "settle. An empty run would record a settlement of nothing and read as a completed cycle";
        }
    }

    /**
     * No run with that id.
     *
     * <p>Distinct from "no run for this cycle", which is an ordinary answer to a compute request. A caller
     * naming a run that does not exist has a stale id or a typo, and answering with an empty report would let it
     * act on a settlement that is not there.
     */
    public record NoSuchRun(UUID runId) implements DomainError {

        public static final String CODE = "settlement.run_not_found";

        public NoSuchRun {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "no settlement run " + runId + " exists";
        }
    }

    // --- quorum: what a member's answer can be refused for (RULE S1) -----------------------------------

    /**
     * The run is not in a state that is waiting on the group, so there is nothing for a decision to change.
     *
     * <p>Reached by approving an {@code executing} or {@code done} run, or by funding one. It is one code
     * rather than one per command because the diagnosis is the same in both cases: the thing the caller is
     * trying to influence has moved on. The status travels with it, so the answer says which state it is in
     * rather than only that it is the wrong one.
     */
    public record NotWaitingOnTheGroup(UUID runId, RunStatus status) implements DomainError {

        public static final String CODE = "settlement.not_waiting_on_the_group";

        public NotWaitingOnTheGroup {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(status, "status");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " is " + status.wireName() + ", so it is not waiting on the group: only a "
                    + "run in awaiting_approval or awaiting_fx_funding can be approved, rejected or funded";
        }
    }

    /**
     * The member is not one of the run's participants.
     *
     * <p><strong>This is a safety refusal, not a bookkeeping one.</strong> Quorum is counted over the members a
     * run concerns - the participants in its plan. If any member id were accepted, a quorum could be reached by
     * whoever asked: a single caller with permission to approve could approve repeatedly under invented ids,
     * and nothing would say so, because the approvals would be rows like any other.
     *
     * <p>The clearing account is not a member either, and it is the sharpest version of this: it is a
     * participant in every cross-currency run, so a quorum that counted it would let the group's own account
     * stand in for a person - in exactly the runs where the amounts are largest.
     */
    public record NotAParticipant(UUID runId, UUID memberId) implements DomainError {

        public static final String CODE = "settlement.not_a_participant";

        public NotAParticipant {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(memberId, "memberId");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return memberId + " is not a participant in run " + runId + ", so their approval does not count. "
                    + "Quorum is counted over the members whose money the run moves, or it could be satisfied by "
                    + "anyone who asked";
        }
    }

    /** The member has already answered, and a decision is final. */
    public record AlreadyDecided(UUID runId, UUID memberId, RunDecision.Decision decision) implements DomainError {

        public static final String CODE = "settlement.already_decided";

        public AlreadyDecided {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(decision, "decision");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return memberId + " has already " + decision.wireName() + "d run " + runId + ". A decision is final: "
                    + "a group that wants a different answer can reject this run and open another cycle over the "
                    + "same commitments, which is one refusal and a readable record rather than an edit";
        }
    }

    /**
     * The run does not have quorum yet, so it may not execute (RULE S1).
     *
     * <p>Carries the quorum itself, so the refusal says how far off it is: how many approvals are needed, how
     * many there are, and which debtors have not confirmed. A refusal that only said "no" would leave a group to
     * work out who is missing.
     */
    public record QuorumNotMet(UUID runId, Quorum quorum) implements DomainError {

        public static final String CODE = "settlement.quorum_not_met";

        public QuorumNotMet {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(quorum, "quorum");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " has " + quorum.approvers().size() + " approval(s) of the "
                    + quorum.required() + " that a majority of its " + quorum.members().size() + " members "
                    + "requires"
                    + (quorum.outstandingDebtors().isEmpty()
                            ? ""
                            : ", and " + quorum.outstandingDebtors().size() + " member(s) who owe a net amount "
                                    + "have not confirmed personally: " + quorum.outstandingDebtors())
                    + ". One member cannot move the group's money";
        }
    }

    /** A member refused, so the run is rejected. */
    public record RunRejected(UUID runId, UUID memberId) implements DomainError {

        public static final String CODE = "settlement.run_rejected";

        public RunRejected {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " was rejected" + (memberId == null ? "" : " by " + memberId) + ", so it may "
                    + "not execute. Nothing has moved: a rejection stops a settlement rather than undoing one";
        }
    }

    /**
     * A currency's external requirement is not covered, so the run may not execute (RULE S4).
     *
     * <p><strong>This is the code MASTER-PLAN names, and it took a gate to make it real.</strong> The
     * outstanding amounts travel with it, per currency: a group that is short "some amount of yen" cannot act on
     * the answer, and a group told the exact figure can bring it in. There is no total across currencies, because
     * adding yen to rupees is not addition (ADR 0012 point 4) - the list is of amounts, each with its currency.
     */
    public record FxUnfunded(UUID runId, List<Money> outstanding) implements DomainError {

        public static final String CODE = "settlement.fx_unfunded";

        public FxUnfunded {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            outstanding = List.copyOf(Objects.requireNonNull(outstanding, "outstanding"));

            if (outstanding.isEmpty()) {
                throw new IllegalArgumentException(
                        "a run cannot be unfunded with nothing outstanding: the refusal would carry no figure "
                                + "and read as a failure with no cause");
            }
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " needs currency from outside before it can execute: " + outstanding
                    + " is outstanding. The engine cannot produce currency the group does not hold (P2), so the "
                    + "run waits rather than settling a cycle it cannot complete";
        }
    }

    /**
     * A funding for a currency this run does not require at all.
     *
     * <p><strong>Not for "it already has it".</strong> Funding more than the requirement is recorded like any
     * other arrival: the currency did arrive, and refusing to record it would leave money a group believes it
     * moved out of the only place that knows about it. The gate asks whether each currency is covered, not whether
     * it was covered exactly - so this refusal is about a currency the plan never asked for, where a row would
     * assert that a gap was closed while naming a gap that was never open.
     *
     * <p>What makes a repeat of the same arrival impossible is the reference rather than this check: the same
     * funding reference is recorded once (RULE S2), which is the case a retry actually produces.
     */
    public record FundingNotRequired(UUID runId, Currency currency, List<Money> required) implements DomainError {

        public static final String CODE = "settlement.funding_not_required";

        public FundingNotRequired {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(currency, "currency");
            required = List.copyOf(Objects.requireNonNull(required, "required"));
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " needs nothing in " + currency.code() + ", so this funding has nothing to "
                    + "cover. What it is waiting for, per currency, is " + required;
        }
    }

    /** A funding that is not a payment: zero or negative. */
    public record FundingNotPositive(UUID runId, Money amount) implements DomainError {

        public static final String CODE = "settlement.funding_not_positive";

        public FundingNotPositive {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(amount, "amount");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "a funding of " + amount + " for run " + runId + " is not a payment. Currency arriving is "
                    + "strictly positive; a correction is another funding, not a negative one";
        }
    }

    /**
     * The rail cannot settle a currency the run needs.
     *
     * <p>Checked before anything is submitted, and that ordering is the point. A run whose rail cannot settle one
     * of its currencies is a deployment problem, but discovering it part-way through a submission loop would
     * leave half the group's money moved and the other half not - the partial application RULE S3 makes a
     * first-class outcome for things outside anyone's control. This one is inside it.
     */
    public record RailCannotSettle(UUID runId, List<Currency> currencies) implements DomainError {

        public static final String CODE = "settlement.currency_not_supported";

        public RailCannotSettle {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(runId, "runId");
            currencies = List.copyOf(Objects.requireNonNull(currencies, "currencies"));
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "run " + runId + " settles " + currencies + ", which the configured rail does not accept. "
                    + "Nothing was submitted: a rail that cannot settle a currency must not be discovered halfway "
                    + "through moving the group's money";
        }
    }
}
