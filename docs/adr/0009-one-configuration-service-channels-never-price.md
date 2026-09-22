# 0009 — One configuration service prices and validates; channels never price

**Status:** accepted, 2026-09-16

## Context

Before this arc the shop, the cart, the app and the billing run each carried
some of the pricing arithmetic. Each new shape (a per-seat licence, a
surcharge on a fourth screen, a launch pass valid to month end) had to be
taught to every face, and the bill could disagree with the offer page. The
CSR desk, the MCP tools and the ontology were about to become three more
faces.

## Decision

- **TMF760 Product Configuration on the catalog** (`/tmf-api/productConfigurationManagement/v5`)
  is the one oracle. `queryProductConfiguration` returns the configuration
  space (choices, ranges, defaults, stock per variant, relationships, prices
  with conditions and windows). `checkProductConfiguration` returns, for a
  pick set and a quantity, accepted or rejected with reasons in words, the
  price lines, the totals, the early-termination note and required actions.
- **Channels ask, never compute.** The shop, app, care desk, business
  console, MCP tools and ontology render what the oracle returns. Add to
  cart is blocked on a refusal; "requires" is shown as an action.
- **Billing prices every product through the same check** before it rates
  the month, and charges the declining early-termination price on cease.
- The answer is in the TMF760 v5 shape beside the house view (ADR 0001).
- The same rule holds for eligibility (policy) and actions (ontology): no
  business logic in a front end.

## Consequences

- Costs: one more round trip on every product page and every cart change;
  the catalog is on the critical path of the bill run; a channel cannot show
  a price the oracle has not computed (no client-side "about N/month").
- Buys: one place to add a pricing shape; bot price, shop price and bill
  price are proven equal rather than maintained equal; an agent gets the
  same verdict a human sees.

## Enforced by

`configurable_channels_test.js` (#131): the same pick set through every
face and the bill; review under `docs/engineering-conventions.md` §4
("channels ask, never compute").

## Related

`docs/configurable-products.md`, `docs/configurable-products-plan.md`,
`docs/tmf760-configurator-plan.md`, ADR 0008.
