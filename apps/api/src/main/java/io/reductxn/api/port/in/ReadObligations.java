package io.reductxn.api.port.in;

import java.util.List;

/**
 * The use case: a group's obligations, narrowed the way the caller asked and ordered how they asked.
 *
 * <p>This is the read the obligations screen has always called and the API never served (ADR 0035's audit found it,
 * and it is the worst of the three: the list is the screen the whole interface is organised around). Until now the
 * only way to see an obligation this module had recorded was to record it and read the response.
 *
 * <p>It answers {@link RecordedObligation}, the same type the write path answers with, and that is deliberate rather
 * than convenient: a list row and a created obligation are one shape on the wire - the contract says so - and a
 * second DTO for rows is the second description of "what an obligation looks like" that ADR 0021 spends its length
 * refusing. A page of obligations is a page of the same thing the form just made.
 *
 * <p>Nothing here is a {@code Result}: every narrowing the contract declares is answerable, and a group with no
 * obligations and a group that does not exist are both the empty list - groups are not tables in this build, which
 * is the same sentence the runs and cycles reads end with.
 */
public interface ReadObligations {

    /** A page of them, newest first unless the filter says otherwise. */
    List<RecordedObligation> list(ObligationFilter filter);
}
