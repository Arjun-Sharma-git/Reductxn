package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: record a member's answer on a settlement run.
 *
 * <p><strong>This is the smallest use case in the module, and what is missing from it is the point.</strong> It
 * contains no quorum arithmetic, no membership check and no run lifecycle: those belong to the run, they are
 * computed from the run's own stored rows, and they live in the module that owns the run
 * ({@code workers/settlement}). What this use case does is put a member's act where those rules can see it, and
 * answer honestly about what the store said - which is why it refuses only three things: a run that does not exist,
 * a member who has already answered, and an answer the run's own rows will not accept.
 *
 * <p><strong>Why a write rather than a message to the worker.</strong> The two deployables never depend on each
 * other (RULE B6) and there is no broker in this build; what they share is one schema, and an approval is a row in
 * it. That arrangement works rather than merely being available because quorum was designed as a pure function over
 * stored rows (ADR 0016): a row written here is judged by the same arithmetic as one written by the worker's own
 * use case, and a row from a member the run does not concern is inert by construction. ADR 0020 records the
 * decision, its alternatives and what it deliberately leaves out.
 *
 * <p>A {@link Result} rather than an exception, for the module's usual reason: these are failures a client can
 * act on, and the kernel keeps expected failures as values.
 */
public interface RecordDecision {

    /**
     * Records the answer.
     *
     * <p>Not idempotent by key like {@code POST /obligations}, and deliberately not: the answer is identified by
     * the run and the member rather than by a client-chosen key, a member has one answer, and a second attempt gets
     * the conflict it deserves rather than a silent replay. The primary key on {@code (run_id, member_id)} is what
     * makes that true for two requests arriving at once, rather than only for one.
     *
     * @return the answer as recorded, or the reason it was not
     */
    Result<RecordedDecision> record(NewDecision decision);
}
