export { ObligationsScreen } from './ui/ObligationsScreen';
export { localRefusals, refusalsOf, worthRetrying, type RecordFormValues } from './model/input';

/**
 * The feature's public surface (RULE F8).
 *
 * The route gets the screen; the mutation, the form's field layout and the input mapping stay private, so a later
 * screen can render the form without being coupled to how it is laid out today. The three exported functions are
 * here because they are the parts with rules in them - what a refusal is attached to, and whether a failure is
 * worth repeating - and a test that asserts those directly should not have to reach into `model/`.
 */
