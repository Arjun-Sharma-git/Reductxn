package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.time.Instant;
import java.util.UUID;

/**
 * The use case: record that a commitment should never have been written.
 *
 * <p><strong>A transition, not a deletion</strong> (ADR 0004): the row stays, keeps its instant, and gains a {@code voided_at}
 * that the derived status reads (ADR 0036). Deleting it would erase the fact that a member recorded it, and a group that
 * cannot see its own mistakes cannot audit them.
 *
 * <p><strong>This is the first write narrowed by membership, and the order of the checks is the reason it is written
 * out.</strong> The caller comes from the token (ADR 0043); then the row is read, because the group is only knowable from
 * the row; then, and <em>before</em> anything about the row's state is weighed, the caller's membership is checked. So a
 * non-member gets {@link ObligationRefusals.NoSuchObligation} whatever the obligation's status is - they cannot learn from
 * the answer whether a commitment is open, held by a run, or already voided.
 *
 * <p><strong>Idempotent by state rather than by key.</strong> Voiding an obligation that is already voided answers `200` with
 * the row: the caller asked for a state it is in. There is no {@code Idempotency-Key} on this operation, and the contract's
 * one is deleted, because a key would create a second record of an act the row already records.
 */
public interface VoidObligation {

    /**
     * Voids the obligation, or answers with it if it is already voided.
     *
     * @param obligationId the obligation
     * @param callerId     who is asking, from the token
     * @param at           the instant the void happened, from the clock rather than from the read's own
     * @return the obligation with its new status, or a refusal: {@link ObligationRefusals.NoSuchObligation} when it does not
     *         exist or is not the caller's group's, {@link ObligationRefusals.NotOpen} when a run holds or has settled it
     */
    Result<RecordedObligation> voidOne(UUID obligationId, UUID callerId, Instant at);
}
