package io.reductxn.settlement.application;

import io.reductxn.settlement.port.in.OpenCycle;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.out.Runs;

import java.time.Clock;
import java.util.Objects;

/**
 * Opening a cycle, which is one row and one derivation.
 *
 * <p>The whole use case, and it is three lines because there is nothing to decide: which instant is the caller's,
 * the identity is {@link ComputeRunService}'s derivation rather than a second one - a cycle opened here and a
 * cycle computed later must be the same row, and the only way to be sure of that is to derive its id in one place
 * - and the clock is used for "when it was opened", which is a fact about the request rather than about the
 * cycle.
 *
 * <p><strong>It takes the shared derivation rather than repeating it.</strong> {@code cycleIdOf} is
 * package-private in this package for exactly this: an id scheme copied into a second class is a scheme that can
 * drift, and its drift would not be a compile error or a failed constraint - it would be a cycle nobody can
 * compute, discovered a cadence later. That the two callers are in one package is the design, not an accident of
 * this slice.
 */
public class OpenCycleService implements OpenCycle {

    private final Runs runs;
    private final Clock clock;

    public OpenCycleService(Runs runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean open(RunRequest request) {
        Objects.requireNonNull(request, "request");

        return runs.openCycle(
                ComputeRunService.cycleIdOf(request.groupId(), request.asOf()),
                request.groupId(),
                request.asOf(),
                clock.instant());
    }
}
