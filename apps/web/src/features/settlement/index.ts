export { RunScreen } from './ui/RunScreen';
export { RunsScreen } from './ui/RunsScreen';
export { alreadyAnswered, isFinished, isKnownStatus, toneOf } from './model/decision';
export { cycleState, openBody } from './model/cycle';

/**
 * The feature's public surface (RULE F8).
 *
 * The routes get the two screens; the mutation, the tables, the cycles card and the refusal mapping stay private, so
 * a later screen can render a run without being coupled to how this one is laid out. The exported functions are the
 * parts with a judgement in them - what a status means to a reader, whether the screen should keep offering an
 * answer, whether a run has finished, what a cycle with no plan means, and whether a typed instant is one - and each
 * has a test that does not need a browser.
 */
