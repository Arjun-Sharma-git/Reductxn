package io.reductxn.api.application.obligations;

import io.reductxn.api.port.in.ObligationFilter;
import io.reductxn.api.port.in.ReadObligations;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.Obligations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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
 */
@Service
public class ReadObligationsService implements ReadObligations {

    private final Obligations obligations;

    public ReadObligationsService(Obligations obligations) {
        this.obligations = Objects.requireNonNull(obligations, "obligations");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecordedObligation> list(ObligationFilter filter) {
        Objects.requireNonNull(filter, "filter");

        int limit = filter.limit() <= 0
                ? ObligationFilter.DEFAULT_LIMIT
                : Math.min(filter.limit(), ObligationFilter.MAX_LIMIT);

        return obligations.listFor(new ObligationFilter(
                filter.groupId(), filter.status(), filter.memberId(), filter.from(), filter.to(),
                filter.sort(), limit));
    }
}
