export { groupCyclesQuery, type Cycle, type OpenedCycle, type OpenCycleBody } from './queries';

/**
 * The entity's public surface (RULE F8): a type and one query.
 *
 * There is no hook here and no component, for the reason `entities/run` gives: a query is what a feature asks for,
 * and which of them a screen shows is the feature's business rather than the entity's.
 */
