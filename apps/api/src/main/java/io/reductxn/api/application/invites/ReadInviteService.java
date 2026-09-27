package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.InvitePreview;
import io.reductxn.api.port.in.InviteRefusals;
import io.reductxn.api.port.in.ReadInvite;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The use case: what a code is for, and whether it still works.
 *
 * <p><strong>The same three questions the acceptance asks, asked in the same order, and answered with the same
 * codes.</strong> Does the code exist (`404`); is its window open; are its uses spent. The order and the codes are
 * copied from {@link AcceptInviteService} rather than re-derived, because a person who checks a code and a person who
 * spends it are asking one question at two moments - and a preview that said "usable" about a code the acceptance
 * then refused as exhausted would be this build telling somebody two things about one row. What the preview does
 * *not* ask is the acceptance's third question (is this person already a member): there is no person here, which is
 * the whole operation.
 *
 * <p><strong>One read, not two.</strong> The terms and the group's label arrive in one statement
 * ({@code Invitations.previewOf}), so there is no window in which the code exists and its group does not - and no
 * second query whose answer could disagree with the first. That is worth stating because the obvious shape was two
 * calls: {@code findByCode} for the window and something for the name, which would have needed a groups read that
 * answers without a membership, and {@code Groups} deliberately has none.
 *
 * <p>The window is half-open, like every window in this codebase: a code is usable only *before* its expiry instant,
 * and an acceptance exactly at it is too late. The use comparison is the same one {@code spend}'s statement makes
 * (`uses &lt; max_uses`), read here to name the condition rather than to decide it - the acceptance still lets the
 * database settle which of two racing callers gets the last use.
 */
@Service
public class ReadInviteService implements ReadInvite {

    private final Invitations invitations;

    public ReadInviteService(Invitations invitations) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<InvitePreview> preview(String code, Instant at) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");

        Optional<Invitations.Preview> found = invitations.previewOf(code);

        if (found.isEmpty()) {
            return Result.fail(new InviteRefusals.NoSuchInvite(code));
        }

        Invitations.Preview invite = found.get();

        if (!at.isBefore(invite.expiresAt())) {
            return Result.fail(new InviteRefusals.InviteExpired(code));
        }

        // The same condition `spend` carries into its statement, asked here so that "this one has been used" is said
        // before somebody types their name. `maxUses` null means no limit, which is why the null check is the positive
        // case rather than the default.
        if (invite.maxUses() != null && invite.uses() >= invite.maxUses()) {
            return Result.fail(new InviteRefusals.InviteExhausted(code));
        }

        return Result.ok(new InvitePreview(
                code,
                invite.expiresAt(),
                invite.maxUses(),
                new InvitePreview.Group(invite.groupId(), invite.groupName(), invite.memberCount())));
    }
}