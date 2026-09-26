package io.reductxn.api.port.in;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a caller asked the obligations list for: a group, and the narrowings the contract declares.
 *
 * <p>Every field except the group is optional, and each one narrows in the way the contract's own description says -
 * a status, a member on either side, a date range, an order. The list is unbounded in the plan and bounded here by
 * {@code limit}, for the reason the runs and cycles reads give: a group's obligations accumulate for as long as the
 * group exists, so "a page of the most recent" is the useful question, and this endpoint answers it.
 *
 * <p><strong>The dates are UTC days, and that is a stand-in rather than a decision.</strong> A group's obligations
 * are incurred at instants; a member thinks in days, and the day a date falls on depends on a timezone this build
 * does not store - a settlement timezone is a property of a group, and a group has none yet. So {@code from} and
 * {@code to} are read as UTC, the screen shows UTC, and when the group's timezone arrives this filter moves with it
 * rather than quietly meaning something else.
 *
 * <p>{@code sort} and {@code status} are parsed at the boundary, so nothing below this line has to ask what a string
 * meant: a value neither knows is refused as a malformed request before a use case is called.
 */
public record ObligationFilter(
        UUID groupId,
        ObligationStatus status,
        UUID memberId,
        LocalDate from,
        LocalDate to,
        Sort sort,
        int limit) {

    public ObligationFilter {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(sort, "sort");
    }

    /** The contract's default: a ledger is read from the most recent commitment backwards. */
    public static final Sort DEFAULT_SORT = Sort.NEWEST_FIRST;

    /** The contract's `limit` default, and what a caller who names no limit gets. */
    public static final int DEFAULT_LIMIT = 50;

    /** The contract's `limit` maximum: a page bigger than this is a page nobody reads. */
    public static final int MAX_LIMIT = 200;

    /**
     * The order a page comes in.
     *
     * <p>Two values rather than a free string, because the contract's example is {@code -occurredAt} and the minus
     * prefix is the only ordering vocabulary this build has. A second field to sort by would be a second index to
     * design, so it arrives with the index that makes it cheap.
     */
    public enum Sort {

        /** Newest first, which is the contract's example and this list's default. */
        NEWEST_FIRST("-occurredAt", false),

        /** Oldest first, which is how a group reads its own history forwards. */
        OLDEST_FIRST("occurredAt", true);

        private final String wireName;
        private final boolean ascending;

        Sort(String wireName, boolean ascending) {
            this.wireName = wireName;
            this.ascending = ascending;
        }

        /** The word the query parameter carries. */
        public String wireName() {
            return wireName;
        }

        public boolean ascending() {
            return ascending;
        }

        /** The sort a query parameter names, or empty when it names something this build does not order by. */
        public static Optional<Sort> fromWire(String candidate) {
            for (Sort sort : values()) {
                if (sort.wireName.equals(candidate)) {
                    return Optional.of(sort);
                }
            }

            return Optional.empty();
        }

        /** Every word this endpoint accepts, for a refusal that tells a caller what it does accept. */
        public static String vocabulary() {
            return "-occurredAt or occurredAt";
        }
    }
}
