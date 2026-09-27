export { activityQuery, type ActivityEvent } from './queries';

/**
 * What has happened in a group, as an entity.
 *
 * One read and one type, and the type is the contract's own - so a client that branches on `kind` is branching on an enum the
 * server declares rather than on a string somebody typed. The screen that renders it is `features/activity`, because showing
 * a feed is a capability rather than a fact about a group.
 */
