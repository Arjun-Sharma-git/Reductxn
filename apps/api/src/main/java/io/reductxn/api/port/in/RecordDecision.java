package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: record a member's answer on a settlement run.
 *
 * <p><strong>This is the smallest use case in the module, and what is missing from it is the point.</strong> It
 * contains no quorum arithmetic and no run lifecycle: those belong to the run, they are computed from the run's own
 * stored rows, and they live in the module that owns the run ({@code workers/settlement}). What this use case does
 * is put a member's act where those rules can see it, and answer honestly about what the store said.
 *
 * <p><strong>One membership check joined it in ADR 0053, and it is a different question from the quorum's.</strong>
 * "Who may write this row" is the group's fact - the same {@code Groups.isMember} the other group writes ask, with
 * the same {@code no_such_group} refusal, because it is the same fact (ADR 0049). "Whose answer counts" stays the
 * run's arithmetic over its own participants, and the two must not be collapsed: a member of the group who owes
 * nothing may still answer, their row is still written and still visible, and it still counts for nothing. ADR
 * 0016's amendment keeps raw rows visible for exactly that reason. Asking the narrow question here instead would be
 * this deployable re-deriving the worker's scope from another module's tables, which is the drift ADR 0020 exists to
 * avoid.
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
     * <p><strong>The caller travels beside the record rather than inside it</strong>, the arrangement
     * {@code OpenCycle#open} and {@code NewCycle} already keep: a caller is who is asking rather than what is being
     * asked. Here it is also the only shape that cannot forge: the answering member *is* the caller, so a command
     * able to name a different member would be a command able to answer for somebody who never spoke.
     *
     * <p>Not idempotent by key like {@code POST /obligations}, and deliberately not: the answer is identified by
     * the run and the member rather than by a client-chosen key, a member has one answer, and a second attempt gets
     * the conflict it deserves rather than a silent replay. The primary key on {@code (run_id, member_id)} is what
     * makes that true for two requests arriving at once, rather than only for one.
     *
     * @param callerId who is answering, from the boundary's session - the condition this use case gained in ADR 0053
     * @return the answer as recorded, or the reason it was not
     */
    Result<RecordedDecision> record(UUID callerId, NewDecision decision);
}
