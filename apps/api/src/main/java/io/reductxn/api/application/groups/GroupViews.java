package io.reductxn.api.application.groups;

import io.reductxn.api.port.in.GroupView;
import io.reductxn.api.port.out.Groups;

/**
 * The one place a stored group becomes the value the API answers with.
 *
 * <p>It is a class rather than three copies of the same eight lines: the create path and both reads all answer with a
 * {@link GroupView}, and a field that the store gained and a mapping forgot would be a field the contract promises and no
 * caller ever sees - the failure that looks like "the client does not show X" for a week.
 */
final class GroupViews {

    private GroupViews() {
    }

    static GroupView of(Groups.StoredGroup group) {
        return of(group, group.memberCount());
    }

    static GroupView of(Groups.StoredGroup group, int memberCount) {
        return new GroupView(
                group.id(),
                group.name(),
                group.currency(),
                group.reportingCurrency(),
                group.settlementTimezone(),
                memberCount,
                group.maxMembers(),
                group.createdAt());
    }
}
