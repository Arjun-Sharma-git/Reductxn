package io.reductxn.api.port.out;

import io.reductxn.api.port.in.RecordedObligation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading an obligation and setting its {@code voided_at}.
 *
 * <p><strong>Why a port of its own rather than a method on {@link Obligations}.</strong> That port is the list read: it takes
 * a filter and answers pages, and it has no way to name one obligation by id. Adding `findOne` and `void` to it would make one
 * port two things - a page read and a single-row write - and the list's own queries would then sit beside an update in the
 * same class. This keeps the write on a port whose whole subject is the transition, while the status it reports comes from
 * the same derived expression the list uses ({@code ObligationStatuses}), so a voided row cannot be described one way on the
 * list and another way here.
 *
 * <p><strong>{@code setVoidedAt} carries the state in its own {@code WHERE} clause.</strong> The service has already decided
 * whether the row may be voided, but two callers can race between that decision and this statement - so the update is written
 * as {@code WHERE id = ? AND voided_at IS NULL} and answers the row as it stands afterwards, voided by this call or by the
 * one that won. Checking and then writing unconditionally would be the check-then-act race the invitation acceptance and the
 * group create both avoid, and there is no reason for this path to be the one that loses it.
 */
public interface ObligationVoids {

    /** The obligation with its derived status, or empty when no row has that id. */
    Optional<RecordedObligation> findOne(UUID obligationId);

    /**
     * Sets {@code voided_at} if it is still unset, and answers the row as it then stands.
     *
     * @return the obligation, voided by this call or already voided by another
     */
    RecordedObligation setVoidedAt(UUID obligationId, Instant at);
}
