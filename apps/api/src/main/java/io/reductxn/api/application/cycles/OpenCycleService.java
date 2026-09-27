package io.reductxn.api.application.cycles;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.NewCycle;
import io.reductxn.api.port.in.OpenCycle;
import io.reductxn.api.port.in.OpenedCycle;
import io.reductxn.api.port.out.CycleToOpen;
import io.reductxn.api.port.out.Cycles;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's books are closed at an instant, so record it - if the caller is one of its members.
 *
 * <p><strong>It has one rule and no computations, which is the shape it should have.</strong> Nothing is checked about
 * the instant (a future cycle is a legitimate "settle what is recorded by then", and the worker resolves everything at
 * the instant rather than at now) and nothing about whether there is anything to settle - that last absence is the
 * interesting one: the commitments are this module's own rows, so it could be checked, and checking it would be wrong,
 * because a commitment recorded tomorrow can carry an instant inside this cycle. The worker refuses to plan an empty
 * cycle when it gets there, which is the honest moment to decide.
 *
 * <p><strong>The rule that is here arrived last, and the interface it lives behind argued it could not exist.</strong>
 * {@link OpenCycle} said the absence of a {@code Result} was a claim: the pair {@code (group, asOf)} is the table's
 * only constraint and a second request for a pair is the same cycle again, so "nothing here can be refused" was true of
 * the row. It was not true of the *caller* - the argument reasoned about a write and not about who was writing - and
 * closing a group's books is an act within the group (ADR 0049). The refusal is {@code no_such_group} for the reason
 * every other group write gives: one answer for "no such group" and "not yours", so a guessed id cannot be tested by
 * comparing two refusals.
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
    private final Groups groups;

    public OpenCycleService(Cycles cycles, Groups groups) {
        this.cycles = Objects.requireNonNull(cycles, "cycles");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<OpenedCycle> open(UUID callerId, NewCycle request) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(request, "request");

        if (!groups.isMember(request.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(request.groupId()));
        }

        return Result.ok(cycles.open(new CycleToOpen(
                UUID.randomUUID(), request.groupId(), request.asOf(), request.requestedAt())));
    }
}
