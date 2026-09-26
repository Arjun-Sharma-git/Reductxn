/**
 * The session, as the browser remembers it: the token pair and when the access half stops working.
 *
 * **It is one module because a token in two places is a token that disagrees with itself.** The request layer reads it to
 * send a bearer header, the sign-in screen writes it, and the header of the page reads it to say who is signed in. Three
 * readers and one writer, all through these functions, is the only way "am I signed in" has one answer.
 *
 * **`localStorage` rather than memory or a cookie, and the trade is stated rather than implied.** A token in memory is
 * lost on every navigation, which is a sign-in per page. A cookie would be sent automatically and need CSRF protection
 * this API does not have. `localStorage` is readable by any script on the origin - so the access token is deliberately
 * short-lived (thirty minutes) and the refresh token is the thing that would be worth stealing. The honest fix is a
 * server-set `HttpOnly` cookie, which needs the API to be same-site with the client and a CSRF story; until then this is
 * the stand-in, and it is recorded (ADR 0042).
 *
 * **Nothing here throws.** A browser that refuses storage (private mode, a locked-down profile) gets a session that lasts
 * until the page is closed rather than an application that cannot sign anybody in: the functions fall back to a value in
 * memory, which is exactly what a session is from the API's point of view.
 */

export type Session = {
  readonly accessToken: string;
  readonly refreshToken: string;
  /** When the access token expires, as epoch milliseconds - the refresh token's window is the server's. */
  readonly expiresAt: number;
};

const KEY = 'reductxn.session';

/** What a browser with no storage keeps instead, so a session still works for the life of the page. */
let inMemory: Session | null = null;

/** The stored session, or null when there is none or it cannot be read. */
export function storedSession(): Session | null {
  try {
    const raw = globalThis.localStorage?.getItem(KEY);

    return raw === null || raw === undefined ? inMemory : (JSON.parse(raw) as Session);
  } catch {
    // Unparseable storage is not a session: a stale or hand-edited value must not stop the application from starting.
    return inMemory;
  }
}

/** Records a session, replacing whatever was there. */
export function storeSession(session: Session): void {
  inMemory = session;

  try {
    globalThis.localStorage?.setItem(KEY, JSON.stringify(session));
  } catch {
    // See above: the in-memory copy is what makes this survivable.
  }
}

/** Forgets the session. The server's row is what actually ends it; this stops this browser from sending it. */
export function forgetSession(): void {
  inMemory = null;

  try {
    globalThis.localStorage?.removeItem(KEY);
  } catch {
    // Nothing to do: there was no readable storage to clear.
  }
}

/**
 * A session from the contract's token pair.
 *
 * `expiresIn` is seconds, and it arrives as a number a client could read as milliseconds - which is why the conversion
 * happens here, once, and why the field is named `expiresAt` rather than `expiresIn` on this side: the name says which of
 * the two it is.
 */
export function sessionFrom(pair: {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly expiresIn: number;
}): Session {
  return {
    accessToken: pair.accessToken,
    refreshToken: pair.refreshToken,
    expiresAt: Date.now() + pair.expiresIn * 1000,
  };
}
