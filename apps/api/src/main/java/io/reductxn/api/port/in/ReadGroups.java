package io.reductxn.api.port.in;

import java.util.List;
import java.util.UUID;

/**
 * The use case: the groups a person belongs to.
 *
 * <p><strong>This operation cannot be answered without a caller, and it is the first one that says so.</strong> "The groups
 * you belong to" has no meaning for a request that does not say who is asking - the alternative was a body or query
 * parameter naming a person, which is exactly the shape ADR 0042 recorded as the gap: every boundary before this one took
 * whoever the request claimed to be. A new operation has no callers to break, so it takes the caller from the token
 * (ADR 0043).
 *
 * <p><strong>No {@code Result}, because there is nothing to refuse.</strong> An empty list is a true answer - somebody who
 * belongs to no group - and a membership query cannot fail in a way a client could act on. The one read that *can* refuse
 * is {@link ReadGroup}, which has a group to find.
 *
 * <p>The order is the memberships': most recently joined first, so a group somebody just created is the first thing they
 * see. Nothing else would be defensible - name order would put a group somebody left years ago above the one they are
 * using.
 */
public interface ReadGroups {

    /** The groups this person is a member of, most recently joined first. */
    List<GroupView> of(UUID userId);
}
