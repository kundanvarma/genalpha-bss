# 0010 — The identity rule for quantity

**Status:** accepted, 2026-09-16

## Context

An order line "quantity 3" means two different things. Three mobile lines
are three products, each with its own number, SIM, state and bill line.
Three seats on a business licence are one thing counted three times. The
first was always decomposed into N products; the second was being forced
into the same shape, producing three identical products that had to be
ceased together and could not be re-priced as a tier.

## Decision

- **Identity → N products.** If each unit has its own identity (a number, a
  SIM, a router, an IMEI) an order line of quantity N decomposes into N
  products (TMF637), each with its own lifecycle.
- **Fungible → one product with a quantity.** If units are interchangeable,
  the product specification says `fungible: true`. The order line becomes
  **one product carrying `quantity` as a characteristic**; the configurator
  prices per unit times quantity (or a `stepped` tier over the quantity);
  stock reserves the quantity; the bill multiplies.
- A quantity change on a fungible product is a TMF622 `modify`, prorated at
  the change date — never cease-and-reorder.
- The flag lives on the specification, declared by the product owner, so the
  channels and billing never guess from the price's `unitOfMeasure`.

## Consequences

- Costs: a specification must say which it is (default: identity, the safe
  direction); a product owner who marks a numbered thing fungible gets one
  product where they wanted several, and the suite for that spec will say
  so.
- Buys: seat-based B2B products bill as one line with a count; cease, pause
  and upgrade act on the right unit; the inventory holds what the customer
  would count.

## Enforced by

`configurable_channels_test.js` (#131): a per-seat offering orders as one
product with quantity, bills multiplied, and modifies with proration;
review of new specifications.

## Related

`docs/configurable-products.md` "Quantity: the rule from the research",
`docs/configurable-products-plan.md`, ADR 0009.
