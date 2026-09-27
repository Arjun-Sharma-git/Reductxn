export { ActivityScreen } from './ui/ActivityScreen';

/**
 * The group's history.
 *
 * One screen, because the feed is one read. What it does not export is the entity's types: a consumer that needs to know
 * what an event *is* reads `entities/activity`, and this feature is how one is shown.
 */
