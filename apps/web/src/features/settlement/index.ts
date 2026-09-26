export { RunScreen } from './ui/RunScreen';
export { RunsScreen } from './ui/RunsScreen';
export { alreadyAnswered, isFinished, isKnownStatus, toneOf } from './model/decision';

/**
 * The feature's public surface (RULE F8).
 *
 * The routes get the two screens; the mutation, the tables and the refusal mapping stay private, so a later screen
 * can render a run without being coupled to how this one is laid out. The three exported functions are the parts with
 * a judgement in them - what a status means to a reader, whether the screen should keep offering an answer, and
 * whether a run has finished - and each has a test that does not need a browser.
 */
