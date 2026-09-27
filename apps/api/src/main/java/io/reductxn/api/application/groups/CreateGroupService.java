package io.reductxn.api.application.groups;

import io.reductxn.api.port.in.CreateGroup;
import io.reductxn.api.port.in.CreatedGroup;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.NewGroup;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.api.port.out.GroupMembers;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: create a group.
 *
 * <p><strong>{@code @Transactional}, because it is two writes and one of them is a promise.</strong> The group row and the
 * creator's membership are one fact - "this person has a group" - and a failure between them would leave a group that its
 * only possible reader cannot see. That is the reasoning the invitation acceptance carries, and the opposite of the reads
 * around it (RULE B15).
 *
 * <p><strong>Every string a request can name is resolved or refused here, never stored on trust.</strong> A currency
 * becomes an exponent through the catalogue, because the alternative is an amount interpreted at the wrong scale. A
 * timezone is parsed, because {@code "IST"} is an abbreviation three countries share and a zone no calendar can resolve is
 * worse than no zone at all - the column is nullable precisely so that "not chosen yet" stays sayable. A name is bounded
 * by the contract's own {@code maxLength}, so a request that breaks the schema's promise is refused rather than stored in a
 * way the schema would then misdescribe.
 *
 * <p><strong>The idempotency key is not validated for shape beyond being present.</strong> The contract bounds it at 128
 * characters and the boundary checks that; what the key is <em>for</em> is the store's, and this use case promises only
 * that a retry with the same key answers with the same group.
 */
@Service
public class CreateGroupService implements CreateGroup {

    /** The contract's {@code maxLength} for a group's name, and the column's constraint. */
    static final int NAME_LIMIT = 120;

    /**
     * The role the creator gets.
     *
     * <p>{@code admin} is recorded and not enforced - the same fact the members read's description states about every role
     * in this build. It is still the right thing to write: the person who made the group is the person who can answer "who
     * is in it", and a later slice that enforces roles would otherwise have to guess which membership was the creator's.
     */
    private static final String ADMIN = "admin";

    private final Groups groups;
    private final GroupMembers members;
    private final CurrencyCatalog currencies;

    public CreateGroupService(Groups groups, GroupMembers members, CurrencyCatalog currencies) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.members = Objects.requireNonNull(members, "members");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
    }

    @Override
    @Transactional
    public Result<CreatedGroup> create(NewGroup request) {
        Objects.requireNonNull(request, "request");

        String name = request.name() == null ? null : request.name().strip();

        if (name == null || name.isEmpty()) {
            return Result.fail(new RunRefusals.RequestIncomplete("name"));
        }

        if (name.length() > NAME_LIMIT) {
            return Result.fail(new GroupRefusals.NameTooLong(NAME_LIMIT));
        }

        Optional<Currency> settledIn = currencies.byCode(request.currency());

        if (settledIn.isEmpty()) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(request.currency()));
        }

        String reportingCode = request.reportingCurrencyOrDefault();
        Optional<Currency> reporting = currencies.byCode(reportingCode);

        if (reporting.isEmpty()) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(reportingCode));
        }

        String timezone = request.settlementTimezone() == null ? null : request.settlementTimezone().strip();
        String chosenZone = timezone == null || timezone.isEmpty() ? null : timezone;

        if (chosenZone != null && !isIanaZone(chosenZone)) {
            return Result.fail(new GroupRefusals.UnknownTimezone(chosenZone));
        }

        Groups.Created created = groups.create(new Groups.NewGroupRow(
                UUID.randomUUID(),
                name,
                settledIn.get(),
                reporting.get(),
                chosenZone,
                request.maxMembers(),
                request.creator().id(),
                request.idempotencyKey(),
                request.createdAt()));

        // The creator's membership, only when this call is what created the group. A replay must not re-add it: the
        // membership write is idempotent on (group_id, user_id) so it would be harmless, but harmless is not the same as
        // asked for, and a replay is not a request to change anything.
        if (created.created()) {
            members.add(new NewMember(
                    created.group().id(),
                    request.creator().id(),
                    creatorName(request),
                    ADMIN,
                    request.createdAt()));
        }

        // The count the caller is owed. The store's read-back ran *before* the membership above was written, so on the path
        // that created the group it reports zero members - true at that instant and wrong in the answer, because the
        // membership this call wrote is one the caller can see in the same breath (`GET /groups/{id}` says 1). On the replay
        // path the read-back already saw whatever memberships existed, so it is taken as it stands.
        int memberCount = created.created()
                ? created.group().memberCount() + 1
                : created.group().memberCount();

        return Result.ok(new CreatedGroup(GroupViews.of(created.group(), memberCount), created.created()));
    }

    /** What the group calls its creator: the deployment's name for them, which is their number until there is a profile. */
    private static String creatorName(NewGroup request) {
        String name = request.creator().displayName();

        // A membership row's name is checked not-empty by its column, so a deployment that somehow knew a person by an
        // empty string would fail the insert. Falling back to the id keeps that failure out of a member's way and keeps
        // the row truthful about what the group knows.
        return name == null || name.isBlank() ? request.creator().id().toString() : name;
    }

    /** Whether this is a zone a calendar can resolve, without the locale machinery RULE B20 keeps out of this module. */
    private static boolean isIanaZone(String timezone) {
        try {
            ZoneId.of(timezone);

            return true;
        } catch (DateTimeException notAZone) {
            return false;
        }
    }
}
