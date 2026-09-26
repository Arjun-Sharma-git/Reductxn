# Spec - Booking

**Owner:** ledger reviewer · **Module:** `libs/booking/api` · **Related:** ADR 0004, 0012, 0013; RULEs
L1, L2, L3, L6, L7, L9, B6, B19, B22

## 1. What booking is

A commitment is not a money movement, but recording one produces movements: the entries that are the
product's record of it. Booking is the decision of which entries a commitment produces, and it is a pure
function of the commitment, the amounts and the chain heads it appends to.

It reads no clock, no database and no rate. The instant comes from the caller, the amounts arrive already
converted (B19), and the chain heads are read by somebody else and passed in.

## 2. The two shapes

| Shape | Entries | Debt | Credit |
|---|---|---|---|
| Single-currency | one, in the obligation's currency | creditor (the claim) | debtor (the liability) |
| Cross-currency | two, linked, one per book | creditor then clearing account | debtor then clearing account |

A single-currency commitment becomes one entry: the debtor is credited and the creditor debited, with the
debtor's net debit negative because they hold the obligation.

A cross-currency commitment becomes two entries through the `fx_clearing` account (ADR 0012 point 3):

```
debt book:      credit debtor        owed       debit  fx_clearing   owed
settlement book: credit fx_clearing  settlement debit  creditor      settlement
```

They are in different currencies, each balances on its own (L1, L6), and nothing reconciles them. The two
amounts differ by the residual by design: the debtor's side is exact because that is what they agreed to
pay, the creditor's side is the converted amount because that is what they agreed to receive.

## 3. The residual

The residual is **fractional** - `residualScaled / 10^residualScale`, always inside half a minor unit of the
target currency (ADR 0013 point 3) - and a posting must be a strictly positive integer (L2). So the residual
is not a small amount of money: it is an amount with no representation in the unit the ledger records in.

Booking therefore does not post it and does not round it. It carries it out on the result, attached to the
quote it came from, in the arithmetic form it actually has. L7's "never dropped" is satisfied by that; how
it is eventually accounted for - accumulated, allocated pro rata, or funded - is the open question in ADR
0013, and answering it inside a booking would hide a policy decision in a constructor.

**The ledger cannot check value conservation across a pair.** The clearing account's two positions are in
different currencies, so comparing them requires a rate, and RULE B19 keeps rates out of every module but
`libs/fx`. What the ledger can state, and does, is that the clearing account holds the owed amount in the
debt currency and owes the converted amount in the settlement currency - which is exactly what L8 measures
per currency at the end of a cycle.

## 4. Chain heads

Entries are chained per `(group_id, currency)` (L4), so a booking needs the head of every chain it appends
to - one for a single-currency commitment, two for a cross-currency one. `ChainHead` is a port for that:
booking asks per currency and the caller answers.

**The caller owns ordering.** Two appends to one chain that read the same head produce two entries claiming
the same predecessor, and the damage is invisible in both entries. An adapter implementing `ChainHead` must
serialise appends per `(group, currency)` before reading the head.

## 5. Idempotence

Entry ids and link ids are derived from the commitment's identity, not generated. A re-booked commitment -
a retried request, a replayed batch - produces the same ids and the same content, so it collides instead of
appending a second entry for a commitment nobody made twice. The ledger is append-only (L3) and has no way
to take an entry back, which makes this the only safe direction to fail in.

The submitting operation's idempotency key is stored on the entry as a second, weaker signal. It depends on
the client behaving; the derived id does not.

## 6. Refusals

Booking refuses rather than producing something that looks valid:

- a zero amount, which cannot be posted (L2) and would leave one side of a linked pair missing;
- a clearing account named as one of the parties, which would post a member against themselves on one side
  and balance perfectly while moving nothing;
- a pair in one currency, which is a single-currency commitment carrying a quote.

A quote is refused earlier, by `libs/fx`: a conversion that rounds to zero minor units never becomes a
quote at all.

## 7. Open questions

- How the residual is eventually accounted for. ADR 0013's open question, and the reason it is reported
  rather than posted.
- Whether a booking should be able to append to more than two chains. A commitment that spans three
  currencies is not a shape the product has.
