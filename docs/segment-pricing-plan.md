# Segment & contract price lists — how they should work in genalpha

A design for the one CPQ pricing piece we deliberately deferred: **who pays
what**, beyond a flat volume tier. Grounded in how the leaders model it and in
what this BSS already has — because the honest answer is *"segment pricing needs
a source of truth for 'segment', and we already have one."*

---

## The question

Volume tiers (shipped) answer "buy more, pay less." They don't answer:
- **Segment pricing** — an *enterprise* customer sees a different price list than
  an *SMB*; a *public-sector* account gets government rates.
- **Contract pricing** — an account that *negotiated and signed* gets *their*
  pinned prices on every future quote, not list.

Both are "the price depends on *who is buying*," which volume tiers can't express.

## How the leaders do it

- **Salesforce CPQ — Price Books.** A product has one list price, and additional
  **price books** hold alternate prices per segment/region/deal type; the quote
  is bound to a price book and reads prices from it. ([MindMajix — Price Book in Salesforce CPQ](https://mindmajix.com/price-book-in-salesforce-cpq))
- **Salesforce Industries / Vlocity (the telco standard) — Price Lists +
  Pricing Rules + Calculation Matrices.** Vlocity adds **price lists** on top of
  price books for more dynamic pricing; **pricing rules** modify a line's price
  *by the context of the transaction* (adjust, override, or swap in a different
  price list); and a **calculation matrix** is a lookup table (input dimensions →
  output price/discount) for complex segment/volume/attribute pricing.
  ([Perficient — Vlocity CPQ](https://blogs.perficient.com/2020/10/29/vlocity-cpq-in-a-nutshell/) ·
  [taranggoel — Vlocity vs SF CPQ](https://taranggoel.com/differences-between-vlocity-and-salesforce-cpq/))

The shared shape: **a named price list, selected by the buyer's context, whose
entries override the catalog list price**, with a resolution order when several
apply.

## What genalpha already has (the honest advantage)

We don't need to invent "segment" — three of the four pieces exist:

1. **List price** — TMF620 `productOfferingPrice`, already read at quote build (C1).
2. **The `segment` dimension** — the `quote_pricing_rule` table already carries a
   nullable `segment` column (added with the volume-tier feature, unused today).
3. **A real source of truth for a customer's segment — the CDP.** `insight`
   already computes **audiences/segments** from `party_trait` (loyaltyTier,
   region, spend, tenure…). A customer's segment is *already* a first-class,
   governed concept — the same one martech targets on. **Segment pricing should
   ride the CDP**, not a new hand-maintained field. That's the genalpha-native
   move: one segment definition serves marketing *and* pricing.
4. **A contract per account** — since C2b, accepting a quote creates a **TMF651
   agreement**. That agreement is the natural home for *contract* (negotiated)
   prices an account keeps.

## The design

Two layers over the catalog list price, resolved most-specific-first.

### Layer 1 — Segment price lists  *(floor: CDP segments, the pricing rules table)*
- A **price list** = a named set of entries `{offeringName → discount% or fixed
  price}`, tagged with a **segment** (e.g. `enterprise`, `public-sector`).
- At quote build, resolve the buyer's segment from the CDP: call
  `insight` for the opportunity's account (`partyId`) → its segment
  trait / audience membership. Apply the matching price list's entries.
- Implementation: extend the existing `quote_pricing_rule` (its `segment`
  column) — a rule with a segment applies only when the buyer is in that
  segment; a rule with no segment is the volume tier we already ship. One table,
  two behaviours. The CDP lookup reuses the `insight` client and `insight:read`
  grant we added for lead scoring.

### Layer 2 — Contract pricing  *(floor: the TMF651 agreement from C2b)*
- When a deal is won, the agreement can record **negotiated prices** for that
  account (an `agreementItem` price term, or a `contract_price` table keyed by
  `agreement_id` + offering).
- Future quotes for that `partyId` look up an active agreement and use its
  pinned prices — the account keeps what it signed.

### Resolution order (most specific wins)
```
contract price (this account's signed agreement)
  ▷ segment price list (their CDP segment)
    ▷ volume tier (quantity)          ← shipped
      ▷ catalog list price (TMF620)   ← shipped
```
Each layer only overrides when it matches; otherwise fall through. The quote
line records *which* layer priced it (like `volumeDiscountPercent` today) so the
quote is explainable — and, per the AI-age rule, an agent can call a
`/quote/priceExplain` decision endpoint to see why a line costs what it does.

## TMF mapping
| Piece | Where |
|---|---|
| List price | TMF620 `productOfferingPrice` |
| Segment / price-list entries | `quote_pricing_rule` (`segment` col) + CDP segment via `insight` |
| Contract prices | TMF651 agreement (`agreementItem` price terms) |
| Segment source of truth | `insight` audiences / `party_trait` (the CDP) |

## Why not now (the honest gap)
Segment pricing is a **small build** *once one decision is made*: **is a
customer's segment the CDP audience/trait, or something else?** The recommendation
above says CDP — but that's a product call about where pricing segments live, and
it should be deliberate, not guessed. Contract pricing additionally wants the
agreement to carry price terms (a modest schema add). Both are low-risk arcs;
neither is blocked by an external vendor or missing data — unlike DocuSign-grade
e-sign or ML win-likelihood.

## Suggested sequencing
**Segment price lists first** (reuses the pricing-rules table + the CDP client we
already have — highest value, lowest new surface), then **contract pricing** on
the agreement. Each proven with a suite, resolution order asserted end to end.

Sources: [MindMajix — Salesforce CPQ Price Book](https://mindmajix.com/price-book-in-salesforce-cpq) ·
[Perficient — Vlocity CPQ](https://blogs.perficient.com/2020/10/29/vlocity-cpq-in-a-nutshell/) ·
[taranggoel — Vlocity vs Salesforce CPQ pricing](https://taranggoel.com/differences-between-vlocity-and-salesforce-cpq/)
