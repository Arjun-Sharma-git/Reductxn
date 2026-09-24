# platform/security

**Purpose:** Spring Security configuration shared by deployables: authentication (passkeys/WebAuthn
and phone OTP), short-lived JWT access tokens with rotating refresh tokens, device binding, and
authorisation policy.

**Public surface:** Spring Security configuration and filter chains. Deployables configure it; they
do not call into it.

**May depend on:** `libs/kernel` and infrastructure libraries (Spring Security, JWT, WebAuthn).

**Must NOT depend on:** `apps/*`, `workers/*`, or any other `libs/*`.

**Must NOT contain:** domain concepts, and **never private key material** (principle P2 / ADR 0001).
reductxn is non-custodial: signing happens on the user's device or inside a payment provider's
wallet. This module integrates with a key-management service; it does not custody keys.

Two things that are security-relevant and easy to get wrong:

- Refresh-token reuse must be detected and must invalidate the whole token family.
- Quorum approval before a settlement executes is an authorisation rule and belongs here, not only
  in the use case (RULE S1).

**Owner:** platform reviewer.
