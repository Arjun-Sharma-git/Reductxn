export { ScheduleScreen } from './ui/ScheduleScreen';

/**
 * The feature's public surface (RULE F8).
 *
 * A route imports the screen and nothing else. The form, the mutations and the CSS stay private, so the way a group
 * states a cadence can change without another feature noticing - and the read itself belongs to the `schedule` entity,
 * because the settlement screens will want to say "this run is for the period that closed on the 1st" one day.
 */