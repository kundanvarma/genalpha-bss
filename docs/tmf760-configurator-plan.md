# TMF760 Product Configuration — the configurator the storefront already implies — plan

*2026-08-03. The standards sweep closed with a question: which flows does
the fleet run that still have no standard face? One answer was hiding in
the browser: the storefront's Offering page IS a product configurator —
choice groups, pick-N-of-M, color/storage pickers, price-follows-the-pick
— but every rule lives in client JavaScript. TMF760 is the standard face
for exactly that, and putting it on the server turns a UI behavior into a
capability ANY channel can call: the storefront, the agent channel, a
partner. Version note: TMF760 has no v4 — it was first published at
v5.0.0, as the API of the Product Configurator ODA component (TMFC027).
The spec's own framing fits the house design: the configurator "uses
product catalog data, policy data, and product inventory data".*

## Research findings

- **The configuration space already lives in one service.** Catalog's
  own entities carry everything: `bundledProductOffering` JSON holds
  fixed members AND choice groups (two cardinality homes: group-level
  `numberRelOfferLowerLimit/Upper` for choices, nested
  `bundledProductOfferingOption` for fixed members — and consumers
  detect a choice by "has an `options` array", not by `@type`);
  `productSpecCharacteristic` JSON carries the pickers (GOTCHA: absent
  `configurable` means TRUE — only display-only facts set `false`);
  `prodSpecCharValueUse` on a price conditions it on a characteristic
  value (the Titanium case). No cross-service reads needed to DESCRIBE
  a configuration. TMF760 hosts on product-catalog.
- **The validation to port is split across two codebases and neither is
  complete.** The storefront checks only the LOWER bound in code (the
  upper bound is enforced by radio/checkbox UI physics); ordering's
  `validateBundleComposition` checks both bounds but only at submit
  time, with the good error wording ("'<group>' requires between L and
  U selection(s), but N were made"). Nobody validates characteristic
  VALUES anywhere — a nonsense color rides through today. The server
  check does all three: both bounds, allowed values, same wording as
  ordering so a configure-time rejection reads like the order-time 400.
- **Pricing semantics come from money.js** and must port exactly:
  an unconditioned price always applies; a conditioned price applies
  only when EVERY condition matches picks by characteristic NAME with
  exact string equality on the value; recurring and oneTime sum
  separately. "The price follows the configuration."
- **Policy is two calls with two postures.** `/price/indicative` is
  anonymous by design (guests may ask what the public deals do to a
  basket). `/evaluate` — the block rules — is machine-only
  (`policy:evaluate`); catalog holds no machine identity today, so the
  arc mints `bss-catalog` (file + live, default-roles stripped — the
  drilled procedure). Fail-open on both: a policy outage must not
  block browsing. Deny comes back as `decision:"deny"` in a 200 body.
- **The agent channel proves the point of a server-side check.** ACP
  checkout items are flat `{id, quantity}` — no picks, no nested
  order items. A bundle bought through an agent would fail ordering's
  cardinality check today. The TMF760 endpoints are the missing
  capability; the P2 MCP tool (`configure_product`) makes the agent a
  configurator client.
- **The test offering exists and is exact.** GenAlpha Family Max: 2
  fixed members + three choice groups — family lines 1..2 of 3 (default
  Mobile 50 GB), phone 1..1 of 3 (default iPhone 17), streaming extras
  0..2 of 2 — base 49.00 EUR/month, and the Samsung Galaxy S26 carries
  a color value "Titanium Edition" whose conditioned price adds
  +2.00 EUR/month only when that exact color is picked.

## The design

Both TMF760 task resources on product-catalog, new base path
`/tmf-api/productConfigurationManagement/v5`, stateless instant-sync
(the POST computes and answers; no task table, no migration):

- **`queryProductConfiguration`**: given `productOffering.id`, returns
  the computed configuration space — fixed members, normalized choice
  groups (name, lower, upper, default, options), each option's
  configurable characteristics (absent flag = true) and prices WITH
  their conditions visible, bundle-level prices and terms. This is the
  storefront's Offering page as data.
- **`checkProductConfiguration`**: given picks (`selectedOption` ids +
  `configurationCharacteristic` name/value pairs), returns
  approved/rejected per item with messages (both cardinality bounds,
  characteristic values against the owning spec, policy block rules
  under the catalog's own machine identity), and for approvable
  configurations the priced result: recurring + one-time totals, the
  price lines that apply (conditioned lines only on exact match), and
  the indicative policy adjustments.
- **Anonymous, like the catalog it reads**: explicit permitAll on the
  two POST paths (configuring IS browsing); tenant from verified issuer
  or the gateway's hostname header, so nova's guests configure nova's
  catalog and never see Family Max.
- Gateway route `productConfigurationManagement` → catalog (host-built
  jar: `mvn package` the gateway BEFORE the image build — recorded
  twice already, respected this time).

## The phases

### P1 — the engine and the standard face (build first)

Controller + ConfiguratorService + PolicyClient (machine-token
interceptor pattern from ordering), `bss-catalog` machine identity with
`policy:evaluate` (file + live, defaults stripped), gateway route,
security matchers. **Suite #77 `configurator_test.js`** against Family
Max: query shape (the five groups with exact limits and defaults;
Titanium only under Samsung; absent-`configurable` chars surface as
pickers); a valid pick set approves with exact price math (base 49.00 +
option prices; Samsung + "Titanium Edition" adds the 2.00 premium, any
other color excludes it); 0 lines rejects on the lower bound; 3 lines
rejects on the UPPER bound (the hole no UI catches); a nonsense color
rejects with the offending value named; anonymous access works; the
nova wall holds. Regressions serial after.

### P2 — the agent becomes a configurator client (later)

`configure_product` MCP tool (anonApi against the check endpoint), and
the ACP checkout gap: carry picks as nested productOrderItem children
so a configured bundle can actually be ORDERED through the agent
channel. Channel adoption: storefront swaps client-side validation for
the server check (or keeps both — instant UI + authoritative server).

## Shipped

*(recorded as phases land)*
