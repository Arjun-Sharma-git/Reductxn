package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.time.Instant;
import java.util.UUID;

/**
 * What can be refused about a group's schedule (ADR 0057).
 *
 * <p><strong>Three conditions, and each one is a different sentence to a person.</strong> "This group has not said when
 * its books close" is not "the date you gave has already passed" and neither is "one day is the shortest interval there
 * is" - and a client renders the three differently, which is the test every refusal code in this module has to pass.
 *
 * <p>{@code NoSuchSchedule} answers the read and the clear alike, because there is one condition: no row for this group.
 * It is deliberately *not* the same code as {@code no_such_group}: a group that exists and has not stated a cadence is a
 * different fact from a group that does not exist, and a member reading the answer can act on the difference - one means
 * "set a schedule", the other means "you are looking at somebody else's group".
 */
public final class ScheduleRefusals {

    private ScheduleRefusals() {
    }

    /** The group has no schedule, so there is nothing to read and nothing to clear. */
    public record NoSuchSchedule(UUID groupId) implements DomainError {

        public static final String CODE = "api.no_such_schedule";

        public NoSuchSchedule {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the group " + groupId + " has no schedule, so its books do not close by themselves. Setting one is "
                    + "`PUT /groups/{groupId}/schedule`, and closing them by hand is `POST /groups/{groupId}/cycles`";
        }
    }

    /**
     * The interval is outside the range a cadence can be: fewer than one day, or more than a year.
     *
     * <p>A {@code 400} rather than a {@code 422}, like the other request-shape refusals ({@code api.member_name_too_long}
     * is the same shape): the contract declares the range, so a value outside it is a request the document does not
     * describe rather than a request it describes and refuses.
     */
    public record EveryDaysOutOfRange(int everyDays, int min, int max) implements DomainError {

        public static final String CODE = "api.schedule_every_days_out_of_range";

        public EveryDaysOutOfRange {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "everyDays is " + everyDays + ", and a cadence is between " + min + " and " + max + " days. Zero "
                    + "would close the books on every pass of the worker, and more than a year is not an interval "
                    + "anybody means";
        }
    }

    /**
     * The due instant is not in the future, so the schedule would be due the moment it was set.
     *
     * <p>A {@code 422}: the request is well formed and understood, the answer is no, and asking again with the same
     * instant changes nothing. The alternative - accepting it and letting the worker open a cycle at once - was
     * rejected because it makes this operation a second way to close the books immediately, and because a past instant is
     * almost always a typo in a date field rather than an intention.
     */
    public record DueInThePast(Instant nextDueAt, Instant now) implements DomainError {

        public static final String CODE = "api.schedule_due_in_the_past";

        public DueInThePast {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the schedule's first due instant is " + nextDueAt + " and it is now " + now + ", so the books would "
                    + "be closed the moment the schedule was set. Closing them now is "
                    + "`POST /groups/{groupId}/cycles`; a schedule is for the closures after this one";
        }
    }
}