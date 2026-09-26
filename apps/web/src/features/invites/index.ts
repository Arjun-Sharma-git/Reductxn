export { AcceptInviteScreen } from './ui/AcceptInviteScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * The accept half of the invite flow. The create half lives on the members screen, where the people already in the
 * group are - inviting somebody is how the next one arrives, and it is the same list (ADR 0041). The two halves share
 * the `entities/invite` types rather than each other, which is what RULE F1 requires: a feature reaches entities and
 * shared, never another feature.
 */
