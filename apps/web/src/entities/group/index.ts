export { groupQuery, groupsQuery, type Group } from './queries';

/**
 * A group, as an entity.
 *
 * Two reads and one type. What a screen gets from here is what a group *is* - its name, what it settles in, how many
 * members it has - and nothing about how one is created: that is `features/groups`' business, because creating one is a
 * capability rather than a fact.
 */
