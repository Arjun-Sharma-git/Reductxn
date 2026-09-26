package io.reductxn.api.adapter.persistence;

import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The one expression that decides whether a tax rule applies at an instant.
 *
 * <p><strong>Two readers, one condition.</strong> The settlement path resolves a rule to assess a partition and the
 * rules read reports which rules exist; if each wrote its own window, the two could disagree and nothing would say so -
 * a group would be shown a rate its settlement did not use, or worse, charged one its own rule list omits. The
 * condition is therefore here, next to both of them, rather than copied.
 *
 * <p>The window is half-open: a rule is in force from its {@code effective_from} instant inclusive until its
 * {@code effective_to} instant exclusive, and a null end means unbounded. Half-open is what makes a handover between
 * two rules well defined - the instant one ends is the instant the next begins - and it is the same shape the totals
 * port uses for a period, for the same reason.
 *
 * <p>The comparison is in UTC because the columns are {@code timestamptz} and an instant is an instant: what the spec
 * says about a group's settlement timezone is about which *day* a cycle belongs to (`docs/specs/tax-rules.md` section
 * 4), and that is decided where a day is turned into a range rather than here.
 */
final class TaxRuleWindow {

    static final Field<OffsetDateTime> EFFECTIVE_FROM = DSL.field("effective_from", OffsetDateTime.class);
    static final Field<OffsetDateTime> EFFECTIVE_TO = DSL.field("effective_to", OffsetDateTime.class);

    private TaxRuleWindow() {
    }

    /** The condition a rule must satisfy to be the one in force at {@code asOf}. */
    static Condition inForceAt(Instant asOf) {
        OffsetDateTime at = asOf.atOffset(ZoneOffset.UTC);

        return EFFECTIVE_FROM.le(at).and(EFFECTIVE_TO.isNull().or(EFFECTIVE_TO.gt(at)));
    }
}
