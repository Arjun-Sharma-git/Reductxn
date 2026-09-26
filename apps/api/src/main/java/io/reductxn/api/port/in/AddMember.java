package io.reductxn.api.port.in;

/**
 * The use case: record a member of a group.
 *
 * <p><strong>No {@code Result}, because nothing here can fail.</strong> The boundary refuses a malformed request
 * before this is called, and the store cannot refuse: {@code (groupId, userId)} is the table's primary key and the
 * insert is {@code ON CONFLICT DO NOTHING}, so a membership that already exists is a {@code 200} with the row as it
 * stands rather than a refusal. A refusal for a duplicate would be this use case reporting an idempotent call as a
 * mistake, which is the shape P8 exists to avoid.
 *
 * <p><strong>A member is data, not a consequence of activity.</strong> The alternative considered was deriving the
 * list from the parties of the group's obligations, which needs no table and no write - and it is wrong for two
 * reasons that both show on a screen: a member who has promised nothing would be missing from the list of people who
 * can be named, and the name would be a uuid, because obligations carry ids and this build has no user records to
 * look a name up in (ADR 0035).
 */
public interface AddMember {

    /**
     * Records the membership if the group does not already have it, and answers with the member either way.
     *
     * @return the member as stored, with {@link AddedMember#created()} saying whether this call recorded them
     */
    AddedMember add(NewMember member);
}
