# 0008 — Prices are positive, discounts are rules, only named pricing algorithms

**Status:** accepted, 2026-09-16 (configurable-products arc; pricing rules as policy earlier, 2026-07; recorded as an ADR 2026-09-22)

## Context

Attribute-based pricing invites a formula field: "price = base + 10 × (profiles − 2)".
A free-form expression is a second programming language inside the catalog —
unreadable to a product owner, untestable by the suites, and impossible for
the shop, the cart, the bill and the OCS to evaluate identically. The
alternative is a small, closed set of shapes the standard already names.

## Decision

- A catalog price (`productOfferingPrice`) is a **positive amount** for
  something sold: recurring, one-time, usage, or a `penalty` for early
  termination. The catalog never stores a negative price.
- A **reduction is a rule**, not a price: a promotion (TMF671, redeemed by
  the owner, a discount line on the bill) or a pricing rule in policy
  (TMF723 JSON-logic, where the adjustment is signed: negative = discount,
  positive = surcharge). Rules are data; no redeploy.
- Attribute pricing uses **only named algorithms** on `pricingLogicAlgorithm`:
  `perUnitAbove` (base plus unit price times units above a threshold) and
  `stepped` (a tier table over a characteristic or the quantity). Both are
  documented in `docs/configurable-products.md`. There are no free-form
  formulas anywhere in the system.
- A price that counts a characteristic forces that characteristic to exist
  on the specification as a range; the catalog refuses an offering whose
  price conditions on a choice its spec does not declare.
- Usage tiers are a table (`overageTier`), pushed to the OCS at activation so
  real-time and bill-time agree.

## Consequences

- Costs: a pricing idea that fits neither algorithm needs a third named
  algorithm, implemented in the catalog and in billing, with a suite —
  never a formula typed into a form; the copilot must propose tiers, not
  expressions.
- Buys: every channel and the bill compute the same number; a product owner
  can read every price; the CTK still recognises the offering.

## Enforced by

`configurable_channels_test.js` (#131) prices the same pick set in shop,
app, care desk, MCP and billing; `pricing_test.js` and `policy_test.js`
for rules; catalog write-time validation; review of any new `plaSpecId`.

## Related

`docs/configurable-products.md`, `docs/product-rules.md`,
`docs/product-modeling.md`, `docs/engineering-conventions.md` §8.
