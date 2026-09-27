package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: record a member's commitment, and book what it does to the ledger.
 *
 * <p><strong>Since ADR 0049 it is also where "a member of the group" is decided, and where the commitment is
 * attributed.</strong> Recording a commitment is an act within a group - the same reading its own void has had since
 * ADR 0045 - so the caller must be in the group, asked of {@code Groups.isMember} before anything is written. And the
 * caller's id travels on as {@code Commitment.createdBy}, which is what turns "no identity module yet, so nothing can
 * say who submitted it" (the comment this replaced) into a fact the ledger carries.
 *
 * <p>Returns a {@link Result} rather than throwing, because the failures this use case can produce are
 * expected ones: an unknown currency, a member owing themselves, an amount that cannot be posted. The
 * kernel's rule is that expected failures are values, and this is the first place in the module where that
 * rule has a subject - {@code DomainErrorResolver}'s documentation notes that the path for a {@code Result}
 * mapped to a status had no cases yet.
 *
 * <p><strong>Returns rather than throws, and therefore also catches.</strong> {@code libs/fx} declares its
 * failures as exceptions that implement {@code DomainError}, which the resolver maps to a {@code 500} as a
 * programming fault. For a rate that could not be obtained that is the wrong reading - it is a port's
 * declared failure, not a bug - so the implementation catches it and hands it back as a value. The
 * disagreement is real and is recorded in MASTER-PLAN section 15 rather than papered over by loosening the
 * resolver, which would then treat an actual fault as a client error.
 */
public interface RecordObligation {

    /**
     * Records the commitment and books the entries it produces, if the caller is a member of the group.
     *
     * <p>Idempotent on the request's key: a second call with the same key in the same group answers with
     * what the first one recorded, and writes nothing.
     *
     * @param callerId who is asking, from the boundary's session - the member this commitment is attributed to
     *                 (ADR 0049)
     * @return the obligation as recorded, or the reason it was refused
     */
    Result<RecordedObligation> record(UUID callerId, NewObligation request);
}
