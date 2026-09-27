package io.reductxn.api.application.obligations;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ObligationFilter;
import io.reductxn.api.port.in.ReadObligations;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.Obligations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's obligations, narrowed and paged.
 *
 * <p>It owns one decision - the clamp - and nothing else. The boundary has already refused a filter it could not
 * read, the adapter does the narrowing in SQL, and this class exists because {@code web} may not reach an adapter
 * (RULE B12).
 *
 * <p><strong>The clamp is the same one the runs and cycles reads use, and it is a decision rather than a
 * formality.</strong> A caller who asks for a thousand obligations gets the maximum instead of an error: the request
 * is answerable, it is just bigger than this endpoint serves, and a refusal would make a screen that asks for a big
 * page fail entirely rather than show the biggest page it can. A caller who asks for none, or for a negative number,
 * gets the default, because "no limit" is not a limit and a zero-row page is nobody's question.
 *
 * <p><strong>And since ADR 0054 it owns a second decision: whose page this is.</strong> The membership check is the
 * first statement, before the clamp and before the query, and that ordering is the point: the filter carries a
 * `memberId` the caller chooses, so a check that came after the read would have read a page of a stranger's group to
 * decide whether to show it. The refusal is {@code NoSuchGroup}, the same one every other read of a group's books
 * answers with, and the same one the write on this endpoint has asked since ADR 0049 - so the list and the form that
 * fills it finally agree about who may look.
 */
@Service
public class ReadObligationsService implements ReadObligations {

    private final Groups groups;
    private final Obligations obligations;

    public ReadObligationsService(Groups groups, Obligations obligations) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.obligations = Objects.requireNonNull(obligations, "obligations");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<RecordedObligation>> list(UUID callerId, ObligationFilter filter) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(filter, "filter");

        if (!groups.isMember(filter.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(filter.groupId()));
        }

        int limit = filter.limit() <= 0
                ? ObligationFilter.DEFAULT_LIMIT
                : Math.min(filter.limit(), ObligationFilter.MAX_LIMIT);

        return Result.ok(obligations.listFor(new ObligationFilter(
                filter.groupId(), filter.status(), filter.memberId(), filter.from(), filter.to(),
                filter.sort(), limit)));
    }
}
