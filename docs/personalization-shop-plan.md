# Personalization — the shop that knows you — plan

*2026-08-05. The growth arc delivered the marketing half of the July
vision (insight, segments, GA4, social, guardrails — suites #35–40)
and the guest experience surface (banner, hero re-sort, pin, resume
rail, rules-as-data, copilot-authorable). The recon maps what the
vision still owes: the shop never uses the customer's OWN BSS DATA to
shape an offer, and the guest grid uses one hero category where a full
interest profile sits unused. Five named gaps; this arc closes four
(pricing-on-the-grid stays out deliberately — price is the bill's
truth, not a personalization lever).*

## Research findings (what exists vs what's missing)

- Guest experiences today: banner + heroCategory re-sort + one pinned
  offering + resume rail, driven by policy `personalization` rules
  over `{interests, topInterest, utmSource, channel, segments,
  knownCustomer}`. MISSING: a `visits` frequency var (rules cannot
  greet a returning visitor) and any use of the full `interests[]`
  array on the grid (only the single hero floats).
- Known customers today: the for-you rail (TMF680 + churn flag +
  governed caption) on shop and app. MISSING: usage. The storefront
  reads meters ONLY on the Services page — the shop never says "you
  are at 92% of your 10 GB" even though the self-scoped
  `queryUsageConsumption` API and the meter arithmetic both exist.
- No catalog-declared upgrade ladder exists — but the usage service's
  `usageAllowance` rows (offering → spec → allowance) ARE the ladder,
  as data nobody assembled: the next rung up for a bucket is the
  active offering with the next-larger allowance for the same spec.

## The design

**P1 — the usage-aware upsell (known customers).** The for-you
payload grows an `upsell` block, computed in intelligence from data
the fleet already holds: the customer's meters
(`bss.usageMeters`), joined to the allowance ladder
(usage `usageAllowance` read via a new BssApiClient method). When any
bucket is ≥ 80% used, the block carries
`{bucketName, usedPct, currentAllowance, suggestedOffering{id,name},
suggestedAllowance}` — the suggestion being the CHEAPEST active
offering with a larger allowance for the same usage spec, never an
invented "deal". The storefront's Shop page renders it as an upsell
card ("You've used 9.2 of your 10 GB — Mobile 50 GB gives you room")
linking to the existing plan-change flow. Honest rules: no meter or
no fuller plan → no block; the caption stays grounded; nothing
prices differently.

**P2 — the guest who returns (anonymous).** Two small pieces:
(1) insight's experience context grows `visits` (distinct days with
events for this visitor) so operators can author returning-visitor
rules as data; (2) the Shop grid ranks singles by the FULL
`interests[]` array (weighted by interest order, stable within
ties) instead of floating one hero category — the profile the
visitor already consented to, finally used.

**Suite #86 `shop_personalization_test.js`**: a fresh customer with a
nearly-drained meter sees the upsell card naming the right bigger
plan (and a customer under 80% sees none); the suggested offering is
genuinely the next rung (allowance-verified); a returning guest
(events on two distinct days) matches a `visits >= 2` rule while a
first-time guest does not; a guest with two interests sees BOTH
categories ranked above the rest, in interest order; consent walls
hold (no consent → no interests → untouched grid). Regressions
serial: personalization, for_you.

## Shipped

*(recorded as phases land)*
