# ADR 0001 - Non-custodial architecture

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principles P2, P3; RULEs B4, B17

## Context

reductxn sits between group members and CBDC rails that levy a transaction tax per transfer.
The obvious design is to hold balances in-app and pay out on demand. That makes reductxn a
custodian of funds, which in most jurisdictions means e-money or money-transmitter licensing.

## Decision

reductxn never holds CBDC and never holds private keys. It maintains a commitment ledger of
obligations, computes net settlement positions, and emits settlement instructions through a
`PaymentRail`. The rail executes; the rail is authoritative for executed movements. reductxn
stores the rail reference and reconciles against it.

## Consequences

- **Easier:** no licensing as a custodian in the target posture; no key management or HSM
  custody; smaller blast radius if compromised.
- **Harder:** settlement depends on rail availability and rate limits, so reconciliation and
  backpressure become core product concerns rather than nice-to-haves. Netting shifts
  counterparty risk onto the group, which must be surfaced in the UI.

## Alternatives considered

- **Custodial wallet** - rejected: licensing burden and custody risk dwarf the product value.
- **Smart-contract escrow** - rejected: depends on programmable CBDC, which most CBDC designs
  explicitly do not offer to third parties.
- **Pure record-keeping, no settlement** - rejected: removes the tax-saving value proposition.
