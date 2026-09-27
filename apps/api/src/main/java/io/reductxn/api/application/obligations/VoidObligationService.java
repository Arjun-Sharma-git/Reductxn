package io.reductxn.api.application.obligations;

import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.in.VoidObligation;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.ObligationVoids;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: void an obligation.
 *
 * <p><strong>The order of the three checks is the whole design, so it is written as three statements in order.</strong>
 *
 * <ol>
 *   <li>The row is read, because the group is only knowable from the row - unlike the group and activity reads, there is
 *       nothing to check membership against before the row exists.</li>
 *   <li><strong>Membership is checked before the status.</strong> A caller who is not a member of the obligation's group gets
 *       {@link ObligationRefusals.NoSuchObligation} whatever the row's state is, so the answer cannot be used to learn
 *       whether a commitment is open, held by a run, or already voided. Checking the status first would have leaked exactly
 *       that, one guess at a time.</li>
 *   <li>Only then is the status weighed: {@code open} is voidable, and {@code netted} or {@code settled} is a conflict with a
 *       run. {@code voided} is neither - it answers with the row, which is what makes this idempotent by state.</li>
 * </ol>
 *
 * <p><strong>The void is a transition, and the row keeps its instant</strong> (ADR 0004): {@code voided_at} is set and the
 * derived status becomes {@code voided}, so the group's history still shows that a member recorded this and that somebody
 * corrected it. Nothing is deleted.
 *
 * <p><strong>The write is conditional and the answer is read back.</strong> The store's update only fires where
 * {@code voided_at IS NULL}, so two callers racing on one obligation produce one void - and this service answers with the row
 * as it stands, which is the same answer for both.
 */
@Service
public class VoidObligationService implements VoidObligation {

    private final ObligationVoids obligations;
    private final Groups groups;

    public VoidObligationService(ObligationVoids obligations, Groups groups) {
        this.obligations = Objects.requireNonNull(obligations, "obligations");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Transactional
    public Result<RecordedObligation> voidOne(UUID obligationId, UUID callerId, Instant at) {
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(at, "at");

        Optional<RecordedObligation> found = obligations.findOne(obligationId);

        if (found.isEmpty()) {
            return Result.fail(new ObligationRefusals.NoSuchObligation(obligationId));
        }

        RecordedObligation obligation = found.get();

        // Second, and the reason it is second: a non-member must not learn the status from which refusal they got.
        if (!groups.isMember(obligation.groupId(), callerId)) {
            return Result.fail(new ObligationRefusals.NoSuchObligation(obligationId));
        }

        if (obligation.status() == ObligationStatus.VOIDED) {
            // Asked for a state it is already in. `200` with the row rather than `409`: the caller wanted this outcome, and
            // answering with a conflict would tell a retrying client that its own successful earlier attempt had failed.
            return Result.ok(obligation);
        }

        if (obligation.status() != ObligationStatus.OPEN) {
            return Result.fail(new ObligationRefusals.NotOpen(obligationId, obligation.status().wireName()));
        }

        return Result.ok(obligations.setVoidedAt(obligationId, at));
    }
}
