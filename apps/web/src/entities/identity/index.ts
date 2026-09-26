export { currentUserQuery, type CurrentUser } from './queries';

/**
 * The signed-in person, as an entity.
 *
 * One read and one type, which is the whole of what identity exposes to a screen: everything else about a session - the
 * tokens, their windows, renewing them - belongs to `shared/session` and `shared/api`, because a screen must never hold a
 * token and no screen has a reason to. What a screen wants is a name to put in the header, and this is it.
 */
