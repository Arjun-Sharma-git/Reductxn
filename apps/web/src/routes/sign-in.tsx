import { createFileRoute } from '@tanstack/react-router';

import { SignInScreen } from '@/features/sign-in';

/**
 * Signing in: one route, and no group in the path.
 *
 * It is outside `/groups/$groupId` for the same reason the invitation acceptance screen is: a person who is not signed in
 * has no group to be in, and a signed-in person who wants a different account is doing this *before* any group screen
 * matters. Nothing here needs a group id, so nothing here takes one.
 *
 * No `staticData.requireReviewedLocale`: the rule refuses an unreviewed translation where a member is told what they will
 * pay or where a plan is approved (`docs/specs/i18n.md` section 8.4). A sign-in screen shows a phone number and a code and
 * no money, so an unreviewed locale falls back to English per key - and a person who cannot read the fallback can still
 * type the two fields the form asks for.
 */
export const Route = createFileRoute('/sign-in')({
  component: SignInRoute,
});

function SignInRoute() {
  return <SignInScreen />;
}
