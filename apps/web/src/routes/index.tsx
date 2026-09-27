import { createFileRoute } from '@tanstack/react-router';

import { GroupsScreen } from '@/features/groups';

/**
 * The way in: your groups.
 *
 * **A thin route (RULE F4), and the screen behind it replaced a placeholder.** Until this slice this route asked for a
 * group id to paste, because `groups` was not a table and identity did not exist to answer "which groups am I in" - the
 * screen said so in its own documentation (ADR 0043). Now the server has both, so the route renders the list, and the paste
 * box it used to be lives on inside the screen for the groups a list cannot show.
 *
 * No `validateSearch` here and no `staticData.requireReviewedLocale`: the root route already declares `lang` for the whole
 * tree, and a group's name and member count are not what a member will pay - the screen that reports that is the netting
 * preview (ADR 0028, `docs/specs/i18n.md` section 8.4).
 */
export const Route = createFileRoute('/')({
  component: GroupsRoute,
});

function GroupsRoute() {
  return <GroupsScreen />;
}
