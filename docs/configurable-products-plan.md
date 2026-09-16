# Configurable products, complete — one oracle for every channel — plan

*2026-09-16. Ivan created a TV product with three choices and surcharges; it
landed without choices and no channel could show it. The immediate defects
are fixed (copilot, shop, catalog integrity). This plan closes the class:
every product shape an operator sells, modelled with TM Forum's own fields,
resolved by one door, rendered identically by every channel, and proven by a
suite that walks the same product through all of them.*

## Research findings

**The standard already has the fields** (TMF620 v4/v5, verified against the
swagger in `tmforum-apis`):

- `ProductOfferingPrice.validFor` (a price has its own window),
  `unitOfMeasure {amount, units}` ("per seat", "per 5 GB"),
  `pricingLogicAlgorithm` (a reference to a named algorithm, body not
  standardised), `popRelationship`, `priceType` is an open string with
  conventional values `recurring`, `oneTime`, `usage`, `penalty`.
- `ProductSpecCharacteristicValue.valueFrom / valueTo` (integers),
  `rangeInterval` (`open | closed | closedBottom | closedTop`),
  `unitOfMeasure`, `valueType`, `isDefault`: numeric ranges are standard,
  and a price's `prodSpecCharValueUse` can name a range. v5 types the value
  (`IntegerCharacteristicValueSpecification` and friends).
- `ProductOfferingRelationship.relationshipType` (v4.1+: `requires`,
  `exchangableTo`, `optionalFor`) and `ProductSpecificationRelationship`
  (`dependency`, `exclusivity`, `substitution`, `migration`).
- TMF622 `productOrderItem.quantity`; TMF637 Product has no quantity of its
  own, so a quantity lives as a product characteristic; TMF678
  `appliedCustomerBillingRate` carries characteristics for unit price and
  quantity.
- TMF687 `ProductStock` (v4 only) is a level per `stockedProduct`, a
  `ProductRefOrValue` that may carry `productCharacteristic[]` — the
  variant (colour) lives there, at a `place`; `reserveProductStock` items
  carry `requestedProduct` the same way. There is no stock characteristic
  of its own.
- TMF760 v5 (ODA component TMFC027) exists for exactly this: "drives the
  configuration of new product offerings … for various user engagement
  channels … calculation of prices and discounts". Its shapes:
  `queryProductConfigurationItem[]` / `checkProductConfigurationItem[]` with
  `state: accepted | rejected` and `stateReason[{code, label}]`, and a
  `ProductConfiguration` carrying `quantity`, `configurationCharacteristic[]`
  (each value with `isSelectable`, `isSelected`, `valueFrom`, `valueTo`,
  `rangeInterval`), `configurationPrice[]` (`priceType`, `unitOfMeasure`,
  `price {dutyFreeAmount, taxIncludedAmount, taxRate}`), `configurationAction[]`,
  `configurationTerm[]` and child `productConfiguration[]`. We serve both
  task resources today in a house dialect (`approved`, `message[]`,
  `monthlyTotal`); phase 1 aligns the shape to v5 and keeps the house
  totals as an `@type`-tagged extension.
- `pricingLogicAlgorithm` carries `plaSpecId`; the algorithm body is not
  standardised anywhere, so a small documented set of named algorithms is
  the honest way to use it (Oracle does the same with a typed extension).
- TMF622 `quantity`, TMF663 cart `quantity`, TMF760 `quantity`, and no
  quantity on TMF637 Product: the standard expects the order and
  configuration layers to hold quantity; inventory holds it as a
  characteristic or as N products.
- Early termination: `priceType: penalty` on a price linked to the
  offering's `productOfferingTerm`; law: EECC 105(6) and ekomloven §4-6
  allow only the subsidised equipment's remaining share, declining
  monthly; Nkom: proportional to remaining months and the benefit granted.

**What commercial catalogs do** (Salesforce Industries, Zuora, Chargebee,
commercetools, Broadleaf, ServiceNow; sources in the research notes):

- Attribute-based pricing is a lookup table, never a free-form formula
  exposed to business users. Tiers are declared as rows: from, to, price,
  format (per unit or flat). Zuora's charge models are the vocabulary:
  flat, per unit, tiered, volume, overage, tiered with overage.
- Quantity: if each unit has its own identifier, lifecycle or assurance
  record (a number, a SIM, a router) it is N products; if units are
  fungible (seats, licences) it is one product with a quantity, priced by a
  tier table and prorated on change. No vendor states this rule; every
  design follows it.
- Stock is per sellable combination (SKU); the offering stays one and its
  availability is derived from its SKUs.
- Prices are effective-dated segments; editing a live price silently
  repricing existing subscribers is the classic mistake, and in Europe a
  detrimental change needs one month's notice and a free exit (EECC 105,
  ekomloven §4-6, which we already enforce in base migration).
- Compatibility: distinct relationship types for "requires" (auto-add or
  block) and "excludes"; catalog-level relationships, order-time
  enforcement, cart-time messaging.
- Early termination: the legal shape is the equipment subsidy declining
  monthly to zero (ekomloven §4-6, EECC 105(6)); a service fee for leaving
  is not chargeable on regulator-triggered exits. Ofcom fined EE £6.3M for
  charging undiscounted remaining fees.
- Usage tiers: anything that must stop a session or decrement a balance
  mid-session belongs in the charging system; the BSS owns the price
  definition and bill-time aggregation. Keeping one tier table, in the
  catalog, and pushing it to the OCS avoids two rating engines drifting.
- Multi-channel: one headless pricing and configuration service; channels
  render responses and never compute. Caches keyed by validity and channel.

## Design

### The rule

**Channels do not price or validate a configuration. They ask.** TMF760 on
the catalog is the one oracle: given an offering and picks it returns the
choices with allowed values and defaults, availability per option, the
price lines that apply, the totals, the relationships that must be
satisfied, and an order-ready configuration. The storefront, the mobile
app, the care desk, the business, dealer and partner consoles, the MCP
tools and the ontology all call it. The catalog's `ConfigurationIntegrity`
guarantees the data it reads is whole.

### 1. TMF760 as the single oracle (P1)

- **Shape**: v5 item names and states, `stateReason` codes, a
  `ProductConfiguration` with `configurationCharacteristic[]` values that
  carry `isSelectable`/`isSelected` and ranges, `configurationPrice[]` with
  `unitOfMeasure` and `dutyFreeAmount`, `quantity`, `configurationAction[]`
  for relationship suggestions, and the house `priceSummary` (monthly and
  one-time totals in the tenant currency) as a tagged extension.
- **Query** gains: ranges and defaults on characteristics, per-value
  `isSelectable` from stock, the offering's `productOfferingRelationship`
  list, `unitOfMeasure` and `validFor` on price views, and the offering's
  own configurable characteristics for a non-bundle (already there).
- **Check** gains: numeric-range matching, quantity, price windows, the
  relationship check (requires → `configurationAction: add` suggestions,
  excludes → rejected with the reason), availability per SKU, tiered
  quantity pricing, and totals in the tenant's currency.
- **Channels**: the storefront's offer page, cart preview and checkout
  price from the check response (money.js keeps a fallback for the shelf
  "from" price only); the mobile app's Shop and the business console's
  order-for-someone use query and check; the care desk's Order-from-
  suggestions and Upgrade options resolve through check; the MCP tools
  `get_configuration_options` and `configure_product` already do; the
  ontology gets capability `catalog.configure` over TMF760 so agents can
  only offer valid configurations.
- **Proof**: one reference product in the seeds, "Screens Plus" (three
  enumerated choices with surcharges, one numeric range, one per-seat
  price, one variant with stock, a requires and an excludes, a price
  window, a term with an early-termination price); suite #131
  `configurable_channels_test.js` walks it through shop, app web target,
  care desk, business console and MCP and asserts the same options,
  availability and totals everywhere, then orders it and checks the bill.

### 2. Quantity as a unit of measure (P1)

- A price with `unitOfMeasure {amount: 1, units: "seat"}` is a per-unit
  price. A cart line carries `quantity`; the order item carries it; the
  product carries `quantity` as a characteristic when the offering's spec
  declares `fungible: true` (a spec fact), otherwise the order still
  decomposes into N products as today (numbers and SIMs are not fungible).
- Billing multiplies per-unit recurring prices by the product's quantity,
  and the applied rate carries `quantity` and `unitPrice` characteristics.
  A quantity change is a TMF622 `modify` on the product, prorated at the
  change date like a plan change.
- Tier tables: a price may carry `popRelationship`-linked tier prices with
  `valueFrom/valueTo` on a `quantity` characteristic; the oracle picks the
  tier (Zuora "volume" semantics: all units at the reached tier;
  "tiered" cumulative semantics declared by `priceType: tieredUsage` for
  usage only).

### 3. Stock per sellable combination (P1)

- `ProductStock` gains `stockedProduct` (`ProductRefOrValue` with
  `productCharacteristic[]`, the combination, e.g. colour Icy Blue).
  Reservation resolves the stock row by offering plus the item's
  characteristics, falling back to the offering-level row when no
  combination row exists.
- The oracle reports `availability` per option and per value; the shop
  greys out a colour that is out of stock; the console's Product Stock page
  edits per combination.

### 4. Ranges and declared algorithms (P2)

- Characteristic values may be ranges; a price condition may name a
  range; the check matches a numeric pick against it.
- `pricingLogicAlgorithm` references one of a small set of named,
  documented algorithms implemented in the catalog (`perUnitAbove`: base
  plus unit price times units above a threshold; `stepped`: a tier table).
  No free-form expressions. The copilot proposes tiers first, an algorithm
  only when the owner asks for "per extra screen".

### 5. Price validity windows (P2)

- `validFor` on prices, honoured by the oracle and billing at the rating
  date. A change to a live price on an offering with subscribers is
  refused unless it is a new dated segment; the base-migration notice
  regime applies to detrimental segments (the simulator already prices the
  change).

### 6. Relationships (P2)

- `productOfferingRelationship` stored on offerings: `requires` (its
  `role` says `auto-add`, `prompt` or `block`), `excludes`, `exchangableTo`
  (the like-for-like plan-change list, replacing name heuristics).
- The oracle enforces them; the order gate re-checks them; the shop
  auto-adds or blocks per `addMode`; the console edits them on the offering
  form; the copilot proposes them from "needs" and "cannot be combined
  with" in the owner's sentence.

### 7. Early termination (P2)

- A price with `priceType: penalty` and `unitOfMeasure` in months on the
  offering, declared next to `productOfferingTerm`. Billing computes the
  charge on cease as the price's value times remaining months divided by
  the term, capped by the country pack (Norway: only the subsidised
  equipment's remaining share; no service-fee penalty). A regulator-
  triggered exit (base migration's penalty-free flag) charges nothing.

### 8. Usage tiers (P3)

- Tier rows on a usage price (`priceType: usage`, `unitOfMeasure` per GB,
  `valueFrom/valueTo` on the usage quantity). The BSS rates offline usage
  by the table; the OCS seam pushes the same table on activation so
  real-time and bill agree. Stepped versus volume declared by the price.

### Faces

- Shop and app: pickers with ranges as number inputs, quantity steppers on
  fungible products, out-of-stock options greyed, "requires" auto-added
  with a note, price windows shown as "until 30 Sep".
- Care desk: Order and Upgrade through the oracle; the configured product's
  choices and quantity on the service row.
- Business console: quantity on order-for-someone; per-seat products.
- Console: relationships and unit of measure on the offering and price
  forms; stock per combination; the copilot card lists choices, tiers,
  relationships and windows.
- Ontology: `catalog.configure` capability; `configureProduct` check-only
  action for agents; the copilot's proposals validated through the oracle
  before the card is shown.

### Proof

Suite #131 `configurable_channels_test.js` (above) plus additions to
`configurator_test.js` (#77) for ranges, quantity tiers, availability,
relationships and windows; `color_pricing_test.js` unchanged and green;
`copilot_test.js` gains a stub scenario for a configurable non-device
product; billing assertions for quantity and the early-termination charge.

## Phases

| Phase | Items | Effort | Demo impact |
|---|---|---|---|
| P1 | oracle across channels, quantity, stock per combination, suite #131 | one night | none until deployed; additive, flag-free |
| P2 | ranges and algorithms, price windows, relationships, early termination | one night | none |
| P3 | usage tiers through the OCS seam, console forms polish, copilot proposals for relationships and tiers | one evening | none |

Order of build: 1 → 2 → 3 → suite → 6 → 5 → 4 → 7 → 8.
