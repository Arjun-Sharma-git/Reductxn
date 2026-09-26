package io.reductxn.api.port.in;

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
 * @param partitions one entry per currency in the plan, in currency order
 * @param transfers  the who-pays-whom instructions, in the deterministic order the table's own comment describes
 * @param answers    every member's answer, including any that cannot count (see {@link ReadRun})
 */
public record RunReport(
        UUID id,
        UUID groupId,
        UUID cycleId,
        String status,
        Instant computedAt,
        List<Partition> partitions,
        List<Transfer> transfers,
        List<Answer> answers) {

    public RunReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(status, "status");
        partitions = List.copyOf(partitions);
        transfers = List.copyOf(transfers);
        answers = List.copyOf(answers);
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
}
