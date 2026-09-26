package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a pass found when it went looking for transfers no rail has decided (section 4.4's "or escalate").
 *
 * <p><strong>One report with two reasons, rather than two reports.</strong> A transfer gets stuck at a rail in two
 * ways - nobody answers, or the rail answers "still pending" for ever - and the question an operator has is the same
 * either way: which movements need me to ask about them? Splitting that into two listings would mean a person on call
 * has to remember to run both, and would put the two halves of one question in two places; so the reasons are rows in
 * one report ({@link StuckTransfer#reason()}) and each carries the age that made it a problem (ADR 0026).
 *
 * <p><strong>Two answers, because a report needs both.</strong> {@code total} says how bad it is; {@code transfers}
 * says which ones it is, oldest trouble first, and stops at the limit so that a broken rail cannot turn a log line
 * into a novel. A single list would conflate the two - the truncation would quietly understate the problem, which is
 * the one number an operator acts on.
 *
 * <p><strong>The cut-offs are part of the report, because the report is meaningless without them.</strong> "Three
 * transfers are stuck" is a statement about two policies ("unanswered for longer than two hours", "at the rail for
 * longer than one"), and a reader that did not know which lines were drawn would be reading numbers it can neither
 * interpret nor reproduce.
 *
 * @param unansweredCutOff  the instant a silence must have begun at or before for {@link StuckTransfer.Reason#NO_ANSWER}
 * @param unconfirmedCutOff the instant a submission must have been accepted at or before for
 *                          {@link StuckTransfer.Reason#NO_CONFIRMATION}
 * @param total             how many transfers are stuck at or before those cut-offs, however many that is
 * @param transfers         the oldest {@code limit} of them, so the worst cases are the ones a human sees
 */
public record StuckTransfers(
        Instant unansweredCutOff,
        Instant unconfirmedCutOff,
        int total,
        List<StuckTransfer> transfers) {

    public StuckTransfers {
        Objects.requireNonNull(unansweredCutOff, "unansweredCutOff");
        Objects.requireNonNull(unconfirmedCutOff, "unconfirmedCutOff");
        transfers = List.copyOf(Objects.requireNonNull(transfers, "transfers"));

        if (total < transfers.size()) {
            throw new IllegalArgumentException(
                    "the report says " + total + " transfer(s) are stuck but lists " + transfers.size()
                            + ". The list is a sample of the total, so it cannot be the larger of the two");
        }
    }

    /** Nothing has been stuck long enough for anybody to look, which is the ordinary answer. */
    public static StuckTransfers none(Instant unansweredCutOff, Instant unconfirmedCutOff) {
        return new StuckTransfers(unansweredCutOff, unconfirmedCutOff, 0, List.of());
    }

    /** Whether the list was cut short by its limit, so a caller can say so rather than imply nothing else exists. */
    public boolean truncated() {
        return transfers.size() < total;
    }
}
