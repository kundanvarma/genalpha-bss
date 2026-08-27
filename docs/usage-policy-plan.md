# Usage policy — pools, caps, passes, and the €50 wall — plan

*2026-08-27. The usage service meters, boosts, rolls over, and gifts
peer-to-peer — but a household cannot share one pool, nothing refills
automatically, no spend cap exists anywhere, and roaming is a single
EU add-on rather than a product family. Two of those gaps are not
features at all in the EEA: the roaming financial limit with cut-off
and free content-service barring are statutory. And one tempting
feature — app-specific zero-rating — is unlawful here and explicitly
skipped. This arc builds the policy layer on the meter primitives.*

## Research findings

- **Statutory MUSTs.** EU Roaming Regulation 2022/612 (EEA-binding,
  so it reaches Norwegian operators): a **default financial limit on
  data roaming ≈ €50/month ex VAT**, warning at 80 %, **cut-off at
  100 %** unless the customer explicitly continues; customer may
  raise/lower/opt out; applies to non-EEA roaming too. Roam-like-home
  itself: domestic pricing in the EEA, fair-use (rolling 4-month
  predominance), and the pricing "welcome SMS" on country entry.
  Norwegian ekomforskriften: **free barring of jointly billed content
  services**, spending limits whose lowest selectable value is
  ≤ 250 kr/month with block-on-breach + notification, and mandatory
  adult-content barring for subscriptions registered to minors.
- **Zero-rating is dead in the EEA**: the 2021 CJEU rulings and
  BEREC's updated Open Internet guidelines prohibit price
  differentiation between applications. What remains lawful is
  **application-agnostic** free data — bonus GB, free hours/weekends,
  event-granted allowances — because all traffic is treated equally.
  Design consequence: no per-app classification enters the rating
  path, ever; free-data promos are campaign-granted bonus meters.
- **Auto top-up is a consumer-protection minefield** when silent:
  documented industry cases of auto-enrollment and surprise boost
  line items. The safe (and here, only) pattern: explicit opt-in
  with recorded consent, per-cycle count and spend caps,
  notification per purchase, one-tap disable.
- **Shared pools have a known engineering trap**: concurrent quota
  grants over-allocating the pool (the classic online-charging race).
  Our meters are transactional and single-region — small
  reserve-then-commit grants per usage record suffice, with
  denormalized per-member counters for real-time display.
- **TMF fit**: TMF654 prepay balance (buckets/top-ups — auto top-up
  is a programmatic `topupBalance`), TMF677 usage consumption (the
  natural family-app read: pool + per-member buckets), TMF620 for
  pools/boosts/passes as offerings and zones as characteristic sets.
  Spend caps have no dedicated TMF API — exposed as usage-service
  policy resources, same approach as the wholesale rate cards.
- **Repo recon**: allowance meters + boosts + rollover + gifting and
  the usage-threshold webhook already exist; boosts are purchasable
  offerings; the events→CDP wiring rule gives every new threshold
  event a martech consumer for free.

## Design

### Household pool (usage)

`allowance_pool` — owner = the household/payer account; member
subscriptions attach with optional per-member `softLimitGB` /
`hardLimitGB`. Consumption decrements the pool through small
transactional reservations (reserve-then-commit per usage record —
never grant the whole remainder to one session) and a denormalized
per-member counter feeds TMF677 reads. Soft limit → notify member +
owner; hard limit → member falls back to own allowance or blocks.
Pool-level 80/100 % events. Owner manages members and caps in
selfcare (existing household roles gate who may).

### One monetary-meter primitive, three legal faces (usage + billing)

A `spend_meter` accumulates rated charges per subscription per cycle,
filtered by charge class, with a threshold policy
(`notifyAt`, `blockAt`, `action`). Three instances:

1. **Subscription spend cap** — all usage-rated + boost charges;
   tenant-default off, customer-settable.
2. **Content-service cap + barring** — jointly billed third-party
   content: barring is **free and always available**, lowest
   selectable limit ≤ 250 kr, block-on-breach with notification,
   mandatory barring categories for minors (defaults from the
   guardian/child machinery). Statutory pack, not tenant-overridable
   below the floor.
3. **Roaming financial limit** — default ≈ €50 ex VAT equivalent per
   cycle, warning at 80 %, hard cut-off at 100 % with an
   explicit-continue action (the continue is itself an audited
   event); per-subscription raise/lower/opt-out.

All three are the same primitive + policy rows; the OCS-facing
webhook grows a `spendThreshold` callback next to the existing usage
one.

### Auto top-up (usage + ordering)

`auto_topup_policy` per subscription: `{enabled, boostOfferingId,
trigger (depletion | thresholdPct), maxBoostsPerCycle,
maxSpendPerCycle, consentAt}`. Consumes the existing threshold-breach
event; places the boost order **idempotently** (one per breach window
per cycle), notifies on every purchase, stops at cap with a
"cap reached — buy manually or raise the cap" notice. Never
default-on; consent timestamp is part of the record.

### Roaming as products (catalog + usage)

Zones (`EEA-RLAH`, `World-1`, …) as catalog characteristic sets.
RLAH = home meters apply, with a phase-2 fair-use monitor (4-month
predominance counter, surcharge line items at the capped rates).
**Travel passes** = time-boxed, zone-filtered boosts — existing boost
machinery + validity window + zone filter on usage records.
**Entry-triggered offers**: first usage record from a new zone in N
days emits an event; the mandatory pricing information rides the
notification, the pass upsell rides the campaign engine behind it.

### Free-data promos, not zero-rating

Campaign-granted bonus meters (birthday GB, event weekends) that
outrank the paid meter during their window — application-agnostic by
construction. Explicit non-goal: per-app rating filters in the EEA;
if a non-EEA tenant ever needs one, it is a rating-filter seam, not
core.

### Events

`PoolThresholdEvent`, `MemberCapReachedEvent`, `SpendCapWarning/
Breach`, `RoamingLimitWarning/CutOff/ContinueElected`,
`AutoTopupPurchased/CapReached`, `ZoneEnteredEvent` — all wired to
insight traits, campaign topics, and bridge mappings per the
standing rule.

### Proof

`usage_policy_test.js`: pool shared across two members with a
per-member hard cap (concurrent-usage race asserted at the
reservation layer); auto top-up fires once at depletion, respects
the cycle cap, and never fires when disabled; content barring blocks
and notifies at the statutory floor; roaming cut-off at the default
limit with explicit-continue restoring service; a travel pass rates
zone traffic inside its window and not outside; a campaign-granted
free-data meter consumes before the paid meter.

## Phases

- **P1** — spend-meter primitive + content-service cap/barring +
  roaming financial limit (the statutory pair first).
- **P2** — household pool + member caps + selfcare faces.
- **P3** — auto top-up + travel passes + zone-entry offers.
- **P4** — RLAH fair-use monitor, free-data promo meters, console
  policy views.

Docs upkeep on ship: manual + architecture + README per house rule.
