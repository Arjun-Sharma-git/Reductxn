export { SignInScreen } from './ui/SignInScreen';
export { useSignOut } from './mutations';

/**
 * The feature's public surface (RULE F8).
 *
 * Signing in: the screen, the two mutations it calls, and the rule about what a code looks like. What it deliberately does
 * not expose is a session - the tokens go into `shared/session` and a screen never sees them.
 */
