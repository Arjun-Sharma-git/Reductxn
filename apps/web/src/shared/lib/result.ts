/**
 * A result that either succeeded or refused with a stable code.
 *
 * The client's mirror of `io.reductxn.kernel.api.Result`. It exists for the same reason the server has one: a
 * refusal is a value with a code and parameters, not an exception with a sentence. A thrown `Error` carrying
 * "invalid amount" cannot be rendered in three languages, cannot be asserted by code in a test, and has no
 * place to put the boundary the user crossed.
 *
 * Codes are namespaced by the module that refuses them. A server code looks like `ledger.currency_mismatch`; a
 * code the *client* invents looks like `amount.too_many_decimals` - and the catalog check knows the difference,
 * because a client code has no server constant to match against (see `tools/check-catalogs.mjs`).
 */
export type Result<T> = { readonly ok: true; readonly value: T } | Refusal;

export type Refusal = {
  readonly ok: false;
  readonly code: string;
  readonly params?: Readonly<Record<string, string | number>>;
};

export function ok<T>(value: T): Result<T> {
  return { ok: true, value };
}

export function refuse(code: string, params?: Record<string, string | number>): Refusal {
  return params === undefined ? { ok: false, code } : { ok: false, code, params };
}
