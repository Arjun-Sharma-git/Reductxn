package io.reductxn.api.application.cycles;

import io.reductxn.api.port.in.NewCycle;
import io.reductxn.api.port.in.OpenCycle;
import io.reductxn.api.port.in.OpenedCycle;
import io.reductxn.api.port.out.CycleToOpen;
import io.reductxn.api.port.out.Cycles;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's books are closed at an instant, so record it.
 *
 * <p><strong>It has no rules, and here that is even more true than usual.</strong> There is nothing to check: not
 * whether the group exists (groups are not tables in this build), not whether the instant is in the future (a
 * future cycle is a legitimate "settle what is recorded by then", and the worker resolves everything at the instant
 * rather than at now), and - the refusal a reasonable implementation would add - not whether there is anything to
 * settle. That last one is the interesting absence: the commitments are this module's own rows, so it could be
 * checked, and checking it would be wrong, because a commitment recorded tomorrow can carry an instant inside this
 * cycle. The worker refuses to plan an empty cycle when it gets there, which is the honest moment to decide.
 *
 * <p><strong>The identity is minted rather than derived, and that is the point of this class being so short.</strong>
 * The worker derives a cycle's id from {@code (group, asOf)} and uses it as a proposal - {@code JooqRuns.open}
 * inserts it with {@code on conflict do nothing} and then reads back whichever row the pair actually has. So an id
 * from here and an id from there are both fine, and the alternative - this module deriving the same id - would copy
 * a key scheme across a deployable boundary, where its drift would not be a compile error or a constraint violation
 * but a cycle nothing ever computes. What makes the cycle unique is the pair, and that is a database constraint
 * rather than a convention two modules have to agree on.
 */
@Service
public class OpenCycleService implements OpenCycle {

    private final Cycles cycles;

    public OpenCycleService(Cycles cycles) {
        this.cycles = Objects.requireNonNull(cycles, "cycles");
    }

    @Override
    public OpenedCycle open(NewCycle request) {
        Objects.requireNonNull(request, "request");

        return cycles.open(new CycleToOpen(
                UUID.randomUUID(), request.groupId(), request.asOf(), request.requestedAt()));
    }
}
