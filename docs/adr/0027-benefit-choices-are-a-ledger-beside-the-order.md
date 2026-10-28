# 0027 — Benefit Choices are a ledger beside the order, not a second order system

Status: accepted, 2026-10-27 — the decision record of the Benefit Choices arc
(#352, tickets #353–#362). The arc document is [benefit-choices](../benefit-choices.md).

## What forced it

A plan that lets the household pick partner services ("Picks", T-Mix style)
looks like a product feature and is really a small accounting problem: a unit
that is granted by one product, spent on another, can be swapped, may be held,
and turns into money only on the operator's side of the partner contract. The
partner paper this came from proposed six services for it. Every one of them
would have owned a slice of a truth that ordering, inventory and the catalog
already own.

## The decision

**One new component, `benefit-allowance`, counts Choices. Everything else stays
where it already lives.** Eight decisions follow from that, and each was put to
the business owner in the design interview before a line was written:

| # | Decision | Why |
|---|---|---|
| 1 | **The pool belongs to the household**, not to a line or a plan | Lines come and go; what the household was promised is the sum of its plans. A pick on one line spends from the same pool as a pick on another. |
| 2 | **Grants are per period; a hold is a lock on one allocation**, not a different kind of Choice | A subsidised benefit that must stay a while is the same Choice with a date on it. Release before the date is refused with the date. |
| 3 | **A plan's grant is a catalog term** (`choicesGranted`); a benefit's cost, partner, wholesale cost and hold are catalog terms too | Product managers already govern the catalog, and launch governance already guards it. No second authoring surface. |
| 4 | **Every change is a TMF622 product order**: a pick is `add`, a release is `delete`, a swap is one order with both | Orders already have states, receipts, channels, cancellations and partner fulfilment. A swap that is one order cannot half-happen. |
| 5 | **Inventory is the truth for what is sold; the ledger is the truth for units** | TMF637 says which benefits a household has; benefit-allowance says how many Choices they cost and how many are left. Neither copies the other. |
| 6 | **A downgrade below what is in use is refused**, naming the benefits and how many to release | No "needs a choice" order state that waits on a customer; the remedy is one release, then the change. |
| 7 | **Partner activation is asynchronous and asks before it retries** | A partner that answered late must not end up with two activations. The order completes only when the partner confirms. |
| 8 | **Points and a marketplace are out** | A Choice is a count, never money and never a currency; the only money is what the operator owes the partner, kept in the finance ledger. |

Underneath, the ledger's own rules: every hold locks the household's pool row
first, so two picks racing for the last Choice cannot both win; the order item
is the duplicate key, so a repeated request is the same allocation, never a
second one; a hold longer than the law of the tenant's country allows is cut to
it — law as data, 24 months for the EU, 0 for Norway until the bindingstid
question is answered.

## What it costs

- The pool is per household (the customer party), not per billing account.
  Most households have one; a household with two accounts has one pool.
- Ordering calls the ledger at placement, completion and cancellation, so the
  ledger is on the order path for any order that carries a benefit or a
  granting plan. When it is down those orders are refused, not taken unchecked.
- Choices are counted where they are granted; there is no transfer between
  households and no expiry.

## What reopens it

A tenant that needs Choices **per billing account** for business customers, a
**merchant-of-record** split where the operator sells the benefit itself, or a
request to **buy or earn** Choices (the line between a Choice and a point) —
each is a new decision, not an extension of this one.
