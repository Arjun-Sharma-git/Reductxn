import type { TaxRule } from '@/entities/tax';

/**
 * The two things the tax screen has to work out for itself.
 *
 * Both are here rather than in the markup so they can be tested without a browser: the client is not the authority on
 * what a rule's parameters mean (that is `libs/netting`'s, and the server passes them through for exactly that reason),
 * and it should not be the authority on whether a period is inverted either - it should only refuse to send a question
 * it can see is backwards.
 */

/** One line of a rule's parameters, as a reader can read it. */
export type ParamLine = { readonly key: string; readonly value: string };

/**
 * A rule's parameters, whatever shape they are.
 *
 * The three shapes have their own keys - `perTransferMinor` for a flat charge, `rateBps` with optional bounds, `tiers`
 * for a threshold - and this function deliberately knows none of them. It shows the keys the rule carries and the
 * values they hold, which is the honest rendering for a client that does not implement the engine: a screen that
 * translated `rateBps: 150` into "1.5%" would be asserting a unit it was not told, and the arithmetic that turns a
 * basis-point rate into a charge is the server's.
 *
 * A nested value is shown as compact JSON rather than as "[object Object]" or as a placeholder, so nothing a rule
 * carries is hidden from the person reading it.
 */
export function ruleParams(rule: TaxRule): readonly ParamLine[] {
  return Object.entries(rule.params as Record<string, unknown>).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value) ?? String(value),
  }));
}

/**
 * Whether a period asks for nothing.
 *
 * Only the case a reader can see is checked - a start after the end - and it is checked here so the screen can say so
 * instead of sending a question whose answer is an empty table. It is not a refusal: the API answers an inverted range
 * with an empty report rather than an error (ADR 0040), so this is a courtesy that saves a request, and a bookmark
 * that carries one will still be answered.
 */
export function isInverted(start?: string, end?: string): boolean {
  return start !== undefined && end !== undefined && start > end;
}
