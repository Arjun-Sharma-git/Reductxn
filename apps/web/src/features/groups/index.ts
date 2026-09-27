export { GroupsScreen } from './ui/GroupsScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * Making a group, and looking at the ones you are in. What it does not expose is the home route's paste-a-group-id flow:
 * that is a route's own concern, and it belongs to the screen rather than to this feature's contract with the rest of the
 * application.
 */
