package io.reductxn.settlement.port.in;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A person's answer about a transfer no rail would decide: that the money moved, or that it did not - and on whose
 * word.
 *
 * <p><strong>Why this is an input at all.</strong> A rail that goes quiet cannot be resolved by the worker (ADR
 * 0024): silence is not evidence of anything except silence, so writing {@code failed} would assert the money did
 * not move and {@code confirmed} would assert that it did. The one thing that can settle the question is somebody
 * who found out - the rail's support desk, a statement, a portal, a bank line - and this record is how that person's
 * answer reaches the module. The escalation report is what tells them which movement to ask about; this is the way
 * back.
 *
 * <p><strong>The vocabulary is smaller than a transfer's states, deliberately.</strong> {@link Outcome} has two
 * values, {@code confirmed} and {@code failed}, because those are the only two things a person can know about money:
 * whether it moved. They cannot know it is {@code submitted}, {@code authorized} or {@code pending} - those are
 * facts about what the <em>worker</em> has done, which the rows already hold - and offering the full state list here
 * would let a caller write a transfer back to a state its history contradicts. Taken as {@link TransferStatus},
 * which is the pair of states that mean an answer has arrived.
 *
 * <p><strong>{@code by} and {@code basis} are required, and they are not decoration.</strong> A resolution is the
 * highest-stakes write in this module - it can complete a run, book a movement, or end a transfer's retries for
 * ever - and both directions are claims a group will later rely on. So the record carries who asserted it and what
 * they were looking at, and it refuses to exist without either. Nothing verifies the name (there is no identity
 * system in this build): it is an attribution, and an attribution that cannot be recorded is worse than one that
 * can be read. {@code basis} is deliberately not called evidence - nothing here checks it - it is the thing the
 * person acted on, stored so a later reader knows what they saw.
 *
 * <p><strong>The run is named as well as the transfer.</strong> Transfer ids are unique, so the transfer alone would
 * find the row - but the caller is a human with two ids from a report, and naming both means a typo resolves nothing
 * rather than resolving a different run's transfer. The run is also what the answer is finally about: a resolved
 * transfer changes the run's fate (see {@code RunOutcome}), and the caller should have to say which run it is
 * changing.
 *
 * <p>No instant is carried, because the clock is the use case's - as everywhere in this module, so that a resolution
 * is testable without waiting and an operator cannot date an assertion into the past.
 *
 * @param runId      the run whose plan the transfer is part of
 * @param transferId the transfer this answers
 * @param outcome    what the person found out: the money moved, or it did not
 * @param by         who is asserting it. An operator's identity as this deployment names them - an email, a name, a
 *                   ticket's author. Recorded verbatim, unverified: an attribution rather than an authentication
 * @param basis      what they looked at when they decided - a rail statement's reference, a support ticket, a bank
 *                   line. Recorded so the claim can be checked later, since nothing here checks it now
 */
public record TransferResolution(
        UUID runId,
        UUID transferId,
        Outcome outcome,
        String by,
        String basis) {

    /** What a person can know about a transfer, which is which way the money went. */
    public enum Outcome {

        /** The money moved. The transfer is confirmed, and the movement is booked like any other confirmation. */
        CONFIRMED,

        /** The money did not move. The transfer is failed, nothing is booked, and it is never offered again. */
        FAILED;

        /** The wire and column form, as {@code --reductxn.outcome} spells it and the CHECK constraint stores it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The outcome an operator's word names.
         *
         * <p>An exhaustive switch over the two words a person may say, with no default: the vocabulary here is the
         * small one above, and a third would have to be taught to this method before it could be typed at a shell.
         */
        public static Outcome ofWireName(String wireName) {
            return switch (wireName == null ? "" : wireName) {
                case "confirmed" -> CONFIRMED;
                case "failed" -> FAILED;
                default -> throw new IllegalArgumentException(
                        "an operator can say 'confirmed' or 'failed' about a transfer, not '" + wireName + "'. "
                                + "Whether the transfer is submitted or authorized is a fact about what the worker "
                                + "has done, and the rows already hold it");
            };
        }

        /**
         * The state this outcome writes.
         *
         * <p>The two are the same vocabulary on purpose: {@code TransferStatus.CONFIRMED} means "an answer that said
         * the money moved" whichever side gave it, so a rail's confirmation and a person's resolve land in one state
         * and every reader of a transfer - the run's fate, the escalation report, a client's read model - sees one
         * kind of fact.
         */
        public TransferStatus transferStatus() {
            return switch (this) {
                case CONFIRMED -> TransferStatus.CONFIRMED;
                case FAILED -> TransferStatus.FAILED;
            };
        }
    }

    public TransferResolution {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(outcome, "outcome");

        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException(
                    "a resolution of transfer " + transferId + " names nobody. The command's whole subject is who "
                            + "is asserting that money moved, so an unattributed resolution is the one write this "
                            + "module refuses to make");
        }

        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException(
                    "a resolution of transfer " + transferId + " by '" + by + "' says nothing about what they "
                            + "looked at. A claim about money is worth exactly its basis, and a resolution without "
                            + "one cannot be checked by anybody later - which is most of the reason for recording it");
        }
    }
}
