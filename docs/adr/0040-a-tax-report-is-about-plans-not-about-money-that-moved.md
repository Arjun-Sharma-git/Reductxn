# ADR 0040 - A tax report is about plans, not about money that moved

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`ReadTaxRules`, `ReadTaxReport`, `ConfiguredTaxRules`, `TaxTotals`,
  `JooqConfiguredTaxRules`, `JooqTaxTotals`, `TaxRuleWindow`, `TaxMapper`, `TaxController`), `apps/web`
  (`entities/tax`, `features/tax`, the `/groups/$groupId/tax` route), and the contract: the report's missing schema,
  the rules schema corrected, both operations described; 12 API tests, 5 unit tests and 5 browser tests
- **Related:** ADR 0038 (the check that recorded both gaps and now reports two fewer), ADR 0036 (what a run that ended
  without settling *releases*, which is what the report reads), ADR 0037 (the read before it, and the same "as of now"
  argument for a reference read), ADR 0012 (no cross-currency total), ADR 0034 (the cycle's instant), ADR 0006 (tax law
  as data); RULE B12, B15, B17, C1, C3; `docs/specs/tax-rules.md` sections 2, 4, 6 and 7

## Context

`GET /tax/rules` and `GET /tax/reports` were the last two operations of section 6's route tree still unserved - ADR 0038
recorded them as one screen, the report "depending on the rules read above it". Three things made this slice a decision
rather than a query:

1. **The report had no response schema.** The path, the summary ("Tax report for a period, including savings versus
   pairwise settlement") and two parameters existed; `200: Tax report` had no content. So the shape of a tax report was
   not under-specified but *unwritten*, and it had to be designed before it could be served - the only operation in the
   document in that state.

## Decision

**The report is the tax a group's settlement plans have assessed, per currency, over a period of cycle instants.** It
reads the stored plan - the per-currency columns a run writes when it computes, which are `libs/netting`'s answers about
the partition - rather than re-running the engine, for the same reason the runs read projects them: a report that netted
the cycle again to read a run could disagree with the run it is reporting on.

**Which plans count, and why the two exclusions are readings.** Every computed plan except the two states that mean the
plan did not happen: `rejected` is the group refusing it, `failed` is one attempted and not completed. That is exactly
what the obligations list says about a run that ended without settling - it *releases* what it held (ADR 0036) - so a
refused plan's tax is released rather than counted as a cost. A run in `pending` or `computing` has no figures yet and
is absent because there is nothing to report, not because of a filter; `awaiting_approval` and `awaiting_fx_funding`
count, because a computed plan is precisely what the group is being asked about.

**The period is the cycle's `as_of`, and its end day is inclusive.** `as_of` is the instant the books were closed and
the instant every rate and tax rule was resolved at (`docs/specs/tax-rules.md` section 4), so it is the date a tax
belongs to - a run computed today for a cycle closed last quarter is last quarter's tax. A caller asks in days, because
a period is a human interval; the conversion to a half-open instant range happens in the use case, which is the one place
where a day stops being a day. The days are UTC, which is a stand-in of the same kind the obligations list records: the
spec wants the group's settlement timezone, and a group's timezone is a property of a group that does not exist as a
table yet.

**There is no headline figure, and the parameter list is the reason.** A single "tax owed" would have to be expressed in
some currency and adding rupees to yen is not addition (ADR 0012). The contract gives this operation no reporting
currency, so it cannot be asked for one - and `docs/specs/tax-rules.md` section 6 says the same from the other end: a
reporting-currency total is an estimate that must be restated with its rate and must never be used to compute a transfer,
an approval or an assessment. An endpoint with no way to ask for one cannot be misused as one. `runCount` is the field
that keeps the line honest instead: it says how many plans the line is made of.

**The report says what it is not.** These are plan figures, not tax collected or remitted. Section 7 of the spec leaves
"are we a withholding agent" open as a legal question whose default posture is compute-and-report; the operation's
description and the screen both say which half of that this is, because a reader who assumed the other half would be
making the most expensive mistake this product can support.

**`TaxRule` is corrected to describe what the table holds.** It gains `params` (an open object, because the three shapes
have their own keys and they belong to the module that assesses them) and `sourceRef` (the statute, which is what makes a
rate defensible); it loses `description`, which nothing stores and which ADR 0035 removed from `Member` for the same

## What the client decides, and what it refuses to

- **The parameters are shown as pairs, not interpreted.** A screen that rendered `rateBps: 150` as "1.5%" would be
  asserting a unit it was never told, and the arithmetic that turns a basis-point rate into a charge is the server's. The
  screen lists the keys and values each rule carries, nested values included, so nothing is hidden behind a placeholder.
- **An inverted period is not asked.** The API answers a backwards range with an empty report rather than an error, so
  the screen refusing to send it is a courtesy rather than a rule - and the browser suite asserts that no request is made,
  not merely that the button is disabled.
- **The period is a question the server is asked.** It lives in the URL (so a range is shareable, like the obligations
  filters) *and* in the request, and the browser test records the request rather than only reading the URL back: an URL
  assertion cannot tell whether the screen used it.

## Consequences

- **Serving two operations means deleting two entries.** `ContractCoverageTest` now reports thirty operations, sixteen
  served and fourteen recorded with an owner; the tax screen is the last of section 6's route tree besides the group's
  invites.
- **The rules read and the settlement path cannot drift, and that is asserted rather than intended.** Both resolve a rule
  through `TaxRuleWindow`, and the test seeds two rules for one currency - one closed, one in force, with different rates
  - asserting that the read shows the one in force and that a preview charges the same rule's rate.
- **One schema lie was corrected in each direction.** `effectiveFrom`/`effectiveTo` were `date`s for `timestamptz`
  columns; `description` was a field with no column. Both were promises the document could not keep, and neither had a
  consumer, which is why they survived this long.
- **A gap is recorded rather than guessed at.** The `TaxRule` schema carries no field saying which kinds the engine can
  execute, so a client cannot warn that a threshold rule will make a settlement refuse. The fix, when a consumer needs it,
  is an `executable`-shaped field for both endpoints - or making the engine assess thresholds, which is the real answer and
  a slice of its own.
- **The tax screen has no headline number, and that is visible rather than implied.** The report card counts no totals and
  its footnote says why: it is the same rule the netting preview follows for its own partitions (ADR 0012), applied to the
  one screen where a reader most expects a single figure.

## Alternatives considered - and why rejected

**Report only tax that settled, from runs that reached `done`.** It is the reading a phrase like "tax report" invites, and
it would be unimpeachable once a rail exists. Rejected twice over: no run reaches `done` in this build, so the operation
would answer zero for every group forever; and the concept is wrong regardless, because what a group owes is what its
plans assess. A report of money that moved is a reconciliation, which is a different endpoint and needs a rail to have
moved anything.

**Add a reporting currency to the operation and answer one total with an estimate label.** Rejected because the
contract's parameters do not offer one, and because a restatable estimate needs the restatement model section 6
describes - a rate as of an instant, and a report that says the figure is an opinion about history. Adding the parameter
first would produce a number nobody could restate. The endpoint that needs it can ask for one then.

**Route the rules read through `libs/netting`'s `TaxRule`.** Rejected because that type is a rule the engine can *execute*
- it assesses an amount and declares the optimizer's objective - and it carries no identifier, no jurisdiction and no
statute. A read of configured rules is a read of rows, and dressing rows up as executable rules would mean building engine
objects in an inbound adapter for nobody's benefit.

**Populate `description` from `source_ref`, or keep both.** Rejected as the dishonest fix: a description is prose and a
source reference is a citation, and a screen showing the citation under the heading "Description" would be telling a
reader that a statute's name is a summary of the rule. Removing the field is what ADR 0035 did with `creditLimit`.

**Have the client translate parameters into human sentences.** Rejected because the client does not implement the tax
engine and would have to know all three shapes, including any fourth that arrives - which is exactly the second model of a
rule's parameters that this module has twice refused to write on the server side.

reason - a field nobody sets is a field nobody notices is wrong; and its window becomes `date-time`, because the columns
are instants, the settlement path compares instants, and reporting a window as a day would lose the boundary a group's tax
turns on.

**The rules read is "in force now", and it shares its window with the settlement path.** "Active tax rules" was the
summary; what it means is a half-open window against an instant, which is the same condition `TaxRuleCatalog` resolves a
rule by. Both now ask `TaxRuleWindow`, one expression in one class, and
`reportsTheRulesTheSettlementPathWouldActuallyUse` asserts end to end that the rule a group is shown is the rule its
preview charges - because two copies of a window agree everywhere except where it matters.

**A `threshold` rule is listed even though nothing can assess it.** `libs/netting` implements flat, ad valorem and a
no-tax rule; a partition governed by a threshold rule cannot be netted at all, and the settlement path refuses rather than
assessing zero. Listing the row is the honest answer to "which rules are in force" - the data exists, and hiding it would
leave a reader staring at a settlement that refuses for a reason they cannot see.

2. **No run can reach `done` in this build.** `settlement_runs` names eight states and its migration says only four are
   reachable: `executing`, `done`, `rejected` and `failed` "need quorum and a rail, which do not exist yet". A report of
   *settled* tax would therefore read zero for every group forever - and, more to the point, would be the wrong concept
   even with a rail: what a group owes is what its plans assess.
3. **The rules schema described something the table does not hold.** `TaxRule` promised `effectiveFrom`/`effectiveTo` as
   `format: date` while the columns are `timestamptz` and the settlement path compares instants in a half-open window; it
   carried a `description` that nothing stores; and it omitted `params_json` and `source_ref`, which the table requires
   precisely so that "a rule can be defended to an auditor rather than only executed". A rules read serving that schema
   could not tell a reader what the rate *was*.
