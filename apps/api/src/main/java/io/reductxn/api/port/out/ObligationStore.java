package io.reductxn.api.port.out;

import io.reductxn.api.port.in.RecordedObligation;

import java.util.Optional;
import java.util.UUID;

/**
 * Recording obligations, and finding out whether one is already on the record.
 *
 * <p><strong>{@link #findExisting} is not an optimisation.</strong> The contract makes the idempotency
 * header required, and the reason is that a retried request must not record a second commitment - which
 * also means it must not book a second entry, since entry ids derive from the obligation's identity. Asking
 * first is what lets a replay answer with what was recorded instead of appending. The unique index on
 * {@code (group_id, idempotency_key)} is the backstop for the case this read cannot see: two requests
 * arriving at once, where both reads find nothing. The index refuses the second insert, so no duplicate is
 * ever written; the losing request gets an error rather than the winner's response, and turning that into a
 * replay needs a transaction of its own - stated here rather than left to be discovered under load.
 *
 * <p>Returns a port/in type, which is deliberate and worth a sentence: RULE B12 forbids {@code web} from
 * depending on {@code port/out}, so the response the controller maps has to be a {@code port/in} type - and
 * a replay's response is the stored obligation. A second, identical type in this package would be two
 * things to keep in step for no gain, and the alternative - the controller does not answer a replay - would
 * make the required header pointless.
 */
public interface ObligationStore {

    /**
     * The obligation recorded under this key in this group, if there is one.
     *
     * <p>Scoped by group because a key is a client's own string: two groups using "batch-7" are describing
     * two different operations, and a lookup by key alone would answer one group with another's obligation.
     */
    Optional<RecordedObligation> findExisting(UUID groupId, String idempotencyKey);

    /**
     * Writes the obligation.
     *
     * <p>The caller has already decided the id, because it is the identity the entries are derived from and
     * two places generating it would be two identities for one commitment.
     */
    void record(ObligationToRecord obligation);
}
