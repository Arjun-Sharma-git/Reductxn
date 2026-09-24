# ADR 0006 - Tax law as configuration

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P10; spec `docs/specs/tax-rules.md`

## Context

CBDC transaction tax is not one thing. At minimum it appears as a flat charge per transfer, an ad
valorem percentage of the amount, and a threshold or progressive schedule. These imply different
optimisation objectives. Tax law also changes far faster than application code.

## Decision

A `TaxRule` is a data record selected by `(jurisdiction, currency, effective_from, effective_to)`.
Three shapes ship initially (flat, ad valorem, threshold) behind one `TaxRuleEngine` interface.
A jurisdiction's rule is inserted, never coded. Rule changes are effective-dated, so historical
settlements recompute identically.

## Consequences

- **Easier:** new countries are data, not releases; historical settlements stay reproducible;
  the optimiser objective can be derived from the active rule.
- **Harder:** the rule engine must be designed before the first real jurisdiction is known, so the
  interface needs a degree of generality it may not yet be earning. Rules require governance -
  who may insert one, and how it is validated against the statute.

## Alternatives considered

- **Hard-coded per-country branches** - rejected: unfixable turnover and untestable combinatorics.
- **Compute tax outside reductxn** - rejected: then tax cannot inform the netting objective, which
  is the entire product thesis.

## Open question

Whether reductxn is a withholding agent or merely computes and reports. Default posture: compute
and report, with the rail remitting. This requires legal review - tracked as an open item.
