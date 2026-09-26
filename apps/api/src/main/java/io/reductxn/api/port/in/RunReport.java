package io.reductxn.api.port.in;

import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A settlement run as its rows read: the plan's figures per currency, the movements the rail is asked for, and who
 * has answered.
 *
 * <p><strong>The status is a {@code String}, and it stays one on the way through.</strong> The column has a
 * {@code CHECK} constraint naming the eight states, so the vocabulary is fixed by the migration rather than by any
 * Java type - and this module has no business declaring a second copy of it. The difference from an approval's word
 * ({@code 'approve'}, which this module *writes*) is the direction of travel: a reader that recognised only the
 * states it had heard of would refuse to read a run from a worker that had learned a ninth, which is a read model
 * that breaks on upgrade.
 *
 * <p>{@code computedAt} is a fact about the run ({@code settlement_runs.computed_at}). There is no
 * {@code executedAt}: nothing records when a run reached {@code done}, so the contract's field has no source and is
 * left out rather than approximated from {@code drivenAt} - which means something else entirely (when the driver
 * last looked at the run, not when the money moved).
 *
 * <p><strong>{@link Gates} is the first judgement this read model carries, and it carries it because the worker
 * wrote it down rather than because the API computed it.</strong> "Why is this waiting?" is answered by quorum's
 * arithmetic and the funding requirement, neither of which this module may derive (ADR 0021) - so
 * {@code settlement_run_gates} holds the verdict the worker reached, this projects it verbatim, and a client can
 * say how far off a group is without re-implementing the rule (ADR 0023).
 *
 * @param partitions one entry per currency in the plan, in currency order
 * @param transfers  the who-pays-whom instructions, in the deterministic order the table's own comment describes
 * @param answers    every member's answer, including any that cannot count (see {@link ReadRun})
 * @param gates      what the run's gates concluded, or null when the worker has not looked at this run yet
 */
public record RunReport(
        UUID id,
        UUID groupId,
        UUID cycleId,
        String status,
        Instant computedAt,
        List<Partition> partitions,
        List<Transfer> transfers,
        List<Answer> answers,
        Gates gates) {

    public RunReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(status, "status");
        partitions = List.copyOf(partitions);
        transfers = List.copyOf(transfers);
        answers = List.copyOf(answers);
        // `gates` is deliberately not required: a run that has just been computed has not been looked at by the
        // worker yet, and "nobody has concluded anything" is a fact this read model reports as an absence rather
        // than as a verdict it invented.
    }

    /**
     * One currency's figures for the run.
     *
     * <p>The four amounts are {@code docs/specs/netting.md} section 2's definitions rather than this module's:
     * {@code gross} and {@code tax} are magnitudes (the schema refuses a negative one, because "a plan nobody could
     * have produced" was worth a constraint), {@code net} is the signed movement, and {@code taxSaved} is what the
     * plan avoided by netting instead of settling pairwise.
     *
     * <p>{@code transferCount} is counted from this run's transfer rows, not stored, and it is here because the
     * saving only means something per movement: "we netted 40 commitments into 3 movements" is the whole value
     * proposition, and a reader otherwise has to fetch the list to say it. There is no {@code algorithm}: the
     * contract allows one, the partition row does not record it, and inventing it here would be a claim about a
     * computation this module did not run.
     */
    public record Partition(
            String currency,
            long grossMinor,
            long netMinor,
            long taxMinor,
            long taxSavedMinor,
            int transferCount) {

        public Partition {
            Objects.requireNonNull(currency, "currency");
        }
    }

    /**
     * One movement the rail is asked to make.
     *
     * <p>{@code toAccount} is derived from the destination's id with {@code libs/planning}'s own model of the
     * clearing account (RULE B21) rather than from a copy of its sentinel: the account is a participant in the plan's
     * arithmetic and not a member of the group, and a reader that reported the group's FX legs as if they were
     * members' payments would present the group's exposure as somebody's debt (ADR 0012).
     *
     * <p>{@code status} travels as the stored word for the reason {@link RunReport} gives. {@code railDetail} is
     * whatever the rail said when it refused - the row carries no stable failure code, so the contract's
     * {@code failureCode} has no source and is left out rather than fabricated from prose.
     */
    public record Transfer(
            UUID fromUserId,
            UUID toUserId,
            ToAccount toAccount,
            long amountMinor,
            String currency,
            String status,
            String railRef,
            String railDetail) {

        public Transfer {
            Objects.requireNonNull(fromUserId, "fromUserId");
            Objects.requireNonNull(toUserId, "toUserId");
            Objects.requireNonNull(toAccount, "toAccount");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(railRef, "railRef");
        }
    }

    /**
     * Whose account a transfer lands in, as the contract's {@code toAccountType} describes it.
     *
     * <p>The wire values are written out rather than derived by lower-casing the constant's name, which is RULE
     * B20's rule rather than a style choice: {@code java.util.Locale} is not available to this module at all - a
     * server that cannot see a locale cannot render one, so localization happens in the client (ADR 0014) - and a
     * rename here fails the build instead of silently changing a field a client reads.
     */
    public enum ToAccount {

        /** A member of the group. */
        MEMBER("member"),

        /** The group's currency clearing account: the leg that crosses currencies. */
        FX_CLEARING("fx_clearing");

        private final String wireName;

        ToAccount(String wireName) {
            this.wireName = wireName;
        }

        /** The wire form, as the contract's enum writes it. */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * A member's answer, exactly as the run's rows hold it.
     *
     * <p>Including the answers that cannot count. The run's arithmetic intersects answers with its own participants,
     * so an answer from somebody the run does not concern is stored and inert (ADR 0020) - and showing the stored
     * answers is what a member needs in order to check their own is on the record. Which of them counted is not
     * computed here: that is the run's judgement, and a reader that guessed it would be the duplicate implementation
     * {@link ReadRun} refuses.
     */
    public record Answer(UUID memberId, String decision, Instant decidedAt) {

        public Answer {
            Objects.requireNonNull(memberId, "memberId");
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }

    /**
     * What the run's gates concluded, as the worker wrote it down.
     *
     * <p><strong>Projected verbatim, and that is the whole point of the table it comes from.</strong> Every field
     * here is the worker's arithmetic - whether a quorum exists, how many approvals it counted, how many it needs,
     * what is still missing per currency - and this module reports it without checking it. The alternative was for
     * this module to count the answers and compare them with the run's participants, which is the duplicate
     * implementation {@link ReadRun} refuses; the run's participants are not even published here.
     *
     * <p><strong>{@code status} is the run's status when the gates were read, and it is here so a reader can tell a
     * current conclusion from a stale one.</strong> A conclusion outlives its subject: a run that was waiting when
     * the worker looked and has since finished keeps the verdict it had, and a client comparing this field with the
     * run's own {@code status} can see that the verdict is about a version of the run that no longer exists.
     * {@code concludedAt} is how long ago that look was.
     *
     * @param concludedAt when the worker last read these gates - not when it first reached this verdict
     * @param quorum      RULE S1's verdict, with the arithmetic behind it
     * @param funding     RULE S4's verdict, with the amounts still missing
     */
    public record Gates(Instant concludedAt, String status, Quorum quorum, Funding funding) {

        public Gates {
            Objects.requireNonNull(concludedAt, "concludedAt");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(quorum, "quorum");
            Objects.requireNonNull(funding, "funding");
        }

        /**
         * Whether the run has quorum, and the counts that explain the answer.
         *
         * <p>{@code counted} and {@code required} are how a client says "one approval short" without knowing the
         * rule, and {@code debtorsStillToConfirm} is how it says "and it has to be one of the members who owes
         * money" - "enough approvals, still waiting" is the combination a member finds hardest to understand, and
         * this is the count that explains it. *Which* member is not here: the run's members are its positions, and
         * this read model does not publish them.
         */
        public record Quorum(boolean met, int counted, int required, int debtorsStillToConfirm) {
        }

        /**
         * Whether the group has brought in the currency the plan needs, and what is still missing.
         *
         * <p>Per currency and never summed (ADR 0012): nothing adds rupees to yen, so {@code outstanding} is a list
         * of amounts with their own currencies. A currency that is covered is absent from it rather than present as
         * a zero, which is what the worker stores - so "what does this group have to do?" is the list itself.
         */
        public record Funding(boolean satisfied, List<Money> outstanding) {

            public Funding {
                Objects.requireNonNull(outstanding, "outstanding");
                outstanding = List.copyOf(outstanding);
            }
        }
    }
}
