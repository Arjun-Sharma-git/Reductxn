package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: record a member of a group, if the caller is one of them.
 *
 * <p><strong>It answers a {@code Result} since ADR 0048, and the reason it used not to is the point.</strong> This
 * interface described itself as unable to fail, because the store cannot refuse: {@code (groupId, userId)} is the table's
 * primary key and the insert is {@code ON CONFLICT DO NOTHING}, so a membership that already exists is a {@code 200} with
 * the row as it stands rather than a refusal (P8). What can refuse is the *caller* - adding somebody to a group is an act
 * within that group - so the condition arrived from outside the write, which is why the shape changed here and the store
 * did not.
 *
 * <p><strong>A member is data, not a consequence of activity.</strong> The alternative considered was deriving the
 * list from the parties of the group's obligations, which needs no table and no write - and it is wrong for two
 * reasons that both show on a screen: a member who has promised nothing would be missing from the list of people who
 * can be named, and the name would be a uuid, because obligations carry ids and this build has no user records to
 * look a name up in (ADR 0035).
 */
public interface AddMember {

    /**
     * Records the membership if {@code callerId} is a member of the group and the group does not already have
     * {@code member}, and answers with the member either way.
     *
     * @param callerId who is asking, from the boundary's session - the condition this use case gained in ADR 0048
     * @return the member as stored, with {@link AddedMember#created()} saying whether this call recorded them, or
     *         {@code GroupRefusals.NoSuchGroup} when the caller is not in the group
     */
    Result<AddedMember> add(UUID callerId, NewMember member);
}
