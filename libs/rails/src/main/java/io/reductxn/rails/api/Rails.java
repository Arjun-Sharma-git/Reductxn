package io.reductxn.rails.api;

import io.reductxn.rails.internal.breaker.GuardedRail;
import io.reductxn.rails.internal.mock.MockRail;

import java.time.Clock;

/**
 * The way to obtain a rail implementation.
 *
 * <p><strong>This class exists because of RULE B10.</strong> Rail implementations live in
 * {@code io.reductxn.rails.internal.*} and nothing outside this module may name them - so a deployable cannot
 * write {@code new MockRail()}. That is the point of the rule, and it leaves a hole that has to be filled
 * deliberately: something has to be able to build one. The sanctioned answer, used by every other module that
 * keeps its implementations private, is a public factory in the {@code api} package that returns the port. The
 * public surface stays here, the implementations stay private, and the only thing that crosses is the type
 * callers are allowed to see.
 *
 * <p>The alternative - making the mock's class public - would make the module boundary ornamental. It is
 * exactly the arrangement {@code SettlementPlans} and {@code FxConverters} already have.
 *
 * <p>ADRs aside, this is also what MASTER-PLAN section 10.1 asks for ("implement {@code PaymentRail} in
 * {@code libs/rails/internal/<name>/}") with one correction: the recipe's second step says to register the
 * adapter in {@code apps/api/adapter/rail/}, which was written when {@code apps/api} was the only deployable.
 * The rail is called by whoever executes a run, and that is {@code workers/settlement} - so the registration
 * lives in that module's bootstrap, and {@code apps/api} continues to neither know nor care that a rail exists
 * (RULE B6).
 */
public final class Rails {

    private Rails() {
    }

    /**
     * A deterministic rail that moves nothing and confirms what it accepts.
     *
     * <p>The default for tests and demos, and the only implementation today: it settles INR, JPY and USD, which
     * are the currencies the catalogue seeds, and refuses anything else rather than pretending. It exists so
     * that "the run executed" is a reachable state in a deployment that has no CBDC connection yet, rather than
     * vocabulary in an enum.
     */
    public static PaymentRail mock() {
        return new MockRail();
    }

    /**
     * The same rail, with a circuit breaker in front of it.
     *
     * <p>A decorator rather than something a rail implementation does, because "stop calling it" is a decision
     * about the <em>caller's</em> behaviour and not the rail's: an adapter should implement the rail's protocol,
     * and nothing about the protocol says when to give up for a while. It also means the breaker is testable
     * against any rail, including the mock, and that a future HTTP adapter gets it without being written twice.
     *
     * <p>The clock is passed in rather than read, so that a breaker's cooldown is testable without sleeping and
     * so that this module never reads a clock of its own (the same rule the use cases follow).
     *
     * @param rail   the rail to guard
     * @param policy when to open, and for how long
     * @param clock  what "now" means for the cooldown
     */
    public static PaymentRail withCircuitBreaker(PaymentRail rail, BreakerPolicy policy, Clock clock) {
        return new GuardedRail(rail, policy, clock);
    }
}
