# ADR 0039 - A balance is the plan's positions, as of now

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`ReadGroupBalances`, `ReadGroupBalancesService`, `BalancesController`,
  `NetPositionResponse`), `apps/web` (`features/balances`, the `/groups/$groupId/balances` route,
  `entities/netting`'s `groupBalancesQuery`); the contract's description of the operation; 6 API tests, 4 unit tests
  and 5 browser tests
- **Related:** ADR 0038 (the check that recorded this as a gap and now reports one fewer), ADR 0036 (one definition of
  "open", which this read reuses), ADR 0035 (the members read that makes names possible), ADR 0032 (the cycle's
  instant), ADR 0012 (no cross-currency total), ADR 0037 (the last read before this one); RULE B12, B15, B21, C1, C3
  and the `netting` spec's N1-N9

## Context

`GET /groups/{groupId}/balances` has been in the contract since the netting slice, as an array of `NetPosition` and
one line of summary: "Current net position per member over open obligations". No handler served it, and ADR 0038
recorded it as one of seventeen gaps with an owner - "one of the two screens MASTER-PLAN section 6 lists and its
status row still names as unbuilt".

It sat unbuilt for a reason that turned out to be a design question rather than a lack of time: **the answer already
exists.** `GET /groups/{groupId}/netting/preview` returns `bestCase.positions`, and the preview's own summary is
"what-if netting over currently open obligations" - the same set, the same phrase, and the positions table on the
netting screen renders exactly those rows. So the operation could have been removed rather than built (as ADR 0033
removed three), or built as a second, cheaper computation.

Three facts decided it:

1. **A screen for it was already owed.** MASTER-PLAN section 6's route tree lists a balances screen, and the closest

## Decision

**`GET /groups/{groupId}/balances` answers with the positions of the plan the preview would build, as of now.** The
read resolves the group's open obligations through the same port, partitions and nets them with the same
`SettlementPlanner`, and returns the positions the plan carries - flattened, in the plan's order, one entry per member
per currency. Nothing about the answer is computed for this endpoint alone, which is what makes it impossible for the
two screens to disagree.

**No `asOf`.** A balance is the answer to "what do we owe each other now", and a caller who wants a past instant is
asking the preview's question - which already takes one. Two endpoints that can both be asked about an instant, over
the same set of obligations, are two answers waiting to differ.

**The tax dependency is stated rather than hidden.** The engine assesses tax on the transfers it emits, so
`plan()` needs a rule for every partition and this read inherits that. A group whose currency has no rule in force
cannot be netted at all - and therefore cannot be settled either - so the read refuses in the same way the preview
does rather than reporting a position whose arithmetic nobody can reproduce. This is a real cost of the decision, and
the contract says so in the operation's own description instead of leaving it to be discovered.

**Three things about the response are decisions too.**

- **Per currency, never summed.** A member holding two currencies has two rows. ADR 0012 refuses a cross-currency
  total, so the entries carry their own currency and nothing adds them up.
- **`flat` is included.** A member who owes and is owed the same is a member; a row that disappeared would look like
  somebody who left the group.
- **The clearing account appears as a position.** A cross-currency obligation is booked through `fx_clearing`, so a
  group that has crossed currencies has a position whose holder is that account rather than a person. The schema
  cannot tell the two apart yet, and rather than invent a field for one endpoint, the wart is recorded in the
  contract, in the ADR and on the screen: the row shows the identifier, and the footnote names both cases instead of
  choosing one.

**The client resolves names, and says so when it cannot.** The positions are keyed by identifier, so the screen joins
them to the group's member catalogue (`nameOf`, recorded by ADR 0035) and falls back to the identifier. That fallback
is the same one every other screen uses, and it is why the footnote exists: the reader is told that an identifier
means either the clearing account or a member the catalogue does not name, rather than being shown "Unknown member",
which would be the screen inventing a fact about a person.

**One schema, one Java type.** `NetPosition` was already top-level in the contract and nested inside
`NettingPreviewResponse` in Java - a shape the document never had. It is now `NetPositionResponse`, used by both
endpoints, and the direction words moved into it as the single place a direction becomes a wire value. `MoneyDto`
gained `of(Money)` for the same reason: the conversion had been written out at every call site, and the third copy is
where an exponent quietly gets added to a schema that deliberately does not carry one.

   thing a reader has today is the preview - which answers with partitions, transfers and tax in order to say what a
   member owes, and shows *identifiers* rather than names, because its table is about the plan rather than about
   people. A member asking "what do I owe" is shown a settlement they did not ask about.

## Consequences

- **Serving the operation removed it from the gap list rather than adding an entry.** `ContractCoverageTest` counted
  seventeen gaps when ADR 0038 landed and reports sixteen now, because this slice had to delete the entry in the same
  change - the stale-entry direction of that check is what keeps the list describing the repository, and it is why
  the count in that test's documentation moved with the code.
- **The two reads cannot drift, and that is asserted end to end.** `agreesWithThePreviewOverTheSameOpenObligations`
  asks both endpoints over the same seeded rows and asserts that the balances body is literally contained in the
  preview's, which fails against any second composition path. It compares bodies rather than parsed values because
  `jackson-databind` is not on the API module's compile classpath - a test that needs to compare two strings does not
  get to add a dependency - and `startsWith` plus a `hasSize` assertion stop that from passing when both are empty.
- **Two design defects were made visible and left visible.** The clearing account is indistinguishable from a member
  in this response, and a client cannot name a position whose member the catalogue lacks. Both are recorded - in the
  operation's description, here, and in the screen's footnote - rather than fixed by inventing a schema field for one
  endpoint's convenience. The fix, when a consumer needs it, is a field on `NetPosition` saying what kind of account
  the holder is, which is a contract change for both endpoints and therefore a decision of its own.
- **A member list that fails to load degrades to identifiers, deliberately.** The screen does not report the member
  query's failure separately: the rows then show identifiers, and the footnote already explains that case, so a second
  notice would say the same thing twice.
- **A screen the route tree promised now exists.** The balances screen is reachable from the group's navigation, and
  the browser suite asserts the navigation as well as the table - a route nothing links to is a page nobody has.

## Alternatives considered - and why rejected

**Remove the operation instead of building it, as ADR 0033 removed three.** The promise was redundant in the sense
that mattered - the preview already returns those positions - and removing it would have been cheaper than serving
it. Rejected because the plan's route tree owes a balances *screen* regardless, and the preview is the wrong endpoint
for it: to answer "what do I owe", it computes partitions, transfers and tax that the reader never sees, and its
positions table shows identifiers because it is a table about the plan. The screen exists either way; the question was
which endpoint feeds it.

**A positions-only entry point in `libs/planning`.** It would avoid computing transfers and tax for a read that wants
neither, and the numbers would be the same - positions do not depend on the tax rule, only the transfers do. Rejected
because the engine's entry point takes the rule and resolves it eagerly, so this is not skipping a step: it is a
second way to compose a plan, next to one that exists precisely so that a multicurrency group has one. The
performance argument is real and unmeasured, and this repository does not change a composition path for one it has not
measured.

**Add `asOf` to this operation so one endpoint could answer both questions.** It would collapse the two endpoints into
one and remove the question entirely. Rejected because the contract's balances operation has no such parameter and its
description is "current" - and because the preview's `asOf` exists for the what-if workflow (a member asking what a
cycle would have cost), which is a different question with a different screen.

**Have the server resolve member names and return them with the positions.** It would let the client render a row
without a second query, and it is what the members read's `Member` schema exists for. Rejected because it would change
the `NetPosition` schema for both endpoints - the preview's positions would grow a name field that its table does not
show - and because names are the group's data rather than the ledger's, so a read of positions would start depending
on the member catalogue. The client already has both.

**Sort the rows on the server by member name.** Rejected for the reason the service gives in its own documentation: the
plan's order is currency-grouped and deterministic, a server-side sort would need the member catalogue to be right,
and a name that is really an identifier sorts by coincidence. A screen that wants alphabetical order sorts its own
rows, and this one does not want it.

2. **The engine needs a tax rule to run at all.** `SettlementPlanner.plan` resolves the rule eagerly, per partition,
   to build each `NettingRequest` - so a positions-only path is not a matter of skipping a step this read does not
   want. It would mean changing the netting engine's entry point or adding a second composition to `libs/planning`,
   and this repository has one multicurrency engine on purpose (ADR 0012).
3. **ADR 0036 had already made this argument once.** Making the obligations list and the netting preview share a
   single definition of "open" was the point of that slice, in its words: "a group that previews one thing and reads
   another has been told two things". A second composition path for balances is that failure with a different pair of
   endpoints.
