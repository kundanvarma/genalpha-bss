# Configurable products — one oracle, every channel

*Built 2026-09-16 from `docs/configurable-products-plan.md`. A product owner
can model every shape an operator sells with TM Forum's own fields; one door
resolves a configuration; every channel renders what it returns; the bill
rates it through the same door.*

## The rule

**Channels do not price or validate a configuration. They ask.** TMF760
Product Configuration on the catalog (`/tmf-api/productConfigurationManagement/v5`)
is the one oracle:

- `queryProductConfiguration` — the configuration space: the choices with
  their allowed values and ranges, defaults, which values can be picked
  (stock per variant), whether the product is sold per seat, the
  relationships, the prices with their conditions and windows.
- `checkProductConfiguration` — for a pick set and a quantity: accepted or
  rejected with reasons in words and codes, the price lines that apply, the
  totals, what leaving early would cost, and the actions that must ride along
  (a required product to add).

Both answer in the TMF760 v5 shape (`queryProductConfigurationItem`,
`checkProductConfigurationItem`, `state: accepted | rejected`, `stateReason`,
a `ProductConfiguration` with `configurationCharacteristic[]`,
`configurationPrice[]`, `configurationAction[]`, `quantity`) beside the house
view earlier clients read (`computedProductConfigurationItem`, `message[]`,
`monthlyTotal`).

Who asks: the shop's offer page, cart and checkout; the mobile app's buy tab;
the care desk's Order on the customer's card; the business console's
order-for-someone; the MCP tools `get_configuration_options` and
`configure_product`; the ontology through `catalog.configure` and
`catalog.configurationSpace`; and **billing**, which prices every product's
configuration through the check before it rates the month.

## The shapes, with the standard's field

| Shape | TMF620 field | Example |
|---|---|---|
| A choice with surcharges | characteristic values + a price per value in `prodSpecCharValueUse` | screens 1-2 included, 3-4 +50, 5+ +100 |
| A numeric choice | `productSpecCharacteristicValue {valueFrom, valueTo, rangeInterval}` | extra profiles 0 to 10 |
| A price that counts | `pricingLogicAlgorithm [{plaSpecId: perUnitAbove, characteristic, threshold, unitPrice}]` | 10 per profile above two |
| A tier table | `pricingLogicAlgorithm [{plaSpecId: stepped, characteristic \| quantity, tier: [{valueFrom, valueTo, price, format}]}]` | 1-10 seats at 20, 11+ at 15 |
| Per seat, per licence | `unitOfMeasure {amount, units}` on the price + a spec fact `fungible: true` | 20 per seat, three seats on one line |
| A limited-time line | `validFor {startDateTime, endDateTime}` on the price | a launch pass until month end |
| Early termination | a price with `priceType: penalty` and `unitOfMeasure` in months next to `productOfferingTerm` | 490 declining over 12 months |
| Requires, excludes | `productOfferingRelationship [{id, relationshipType, role}]` | requires fibre (prompt), excludes plain TV |
| A variant with its own stock | TMF687 `stockedProduct.productCharacteristic` on the stock row | box colour Icy Blue: 0 left |

Only the two named algorithms exist, and they are documented here; there are
no free-form formulas. A price that counts a characteristic makes the
characteristic exist on the specification as a range (the copilot completes
it; the catalog refuses an offering whose price conditions on a choice its
specification does not declare).

## Quantity: the rule from the research

If each unit has its own identity (a number, a SIM, a router) it is N products,
one order line of quantity N. If units are interchangeable (seats, licences)
the specification says `fungible: true`, the order line's quantity becomes
**one product carrying `quantity` as a characteristic**, the oracle prices per
unit times quantity, stock reserves the quantity, and the bill multiplies. A
quantity change is a TMF622 `modify`, prorated at the change date.

## Early termination

Billing charges the offering's penalty price on cease inside the term: value
times months remaining divided by the term, as a one-time line naming the
months, declining to zero. A product carrying `penaltyFreeExit: true` (base
migration's flag for a change to the customer's detriment) is never charged.
The oracle reports the price on every check as `earlyTermination` and never
adds it to a total.

## Usage tiers

An allowance may carry `overageTier [{valueFrom, valueTo, price}]`; usage
beyond the allowance walks the tiers cumulatively, the flat overage price
covering anything beyond the last tier. The table is the price definition;
the charging seam pushes it to the OCS at activation so real-time and
bill-time agree.

## Faces

- Shop and app: pickers with sold-out values disabled, a number input for a
  range, a quantity stepper on a per-seat product, the oracle's lines and
  total, Add to cart blocked on a refusal, "requires" shown as an action.
- Care desk: Order on the customer's card configures the same way.
- Business console: order-for-someone with pickers and a quantity.
- Back office: the offering form has Requires / excludes; the price form has
  Per unit of, Price window, Algorithm and Applies only when; the stock form
  has Variant; the copilot proposes all of them from a sentence and the card
  says what each price means ("15 EUR per 1 seat/month", "5 for each
  extraProfiles above 2", "until 2026-12-31").
- Ontology: capabilities `catalog.configure` and `catalog.configurationSpace`
  readable by the shop-home, care-assist, external-mcp, sales-advisor and
  product-copilot agents.

## Proof

- `ops/e2e/configurable_channels_test.js` (#131): the reference product
  "Screens Plus" (`ops/seed/seed_screens_plus.py`) through the oracle, the
  shop, ordering (three seats become one product with quantity 3) and the
  billing run (205 a month, prorated), plus the catalog's refusal of a price
  its specification cannot honour.
- `ops/e2e/configurator_test.js` (#77): the bundle configurator, unchanged
  behaviour in the v5 state vocabulary.
- `ops/e2e/color_pricing_test.js`: phones, colour premiums and the bill.

## Closed on the second night (2026-09-17)

- **`exchangableTo` decides the plan-change list** in the shop, the business
  console and the ontology's upgrade list; without it the category rule still
  applies. The copilot proposes it from "customers can move to".
- **Overage tiers ride to the charging system**: at activation the SOM reads
  the offering's allowance table from the usage component (machine identity
  with `usage:read`) and pushes it to the OCS through the seam; on SigScale it
  lands on the subscriber's product as `bssOverageTiers`, the table the rating
  function reads. A rating function that walks it inside SigScale is the OCS's
  own configuration, outside this repository.
- **The copilot sees every offering name** (a compact index of the whole
  shelf beside full rows for the first forty) and resolves names case-
  insensitively, so "needs Fiber 500" always finds it.
- **The learning loop judges on verdicts**: a recommendation ranks down or
  up from the answers it received (five or more), not from how often it was
  shown in silence.
- **A governed `configureProduct` action**: agents configure through the
  registry with a receipt, the same check the shop runs; `check` and `execute`
  both answer the verdict and the price.
- **Billing prices an installed product `priceOnly`**: today's stock and
  relationships never reprice a subscriber.

## Honest limits

The tier table reaches SigScale as data; whether SigScale rates by it depends
on the operator's rating configuration there. The mobile app configures
inline in its buy tab, not on a page of its own.
