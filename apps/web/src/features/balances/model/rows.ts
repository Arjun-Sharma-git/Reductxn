import { nameOf, type Member } from '@/entities/member';
import type { Balances } from '@/entities/netting';

/**
 * A position, with the group's name for the member if it has one.
 *
 * The join lives here rather than in the screen so it can be tested without a browser, and because it is the one
 * thing this screen does that the server does not: the balances read answers with positions keyed by identifier
 * (ADR 0039), and the names are the client's to resolve.
 */
export type BalanceRow = {
  /** One row per member per currency, which is what the server sends - so both parts are needed to be unique. */
  readonly key: string;
  readonly userId: string;
  readonly name: string;
  readonly direction: 'pay' | 'receive' | 'flat';
  readonly net: { readonly amountMinor: number; readonly currency: string };
};

/**
 * The server's positions, in the server's order, with names where the group has them.
 *
 * **The order is not changed**, and that is deliberate: the server's answer is grouped by currency because the plan
 * is, and a screen that re-sorted would be a second ordering rule to keep in step with the first (ADR 0039).
 *
 * **A position without a name keeps its identifier.** `nameOf` returns the identifier rather than "Unknown member",
 * and here that is the only honest answer, twice over: the catalogue may simply not have loaded, and a cross-currency
 * group has a position whose holder is the currency clearing account rather than a person at all. The screen says
 * which of the two it might be rather than guessing between them.
 */
export function balanceRows(positions: Balances, members: readonly Member[]): BalanceRow[] {
  return positions.map((position) => ({
    key: `${position.userId}-${position.net.currency}`,
    userId: position.userId,
    name: nameOf(members, position.userId),
    direction: position.direction,
    net: position.net,
  }));
}

/** Whether any row is showing an identifier where a name would go, which is what the footnote is for. */
export function hasUnnamedRows(rows: readonly BalanceRow[]): boolean {
  return rows.some((row) => row.name === row.userId);
}
