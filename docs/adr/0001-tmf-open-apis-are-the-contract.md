# 0001 — TM Forum Open APIs are the contract

**Status:** accepted, 2026-07-09 (first five CTKs at zero failures; recorded as an ADR 2026-09-22)

## Context

Every component exposes a TM Forum Open API (TMF620, TMF622, TMF678 and so on)
and is run against TM Forum's official Conformance Test Kits through the gateway
with a real token. The house needs fields the standard does not carry — a
configurator's `monthlyTotal`, launch-governance state, a decision receipt.
The temptation is to bolt them onto the standard resource; the cost is a payload
no other vendor's client can read and a CTK that goes red.

## Decision

- The TM Forum resource is the wire contract. Its shape is not changed.
- House views ride **beside** the standard payload, never instead of it: a
  sibling field (`computedProductConfigurationItem` next to the TMF760 v5
  item), a sibling door (`/productOffering/{id}/governance/...` next to the
  TMF620 resource), or a separate house API (`/ontology/v1`, `/dealer/v1`).
- Every served capability that has a published CTK runs it; the scorecard is
  `docs/ctk-conformance.md` and every number there is reproducible from
  `ops/ctk`.
- A CTK failure is allowed only where the house is deliberately stricter than
  the spec (a payment must authorise; a message needs a recipient) and the gap
  is named on the scorecard as a product decision.

## Consequences

- Costs: two shapes to keep for the same fact where the house needs more than
  the standard; extra doors instead of extra attributes; the CTK harness
  (`runctk.py`) must be maintained because the kits' own runner has rotted.
- Buys: any TMF client, any other ODA component and any migration tool reads
  the payload as-is; a component can be swapped for a vendor's without the
  channels noticing; the conformance claim is a number, not a slide.

## Enforced by

`ops/ctk` (28 kits at zero failures at the time of writing; two intentional
gaps listed); the numbered suites, which drive the standard shape through the
gateway; review of any change that adds a field to a TMF resource.

## Related

`docs/ctk-conformance.md`, `docs/configurable-products.md` (v5 shape beside the
house view), `docs/launch-governance.md` (the TMF620 resource stays standard),
`README.md` component table.
