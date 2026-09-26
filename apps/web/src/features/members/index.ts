export { MembersScreen } from './ui/MembersScreen';
export { addMemberBody, localRefusals, refusalFor, refusalsOf } from './model/input';

/**
 * The feature's public surface (RULE F8).
 *
 * The route gets the screen; the form, the mutation and the CSS stay private, so a later screen can show a group's
 * members without being coupled to how this one is laid out. The exported functions are the parts with a judgement in
 * them - what a typed member becomes, and which control a server's refusal belongs to - and each has a test that does
 * not need a browser.
 */
