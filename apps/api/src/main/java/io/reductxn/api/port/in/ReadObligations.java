package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.List;
import java.util.UUID;

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
 * <p><strong>A {@code Result} since ADR 0054, and the sentence it replaces is the one every read in this module used
 * to end with.</strong> This port said "nothing here is a {@code Result}: every narrowing the contract declares is
 * answerable, and a group with no obligations and a group that does not exist are both the empty list - groups are
 * not tables in this build". The narrowing is still answerable; what changed is who may ask for it. A group's
 * obligations name both parties of every commitment, so an empty page for a stranger's group was not a harmless
 * answer - it was the group's ledger, or a convincing imitation of it.
 */
public interface ReadObligations {

    /** A page of them, newest first unless the filter says otherwise, or the reason the group is not readable. */
    Result<List<RecordedObligation>> list(UUID callerId, ObligationFilter filter);
}
