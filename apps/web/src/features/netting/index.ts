export { NettingPreviewScreen } from './ui/NettingPreviewScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * A route imports the screen and nothing else: `ui/transfers-table` and `model/…` are private, so a change to
 * how the plan is laid out cannot ripple into another feature, and a reviewer can see the whole interface this
 * slice offers in five lines.
 */
