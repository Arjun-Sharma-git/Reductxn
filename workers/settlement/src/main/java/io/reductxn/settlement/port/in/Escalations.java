package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What a pass found when it went looking for rails that have stopped answering (section 4.4's "or escalate").
 *
 * <p><strong>Two answers, because a report needs both.</strong> {@code total} says how bad it is; {@code transfers}
 * says which ones it is, oldest silence first, and stops at the limit so that a broken rail cannot turn a log line
 * into a novel. A single list would conflate the two - the truncation would quietly understate the problem, which
 * is the one number an operator acts on.
 *
 * <p><strong>The cut-off is part of the report, because the report is meaningless without it.</strong> "Three
 * transfers are stuck" is a statement about a policy ("silent for longer than two hours"), and a reader that did
 * not know which policy would be reading a number it cannot interpret or reproduce.
 *
 * @param cutOff       the instant the policy drew the line at: a transfer is here when its silence began at or
 *                     before this
 * @param total        how many transfers are silent at or before the cut-off, however many that is
 * @param transfers    the oldest {@code limit} of them, so the worst cases are the ones a human sees
 */
public record Escalations(Instant cutOff, int total, List<SilentTransfer> transfers) {

    public Escalations {
        Objects.requireNonNull(cutOff, "cutOff");
        transfers = List.copyOf(transfers);

        if (total < transfers.size()) {
            throw new IllegalArgumentException(
                    "the report says " + total + " transfer(s) are silent but lists " + transfers.size()
                            + ". The list is a sample of the total, so it cannot be the larger of the two");
        }
    }

    /** Nothing has been silent long enough to escalate, which is the ordinary answer. */
    public static Escalations none(Instant cutOff) {
        return new Escalations(cutOff, 0, List.of());
    }

    /** Whether the list was cut short by its limit, so a caller can say so rather than imply nothing else exists. */
    public boolean truncated() {
        return transfers.size() < total;
    }
}
