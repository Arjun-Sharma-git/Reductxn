import { createFileRoute } from '@tanstack/react-router';

import { SharedViewScreen } from '@/features/balances';

/**
 * The share-link route: a code in the URL, and what the group owes.
 *
 * <p>This file is the URL, and nothing else (RULE F4). It is the second route outside {@code /groups/$groupId}, and it
 * is there for the same reason the invitation route is: the operation is addressed by the code, because the code carries
 * the group (ADR 0041, 0055). A person following a link has no group id to put in the path, and the server resolves the
 * group from the code.
 *
 * <p><strong>No session is needed, and the route does not check for one.</strong> That is the operation's shape rather
 * than an oversight: the reader is often somebody with no account here at all - an accountant looking at a quarter - and
 * a route that redirected them to sign in would make the whole feature pointless.
 *
 * <p>The code is passed through as it was typed, for the reason the invitation route gives: normalising it here would be
 * a second place that knows the alphabet, and the store canonicalises what it is given.
 *
 * <p>No {@code staticData.requireReviewedLocale}: the rule refuses an unreviewed translation where a member is told what
 * they will pay or where a plan is approved (`docs/specs/i18n.md` section 8.4). This screen shows numbers that a member
 * of the group already sees, and it changes nothing.
 */
export const Route = createFileRoute('/shared/$code')({
  component: SharedViewRoute,
});

function SharedViewRoute() {
  const { code } = Route.useParams();

  return <SharedViewScreen code={code} />;
}