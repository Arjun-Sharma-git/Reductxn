package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: one group, if the caller is in it.
 *
 * <p><strong>Membership is the read's condition, not a check after it.</strong> A group the caller is not a member of is
 * answered exactly as a group that does not exist - one code, {@code api.no_such_group} - because telling them apart would
 * let anybody with a guess learn which ids are real. The contract declares a single {@code 404} for this read, and this is
 * what it means.
 *
 * <p>It exists because the group's own screens need a name to put above them, and because a screen reached by typing an id
 * should be able to say whether that id is a group this person belongs to. What it deliberately does <em>not</em> do is
 * become a precondition for the other group reads: those serve every group id in this database, including the ones with
 * commitments and no group row, which is the state this build's development data is in (ADR 0043).
 */
public interface ReadGroup {

    /**
     * The group, with its membership count.
     *
     * @return the group, or {@link GroupRefusals.NoSuchGroup} when there is no such group or the caller is not in it
     */
    Result<GroupView> read(UUID groupId, UUID userId);
}
