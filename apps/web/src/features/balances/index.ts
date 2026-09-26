export { BalancesScreen } from './ui/BalancesScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * A route imports the screen and nothing else: `model/rows` is private, so the join between positions and names can
 * change without another feature noticing, and a reviewer can see everything this slice offers in one line.
 */
