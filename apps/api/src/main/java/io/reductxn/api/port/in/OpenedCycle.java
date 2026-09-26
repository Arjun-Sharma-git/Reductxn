package io.reductxn.api.port.in;

import java.util.Objects;

/**
 * The cycle a request for one produced, and whether this request is what put it there.
 *
 * <p><strong>{@code created} is in the value rather than in the boundary's choice of status.</strong> The store is
 * the only place that can know whether an insert happened or found a row, so it reports it, and the controller
 * answers {@code 201} for a cycle this request opened and {@code 200} for one that was already open. The
 * alternative - the boundary asking "does this cycle exist?" before writing - is the check-then-insert that loses
 * the race a unique constraint exists to settle.
 *
 * <p>The cycle itself is a {@link CycleReport}, not a second record with the same fields: what the write knows that
 * the read does not is one boolean, and one boolean is what it adds. The report's {@code runId} is populated here
 * too, which is what makes the {@code 200} answer truthful - a cycle that already had a plan is reported with it
 * rather than as "waiting".
 */
public record OpenedCycle(CycleReport cycle, boolean created) {

    public OpenedCycle {
        Objects.requireNonNull(cycle, "cycle");
    }
}
