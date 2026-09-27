package io.reductxn.api.application.groups;

import io.reductxn.api.port.in.ActivityEvent;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadActivity;
import io.reductxn.api.port.out.ActivityFeed;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: what has happened in a group.
 *
 * <p><strong>The membership check and the read are two statements, and that is a deliberate departure from the group
 * read's shape.</strong> `ReadGroup` narrows inside the query - the store's join means a group the caller is not in cannot
 * be read at all. This one cannot do that, because the three sources do not contain a group's members; there is no join to
 * narrow. So the check is a call to the same store the group read uses, and the ordering of the two statements is the
 * guarantee: <em>the check comes first, and a failure returns before anything about the group is read.</em> A feed is the
 * read that names people, so this is exactly where the ordering has to be visible in the code.
 *
 * <p><strong>The limit is clamped here, and the clamp is the paged reads' own.</strong> A request for every event a group
 * has ever produced is a request a screen cannot render, and a `limit` of zero or a negative number is a request for a page
 * that is not a page. The default is the contract's fifty, the ceiling is the contract's and every other read's two hundred.
 */
@Service
public class ReadActivityService implements ReadActivity {

    /** The contract's default when a request names no limit. Public because the boundary names it: the contract's default
     * is the web layer's business to apply, and this is where the number lives so the two cannot disagree. */
    public static final int DEFAULT_LIMIT = 50;

    /** The contract's `maximum`, and the same ceiling the obligations, runs and cycles reads apply. */
    static final int LIMIT_CEILING = 200;

    private final Groups groups;
    private final ActivityFeed feed;

    public ReadActivityService(Groups groups, ActivityFeed feed) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.feed = Objects.requireNonNull(feed, "feed");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<ActivityEvent>> of(UUID groupId, UUID callerId, int limit) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(callerId, "callerId");

        int page = clamp(limit);

        // First, and before anything about the group is read: is this group the caller's? A feed names people, so a read
        // that answered first and checked afterwards would have read a stranger's group to find out it should not have.
        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        return Result.ok(feed.recent(groupId, page));
    }

    /**
     * The page size this read will actually answer with.
     *
     * <p>A limit below one becomes one rather than being refused: the contract's schema is an integer with a default and a
     * maximum, and no minimum, so a client that sent `0` has asked for nothing rather than sent something invalid. Answering
     * with an empty page would be defensible too; one event is the choice made here because a client rendering a feed has no
     * way to ask for more, and a `0` is far more likely to be a bug on its side than an intent (ADR 0044).
     */
    private static int clamp(int limit) {
        if (limit < 1) {
            return 1;
        }

        return Math.min(limit, LIMIT_CEILING);
    }
}
