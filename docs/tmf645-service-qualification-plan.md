# TMF645 Service Qualification — what the network can deliver here — plan

*2026-08-03. The configurator arc closed the last catalog-side gap; the
next question moves to the address. The fleet already answers the
COMMERCIAL question — TMF679 on `services/qualification` gates offerings
by postcode prefix, and the storefront's cart and checkout both consult
it. But nobody answers the TECHNICAL one: what technology, at what
speed, can actually be delivered at this address? Today an address is
either "serviceable" or not, with no notion of fiber vs 5G-FWA vs VDSL,
no bandwidth, and no alternative when the first choice is unavailable.
TMF645 Service Qualification is the standard face for exactly that —
one of TM Forum's Pre-Ordering APIs, "service availability at customer
location" (v4.0.1 current, v5.0.0 released 2026-03; we mount v4 like
the rest of the fleet since a v4 exists).*

## Research findings

- **The serviceability spine exists and is honest about what it knows.**
  `services/qualification` (TMF679) owns `serviceable_area` rows —
  postcode-prefix → offering gate, config-as-data, seeded over REST
  (fiber gated to prefixes 111/222/333). The storefront calls
  `checkQualification` from the Cart effect and again as the
  authoritative gate in `checkout.js`, with TMF673 address validation
  (`services/geographic-address`) standardizing the address first. The
  place that rides orders is a flat GeographicAddress
  `{role, street1, postCode, city, country}` — stored verbatim by
  appointment and fulfilment, never parsed for coverage.
- **TMF645 ≠ TMF679.** 679 asks "may I SELL this offering here?"
  (commercial). 645 asks "can the network DELIVER this service here,
  and what are its characteristics?" (technical) — and its classic
  behavior is the ALTERNATIVE: asked for fiber where there is none, a
  real qualification answers "no — but 5G-FWA at 100 Mbps is
  available". Serving 679's rows under a 645 path would be a face
  claiming more than the thing knows; the arc must add the technical
  substance: a COVERAGE MAP as data.
- **Host: `services/qualification`.** It is the qualification bounded
  context, has the full house scaffold (outbox, tenant security, RLS),
  already speaks anonymous shop-window checks, and the coverage map is
  a sibling of `serviceable_area`. SOM was considered and rejected: it
  owns provisioning AFTER the order, has no address anywhere, and
  pre-sale feasibility does not belong with resource pools.
- **Nobody gates the ORDER on serviceability.** The storefront checks
  at cart/checkout (client-side discipline); the ordering API itself
  runs validateBundleComposition → enforcePolicy → stock, with no
  feasibility hook — an API-direct or agent order for un-serviceable
  fiber sails through today and fails at fulfilment. Same shape as the
  configurator gap: prove it, then close it (P2).
- **Config-as-data pattern to follow**: `serviceable_area` itself —
  entity + repo + CRUD + REST seeding (`seed_serviceable_areas.py`),
  RLS-scoped, events on change.

## The design

- **`coverage_map` rows** (qualification service): tenant-scoped
  `{technology, postcodePrefix, maxDownMbps, maxUpMbps, note}` — the
  operator's radio/fiber footprint as editable data. An empty prefix
  matches everywhere (the mobile fallback). CRUD staff-gated like
  serviceable areas; changes evented.
- **`POST /tmf-api/serviceQualificationManagement/v4/queryServiceQualification`**
  — "what CAN you deliver here?": given a place (searchCriteria), every
  technology whose prefix covers the postcode, as qualified service
  characteristics (technology, bandwidth), longest-prefix row winning
  per technology. Anonymous: the footprint is the shop window.
- **`POST .../checkServiceQualification`** — "can you deliver THIS
  here?": serviceQualificationItem[] each naming a requested service
  (serviceSpecification name = technology, optional expected
  bandwidth); answers qualified/unqualified per item with
  `eligibilityUnavailabilityReason` and — the 645 signature — an
  `alternateServiceProposal` carrying the best technology the map CAN
  deliver there. PERSISTED (task resource with state=done, readable by
  id) — a qualification is a fact worth keeping; the suite reads it
  back.
- Gateway route `serviceQualificationManagement` → qualification
  (mvn package the gateway first — the drilled gotcha).

## The phases

### P1 — the coverage map and the standard face (build first)

Migration (`coverage_map` + RLS + `service_qualification` result
table), entity/repo/service/controller, security matchers (anonymous
POSTs + per-id GET; staff CRUD), gateway route, REST seed
(`seed_coverage_map.py`: fiber 1000 in 111*, fiber 500 in 222*, fiber
300 + VDSL 100 in 333*, 5G-FWA 100 everywhere). **Suite #79
`servicequal_test.js`**: query at a Stockholm postcode lists fiber
1000 + the universal 5G-FWA; query at a rural postcode lists ONLY
5G-FWA; check fiber at the rural postcode → unqualified with the
reason AND the 5G-FWA alternative proposed; check fiber in 111* →
qualified at 1000 Mbps; the persisted qualification reads back by id
with the same verdict; coverage CRUD is staff-only; nova's hostname
sees nova's (empty) footprint, never genalpha's. Regressions serial.

### P2 — channels adopt the answer (later)

Storefront: the cart's serviceability line grows the technology and
speed ("Fiber 1000 available at your address" / "no fiber here — 5G
broadband 100 Mbps instead") from the query endpoint. Ordering: the
gap proven then closed — a fiber order for an uncovered address is
refused at the API with the qualification's own reason (hook beside
validateBundleComposition, anonymous in-process call, fail-open).
Console: a Coverage tab over the CRUD.

## Shipped

**P1 — 2026-08-03, suite #79 green (four legs), first full run.** TMF645
lives on the qualification service at
`/tmf-api/serviceQualificationManagement/v4`, beside the TMF679 it
completes. The substance is the `coverage_map` — five seeded rows:
fiber 1000/1000 in 111* (XGS-PON), 500 in 222*, 300 in 333*, VDSL 100
in Malmö's copper, and 5G-FWA 100 with the EMPTY prefix as the
everywhere-fallback — operator-editable over REST, evented, RLS-walled.
`queryServiceQualification` answers the footprint per place (longest
prefix wins per technology); `checkServiceQualification` never says a
bare no — fiber in Kiruna refused WITH the 5G-FWA alternative proposed,
a 500 Mbps ask against Malmö's 300 Mbps plant refused naming the
ceiling, the same ask in Stockholm qualified at the full 1000. Checks
PERSIST (`service_qualification`) and read back by unguessable id; the
list (customer addresses) and the coverage CRUD are back-office —
anonymous and customer both refused on camera, staff served, and
nova's hostname answered from nova's own empty footprint. Regressions
green (serial): guest, storefront.
