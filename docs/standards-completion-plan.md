# Standards completion — six TMF faces the fleet has earned — plan

*2026-08-03. The TMF701 arc proved a method: find the flow the fleet
already runs, give it its standard face, and the gaps it exposes become
visible work. This plan applies the same method six more times, in
value order. Numbers checked against the public catalog — the SLA API
is TMF623 (not 615 as first guessed); TMF700/697/724 confirmed with
public specs and Apache-2.0 API repos.*

## Research findings

- **TMF700 Shipping Order** + **TMF697 Work Order** are the fulfilment
  pair (TMF646 appointments book the slot; the work order is the visit,
  the shipping order is the parcel). Our recon: physical fulfilment
  today is a WIDE-OPEN seam — "needs fulfilment" is just
  `product.place != null`, SOM waits, and completion is a bare state
  PATCH that a CSR button drives by hand. No shipment entity, no
  tracking, no install-visit state. The terminal-state guard makes a
  second (machine) completion driver explicitly race-safe.
- **TMF623 SLA Management** covers the full SLA lifecycle INCLUDING
  violation and consequence handling. Our recon: agreements carry an
  opaque `characteristic` JSON (SLA terms fit with zero schema change,
  mind the 2000-char cap), assurance problems have derivable durations
  and evented state changes, and — decisive — the credit-note issue
  path is callable IN-PROCESS inside billing (no machine client holds
  billing:admin today, and none should need to: SLA credits are
  PRE-AGREED compensation, contractually authorized).
- **TMF688 Event Management**: the fleet is event-native but offers no
  standard subscription API. Flow is deliberately DB-free (no
  datasource at all) — the hub must be its own small service, reusing
  flow's pattern-listener for ingestion and the distribution-relay
  pattern for delivery-with-retries.
- **TMF724 Incident Management**: the incident agent's traces map onto
  the standard incident resource almost field-for-field; a read face +
  state mapping is cheap and makes the memory loop standards-
  addressable.
- **TMF653 Service Test**: SOM's `diagnose` endpoint already produces
  `{verdict, findings[]}` from live cross-service reads — a ServiceTest
  face over the existing finding-code catalog.
- **TMF639 Resource Inventory**: pools are monotonic counters, not
  free-lists — the honest facade reports ISSUED (the assignment
  ledger), quarantined, and the counter; it must not invent an
  "available" number.

## The phases

### P1 — component #37 `fulfilment`: TMF700 + TMF697 (build first)

The parcel and the visit become RESOURCES:

- **shippingOrder** minted from `ProductOrderCreateEvent` when any item
  carries a place: items, the place, state
  `acknowledged → inProgress → shipped → delivered` (+`cancelled`).
- **workOrder** minted from `AppointmentCreateEvent` whose
  relatedEntity names a ProductOrder (the install visit): appointment
  ref, place, state `planned → inProgress → completed` (+`cancelled`).
- **The warehouse/installer face is the API**: PATCH advances state
  (ordering:write — staff/partner grade); every change events on
  `bss.fulfilment.events`.
- **The completion rule closes today's manual gap**: when the shipping
  order is `delivered` AND any work order is `completed`, fulfilment
  PATCHes the productOrder to `completed` under its own machine
  identity (`bss-fulfilment`, ordering:read/write, granted file+live
  with default-roles stripped) — the CSR button becomes optional, not
  load-bearing.
- **The process layer watches something real**: `bss.fulfilment.events`
  joins the process listener's topics, so the `fulfilled` task's
  timeline shows parcel and visit milestones — and the incident agent's
  assembled context names WHICH leg of fulfilment stalled.
- Party-scoped customer reads (track-my-delivery for free), console
  "Shipping orders" tab with an advance-state action.
- **Suite #73**: physical order mints a shippingOrder; advancing it to
  delivered completes the order end-to-end (SOM provisions — the CSR
  button untouched); an appointment mints a workOrder and BOTH gates
  must pass; the process timeline carries the fulfilment milestones;
  stuck detection still fires when the parcel never moves; walls.

### P2 — TMF623 SLA: breach → credit note

SLA terms as data on the agreement's characteristic (`sla` block:
metric, threshold, credit percent/amount, cap); an SLA monitor INSIDE
billing (the recon's cheaper-and-safer call: in-process
`CreditNoteService.issue`, no new admin machine grant) consuming
assurance `ServiceProblemStateChangeEvent`s: resolved problems whose
duration exceeds the SLA'd threshold, joined to the customer via the
delivery-path logic diagnose already uses, mint an AUTOMATIC credit
note — reason citing the SLA term and the problem id, amount from the
pre-agreed rule, capped. TMF623-shaped `sla`/`slaViolation` read
resources; violations evented. Suite #74: an agreement with SLA terms +
an injected outage that resolves too late → a credit note exists with
the SLA citation; a fast-resolved problem mints NOTHING; caps hold.

### P3 — TMF688 event hub (small service)

`hub` registrations (callback URL + event-type filter, tenant-scoped),
ingestion via the pattern listener, delivery ledger with retries and
backoff (the bill-distribution relay pattern), dead-letter after N.
Suite #75: register a mock listener, receive real fleet events
filtered, kill the listener and watch retries + dead-letter, tenant
walls.

### P4 — TMF724 incident face (on intelligence)

`/tmf-api/incidentManagement/v4/incident` read view over incident
traces (state mapping: pending-verdict → acknowledged, useful →
resolved), ackStatus from verdicts, runbook ref as rootCause aid.

### P5 — TMF653 service test (on SOM)

`serviceTest` resources wrapping diagnose: POST creates a test
(executes the existing triage), result carries the finding catalog;
history queryable.

### P6 — TMF639 resource facade (on SOM)

Read-only `resourcePool` (counter + prefix honestly labeled) +
`resource` list from the assignment ledger + quarantine; no invented
"available" counts.

## Shipped

**P1 — 2026-08-03, suite #73 green (four legs).** Component #37
`fulfilment` (port 8117, `/tmf-api/shippingOrderManagement/v4`) is
live. The parcel: a physical order mints a shippingOrder
(acknowledged), the warehouse drives
acknowledged→inProgress→shipped→delivered over the API (trackingRef
carried), and DELIVERED completes the product order under the
service's own machine identity (`bss-fulfilment`, ordering:read/write,
file+live, defaults stripped) — the CSR button demoted from
load-bearing to optional, race-safe against it by ordering's own
terminal-state guard. The visit: an install appointment whose
relatedEntity names the order mints a workOrder (appointment ref
carried), and BOTH gates must pass — the suite proved a delivered
parcel alone does NOT complete an order with an open visit. The
process layer's timeline now carries the parcel milestones and the
physical flow's 'fulfilled' task completes on delivery — the incident
agent sees WHICH leg of fulfilment stalled. Party-scoped reads gave
customers delivery tracking for free; the suite caught a REAL wall
hole en route (customers hold ordering:write for their own orders, so
the role gate alone could not keep them out of the warehouse — a
party-scope refusal now 403s them explicitly). Regressions green:
storefront, process-memory. Build note: gateway routes are HOST-BUILT
jars — the route 404s until the gateway is rebuilt (bitten again,
recorded again).

**P2 — 2026-08-03, suite #74 green.** TMF623 lives across two owners,
each doing its own job: ASSURANCE detects and keeps the ledger (the
`sla_violation` table the monthly cap is enforced against, the
`SlaViolationEvent`, and the TMF623 read faces `/sla` +
`/slaViolation` — the SLAs projected LIVE from agreements whose
characteristic carries the `sla` block); BILLING compensates
(`SlaViolationListener` → in-process `CreditNoteService.issue`, no
admin machine grant — the recon's call held: nobody decides at breach
time, the contract decided at signature). Proven live: a 0-minute
promise on a named circuit, three sequential outages of ~1m each →
three violations, TWO credited as CN-000022/23 at the pre-agreed
2 EUR, the THIRD recorded-but-capped with the reason written down; a
lenient 99999m control never violated. Machine identity:
bss-assurance += agreement:read (file+live). Debug lessons: assurance
mints ONE problem per object (breaches must cycle); the manual and
auto resolve paths publish DIFFERENT expressions — hook BOTH (the
exact-string replace matched only one and the suite caught it); suite
waits outlive access tokens — re-mint per cycle. Regressions green:
ai-slice (self-heal), credit notes.

**P3 — 2026-08-03, suite #75 green.** Component #38 `event-hub` (port
8123, `/tmf-api/eventManagement/v4`): register a callback + event-type
filter (partner/back-office grade — an event feed is a firehose of
business facts, a customer got 403 on camera), and the fleet's OWN
envelopes arrive — the suite's mock partner received a real
AgreementCreateEvent, tenant-stamped, while unsubscribed noise stayed
on the bus. Ingestion reuses Live Flow's pattern-listener
(`bss\..*\.events`); delivery is a LEDGER with exponential-backoff
retries and a DEAD state that keeps its error — the unreachable
listener was retried 4x then dead-lettered, never lost, never silent.
Flow stays deliberately DB-free, exactly as the recon recommended.
Build lessons: the scaffold's outbox table is NON-OPTIONAL even for a
consumer-ish service (the shared entity validates it), and the
host-built-jar gotcha extends to MIGRATION files — an edited V1 ships
only after `mvn package`. Regression green: storefront.
