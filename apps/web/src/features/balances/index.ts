export { BalancesScreen } from './ui/BalancesScreen';
export { SharedViewScreen } from './ui/SharedViewScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * A route imports the screen and nothing else: `model/rows` is private, so the join between positions and names can
 * change without another feature noticing, and a reviewer can see everything this slice offers in two lines.
 *
 * **Two screens, since ADR 0055, and they are the same positions read two ways.** `BalancesScreen` is the group's own
 * view, reached by a member under `/groups/$groupId`; `SharedViewScreen` is what a share link shows, reached by whoever
 * holds the code under `/shared/$code`. They live in one feature because what they present is one thing - a position,
 * with the group's name for it, in the currency it is denominated in - and splitting them across two features would mean
 * two copies of that vocabulary.
 */
