# ADR 0012 - Multicurrency settlement: net per currency, convert the residual only

- **Status:** accepted
- **Date:** 2026-09-25
- **Related:** principles P1, P2, P3, P12; ADR 0004, ADR 0005, ADR 0011, ADR 0013,
  ADR 0014; RULEs L1, L6, B3, B18, B19

## Context

Groups are not currency-homogeneous. A member in Tokyo owes a member in Moscow in yen; a member in
Mumbai owes a member in New York in rupees. The product must support `JPY -> RUB` and `INR -> USD`
style obligations, converted at the time the commitment is made.

The current design is single-currency **by construction**, not by accident:

- `NettingRequest` validates that every obligation shares one currency and throws
  `CurrencyMismatchException` otherwise.
- `Money.plus`/`minus`/`compareTo` throw on a currency mismatch.
- Ledger invariant **L1** says debit and credit totals must balance *per currency*.
- Ledger invariant **L6** says every posting in a journal entry shares one currency.

So multicurrency cannot be bolted on as an extra currency field. Something has to give, and the
candidates are L1 and L6.

Also decisive: reductxn is non-custodial (P2). It does not hold balances of any currency. It can
**value** a currency it does not hold, but it cannot **produce** one.

## Decision

**1. Netting stays single-currency and does not change.** A multicurrency cycle is partitioned by
currency, and each partition is handed to the existing engine unchanged. Every property N1-N9
therefore continues to hold **per currency**. `libs/netting` needs no new algorithm, and its proofs
survive intact. This is the central point of this decision.

**2. An obligation may declare a settlement currency different from its debt currency.** The
`currency` on an obligation is what the debtor owes. An optional `settleInCurrency` is what the
creditor is to receive. When the two differ the obligation is *cross-currency* and must carry a
locked FX quote (ADR 0013). Conversion happens when the obligation is **recorded**, not when the
**3. A cross-currency obligation produces two single-currency journal entries**, linked by
`fx_link_id`, with a dedicated internal `fx_clearing` account balancing each side:

```
JPY book:  Alice       -50,000      fx_clearing  +50,000
INR book:  fx_clearing -27,952      Boris        +27,952
```

Each entry is internally balanced, so **L1 and L6 are both preserved exactly as written**. L1 is not
weakened and L6 is not deleted. The two entries form one logical transaction linked by `fx_link_id`,
and the link is what makes the pair auditable.

**4. The `fx_clearing` account is excluded from member netting but included in the group balance
report.** It is owned by `owner_type = 'fx'`: a real account with a real position, not a slush fund.

**5. If `fx_clearing` is non-zero in a currency at the end of a cycle, the group has a genuine
external FX requirement.** That is surfaced as an explicit, quantified obligation, exactly as
settlement risk is surfaced today.

This is the honest consequence of P2. Take a group with one cross-currency obligation and nothing
else: Alice owes JPY, Boris is owed INR. Netting leaves `fx_clearing` at `+50,000 JPY` and
`-27,952 INR`. Settling that requires someone to actually exchange yen for rupees. reductxn must say
so, in the "who pays whom" report, rather than quietly declaring the group settled.

**6. `libs/fx` is a new pure module** owning rate representation, conversion, and valuation.
`libs/ledger` and `libs/netting` do not gain FX awareness.

**7. Reporting uses a separate base currency and a separate reporting rate** (ADR 0013). The locked
settlement rate is contractual and immutable; the reporting rate is presentational and may be
restated. Conflating them would either misstate history or wrongly bind the product to a rate it
never promised.

## Consequences

**What becomes easier.**

- The netting engine and its proofs are untouched. This is the main payoff: the hard, verified part
  of the system is not disturbed by a feature that could easily have rewritten it.
- The ledger model is untouched. L1, L2, L3 and L6 all still hold, so the existing DB constraints,
  the hash chain, and the append-only grants all keep working.
**What becomes harder, and must be accepted.**

- **Cross-currency is more expensive than single-currency.** The `fx_clearing` leg forces at least
  one additional transfer per currency pair, and under a per-transfer tax that costs real money. A
  cross-currency obligation can therefore be strictly worse than two members settling within one
  currency. This must be shown to the user, not hidden: the tax-savings panel should be able to say
  "settling in a common currency would have saved N".
- **Netting cannot net the FX leg away.** The FX account is an internal counterparty, so the
  per-currency transfer counts each include a leg attributable to it. The N-1 bound still holds per
  currency, but the total across currencies is higher than a single-currency cycle of the same size.
- **The hash chain is per `(group, currency)` and now has cross-chain links.** A pair of entries in
  two different chains must verify as a pair. Chain verification therefore needs a second pass that
  checks `fx_link_id` integrity, because a valid chain does not by itself prove that its partner
  entry exists or matches. This is a new invariant (L9) and a new verifier job.
- **The composition of a cross-currency plan is a new modelling surface.** Whether to convert at
  obligation level, at net-position level, or not at all is a genuine cost trade-off. v1 fixes the
  choice (obligation level); a later ADR may add strategy selection.
- **FX rate sourcing becomes an operational dependency.** Rates come from a provider, with a spread,
  with staleness bounds, and with an outage mode. That is new operational surface with new failure
  modes.

**Alternatives considered, and why rejected.**

- **Convert every obligation into one group currency at settlement time.** Simple to state, but it
  changes what was promised: a creditor who agreed to receive a specific converted amount would
  receive whatever the rate happened to be later. Someone must then bear the drift, which is either
  the group (hidden risk) or the product (violates P2). Rejected.
- **Net across currencies by valuing everything in a base currency, then netting the base totals.**
  The tempting one, and it is wrong. It destroys per-currency zero-sum, so a netted plan would no
  longer be settlable in the currencies the members actually hold. It breaks L1 and N1, and it moves
  FX risk onto the group invisibly. Rejected.
- **Delete or weaken L6 to allow mixed-currency postings.** Would let one entry carry both sides
  directly, which looks simpler, but it destroys the per-currency balance check that currently makes
  the ledger self-verifying, and it makes the DB constraint impossible to express. Rejected: two
  linked entries cost one extra column, weakening L6 costs the invariant.
- **Have reductxn perform the FX and hold the inventory.** Requires holding funds and a licence.
  Directly contradicts P2. Rejected.
- **Assume the rail always does FX, so the clearing position is never the group's problem.** True for
  some rails, false for others, and it hides a real requirement behind an assumption. Modelled
  instead as a rail capability (ADR 0013): if the rail converts, the requirement is satisfied by the
  rail; the group is told which case applies.
- **Store rates as `double` and convert with floating point.** Violates P1. Rejected without
  discussion, and ADR 0013 specifies the exact replacement.

## Open question

The cost of the `fx_clearing` leg means there is a real optimisation to be had later: choose, per
cycle, between obligation-level conversion, net-position-level conversion, and no conversion, by
comparing total cost including tax. This is the same "compare the plans, keep the cheaper" structure
that ADR 0011 introduced, extended across currency strategies. Deliberately out of scope for v1,
recorded here so the choice is not lost.
