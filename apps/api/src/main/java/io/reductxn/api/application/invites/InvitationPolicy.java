package io.reductxn.api.application.invites;

import java.time.Duration;

/**
 * The two rules both creation paths apply: how long a code lasts, and how many times it may be spent.
 *
 * <p><strong>It exists because ADR 0055 gave the policy two callers.</strong> Until the share link there was one
 * operation that made codes and this lived inside it; now there are two, and two copies of "seven days" is how a share
 * link comes to outlive an invitation without anybody deciding that it should. The values are the same for both because
 * the instrument is the same one: a code is a bearer credential, and what changes between the two kinds is what holding
 * it entitles somebody to rather than how long it lives.
 *
 * <p><strong>Neither value is a property, and the reason is reproducibility.</strong> A window a deployment can change
 * is a window whose expiry nobody can explain from the code: a test asserting "seven days" would be asserting whatever
 * the properties file said, and an invitation made before a configuration change would have an expiry that cannot be
 * derived from anything. When a caller needs to choose - a longer link for an accountant doing a quarter's review - the
 * contract gains a body and these become parameters, which is a decision somebody makes on purpose (ADR 0041's
 * reasoning, now applied to both operations).
 *
 * <p><strong>No limit rather than a count.</strong> An invitation is a link a group shares with somebody it trusts, and
 * what stops it is time; a share link is the same, and its uses are not counted at all - reading through one spends
 * nothing, because a limit is about how many people a group lets in.
 */
final class InvitationPolicy {

    /** How long a code lasts, for both kinds. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** No limit on how many times a membership code may be spent. Share links are never spent. */
    static final Integer NO_LIMIT = null;

    private InvitationPolicy() {
    }
}