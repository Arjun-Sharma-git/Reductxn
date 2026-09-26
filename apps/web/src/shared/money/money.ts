import { refuse, type Refusal } from '@/shared/lib/result';

/**
 * Money in the client is what it is on the server: integer minor units and a currency code (P1, RULE F9).
 *
 * **Why `number` is acceptable here, and where the line is.** The wire format declares `amountMinor` as an
 * `int64`, and `JSON.parse` produces a double - so a value above 2^53 would be *silently changed* before any
 * code of ours saw it. What makes that safe today is the server's own bound: `Money` refuses amounts above
 * 10^15, which is well inside 2^53 (9.007e15). The line is therefore drawn at the edge rather than assumed:
 * {@link money} refuses anything that is not a safe integer, so a value the server should never have sent
 * becomes a visible refusal in the client instead of an amount that is quietly one paisa out. Rounding would be
 * the wrong repair - it would make the client the second place that decides what an amount is.
 */
export type Money = {
  readonly amountMinor: number;
  readonly currency: string;
};

/** The minor-unit exponent is reference data (`/currencies`), never a constant compiled into the client. */
export type CurrencyInfo = {
  readonly code: string;
  readonly minorUnitExponent: number;
};

/** The largest amount the server will accept, from `docs/specs/ledger.md`. Also the client's ceiling. */
export const MAX_AMOUNT_MINOR = 1_000_000_000_000_000;

/**
 * Money, or a refusal.
 *
 * @param amountMinor integer minor units; a magnitude, per L2 - the direction of a movement is carried by the
 *                    payer and the payee, so a negative amount is refused rather than interpreted
 */
export function money(amountMinor: number, currency: string): Money {
  if (!Number.isSafeInteger(amountMinor)) {
    throw new MoneyFault(
      'amount.unsafe_integer',
      `amountMinor ${amountMinor} is not a safe integer; the contract's int64 cannot be carried exactly by a JSON number, and rounding it would invent a value`,
    );
  }

  if (amountMinor < 0) {
    throw new MoneyFault(
      'amount.negative',
      `amountMinor ${amountMinor} is negative; an amount is a magnitude and the direction carries the sign (L2)`,
    );
  }

  if (amountMinor > MAX_AMOUNT_MINOR) {
    throw new MoneyFault(
      'amount.above_bound',
      `amountMinor ${amountMinor} is above the ${MAX_AMOUNT_MINOR} the ledger accepts`,
    );
  }

  if (!/^[A-Z0-9]{3,12}$/.test(currency)) {
    throw new MoneyFault('amount.not_a_currency_code', `${currency} is not a currency code`);
  }

  return { amountMinor, currency };
}

/**
 * A fault rather than a refusal, deliberately.
 *
 * Every one of the checks above is about a value the server should never have produced, or a `Money` built by
 * hand in the client - not about something a user typed. A user's typo is a {@link Refusal} with a code the
 * screen renders next to the field (see `shared/money/parse`); a broken contract is a programming error and
 * belongs in a stack trace.
 */
export class MoneyFault extends Error {
  readonly code: string;

  constructor(code: string, detail: string) {
    super(detail);
    this.name = 'MoneyFault';
    this.code = code;
  }
}

/** The exponent for a currency, or a refusal the screen can render. */
export function exponentOf(currency: string, catalog: ReadonlyMap<string, CurrencyInfo>): number | Refusal {
  const info = catalog.get(currency);

  if (info === undefined) {
    // Not a fault: the catalogue is fetched, and a currency can genuinely be missing from a stale copy - which
    // is a "we cannot render this amount yet" rather than a crash.
    return refuse('amount.unknown_currency', { currency });
  }

  return info.minorUnitExponent;
}
