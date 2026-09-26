import { createFileRoute } from '@tanstack/react-router';

import { AcceptInviteScreen } from '@/features/invites';

/**
 * The acceptance route: a code in the URL and one question.
 *
 * This file is the URL, and nothing else (RULE F4). It is the one route in the client that is not under `/groups/$groupId`,
 * and that is the contract's shape rather than a shortcut: the operation is addressed by the code, because the code is
 * what carries the group (ADR 0041). A person following an invitation link has no group id to put in the path and does
 * not need one - the server resolves the group from the code.
 *
 * The code is passed through as it was typed. Normalising it here would be a second place that knows the alphabet, and
 * the boundary already trims and uppercases it - one normalisation, where the wire becomes a use case.
 *
 * No `staticData.requireReviewedLocale`: the rule refuses an unreviewed translation where a member is told what they will
 * pay or where a plan is approved (`docs/specs/i18n.md` section 8.4). This screen records a membership and no money, so
 * an unreviewed locale falls back to English per key.
 */
export const Route = createFileRoute('/invites/$code')({
  component: AcceptInviteRoute,
});

function AcceptInviteRoute() {
  const { code } = Route.useParams();

  return <AcceptInviteScreen code={code} />;
}
