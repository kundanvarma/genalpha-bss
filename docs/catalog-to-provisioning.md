# How the commercial catalog reaches SOM and ROM

*The answer to a question asked at a demo on 23 September 2026: "how does your
commercial catalog know about SOM and ROM, and about all the configuration that
has to go to the downstream systems for service and resource provisioning?"
What this BSS does today, what the established vendors do, where the gap is,
and what closes it. Companion to [architecture.md](architecture.md) and
[wholesale-open-access-plan.md](wholesale-open-access-plan.md), which already
names the CFS/RFS layer as its phase W2.*

## The short answer

Today the commercial catalog carries provisioning intent in **three places a
product manager can already edit**, and the service orchestrator (`service-
orchestration`, the SOM) reads them at order time:

1. **The category decides the fulfilment family.** `Broadband/Fiber` installs and
   never draws a number; `Mobile` is a network line (number + SIM + charging);
   `TV/Streaming` is a digital entitlement; `Devices` ship; `Insurance` and
   `Top-ups` are billing-only and create no service at all; `Partner services`
   activate with the partner; `Security` toggles a feature. This is
   `componentType()` in `OrchestrationService`, driven by the offering's
   `category` on the wire.
2. **Product-specification characteristics are the technical levers.** They are
   plain TMF620 `productSpecCharacteristic` values, so they live in the
   catalog, not in code:
   - `chargingSpecId` — the OCS rate plan the line is provisioned on (per-tenant
     OCS adapter: the bundled mock or SigScale);
   - `zeroRatedApps` — the zero-rating list pushed with the subscriber;
   - `sliceProfile`, `boostHours`, `sliceChargingSpecId`, `guaranteedDlMbps` —
     the 5G slice intent a priority tier or boost pass carries to the core;
   - `zone`, `passValidityDays` — what turns a top-up into a time-boxed travel
     pass on the usage meter;
   - `overageTier` rows on the usage allowance — pushed to the OCS for rating.
3. **Relationships decide ordering.** A TMF622 `orderItemRelationship` of type
   `reliesOn` holds a dependent component (TV) `inProgress` while the base it
   rides (fibre) is still being installed; `excludes`/`requires` on the
   offering are enforced by the TMF760 configurator before the order exists.

With that, a product order **decomposes into one TMF641 service order per
component**, each activated by the TMF640 stand-in (instant on the laptop, an
adapter in production), recorded as a TMF638 service, and given its resources
from the TMF639 side: a number from the tenant's pools, a SIM or eSIM profile
(SM-DP+ seam), an OCS subscriber, a TS.43 entitlement. Broadband over another
operator's fibre goes out as a MEF Sonata access order (TMF645 qualification
first). The proofs are `order_journey_test`, `bundle_decomposition_test`,
`sim_test`, `number_choice_test`, `slice_boost_test`, `sigscale_ocs_test`,
`wholesale_open_access_test`.

So the honest one-liner for a buyer: *the commercial catalog carries the
provisioning intent as standard TMF620 data; the SOM decodes it into TMF641
service orders and TMF639 resources; what is missing is the explicit
service-and-resource catalog between them.*

## What the established vendors do

Every mature stack answers this question with **three catalog layers and a
decomposition table**, and they differ mainly in where the table lives:

| Vendor | Commercial → technical mapping | Where decomposition is authored |
|---|---|---|
| Oracle OSM (+ Siebel/RODOD) | Product specs map to **customer-facing services (CFS)**, CFS to **resource-facing services (RFS)**, RFS to network resources — the SID model, literally | Design Studio: fulfilment patterns and the orchestration plan per product spec |
| Salesforce Industries (Vlocity) | A **commercial catalog** and a **technical catalog**; commercial offers point at technical product specs | EPC decomposition rules + attribute mapping; order decomposes to service/resource orders |
| Amdocs | One shared catalog (Catalog ONE) with product, service and resource layers | Decomposition rules on the catalog entity, executed by OSS order management |
| Ericsson | Catalog Manager holds product and service specs; Order Care decomposes | Catalog-driven, SID-based decomposition |
| Netcracker | Unified product/service/resource catalog | Decomposition as catalog data; orchestration reads it |
| Hansen, Comarch | Product catalog + service catalog with CFS/RFS; "technical product" mapping | Catalog-side mapping rules |
| ServiceNow TNI | TMF620 product spec → TMF633 service spec → TMF634 resource spec, related in the CSDM | Order management decomposes TMF622 into TMF641 and resource orders by those relations |
| TM Forum ODA | Product Catalog, Service Catalog and Resource Catalog are **separate components**, linked by spec references (`productSpecification.serviceSpecification[]`, `serviceSpecification.resourceSpecification[]`) | The Service Order Management component decomposes; the mapping is catalog data, not code |

The pattern is the same everywhere: **the mapping is data on the catalog,
authored at design time, executed by orchestration at order time**. Nobody
integrates "product X means do Y" into the order handler.

## Where this BSS is, honestly

- **Step 1 is built (25 September 2026): every sellable spec names a CFS.**
  Six customer-facing services live in the service catalog the product-catalog
  component serves over TMF633 — *Mobile line*, *Broadband access*, *TV
  entitlement*, *Device shipment*, *Partner activation*, *Security feature* —
  each declaring its family as the characteristic `fulfilmentFamily`
  (`serviceType` already means CFS | RFS). Every retail product spec an Active
  offering sells carries the reference in TMF620's `serviceSpecification[0]`
  (`ops/seed/seed_service_specifications.py`, idempotent, runs last). At order
  time the SOM resolves offering → spec → CFS and runs the family the CFS
  declares; `componentType(category)` is now only the fallback for a spec that
  names none, so an unfilled catalog changes nothing. The inventory row records
  the CFS it realised, and the TMF638 `serviceSpecification` points at the real
  spec instead of a derived `svcspec-<category>` stand-in. Proven by
  `cfs_decomposition_test.js` (suite #228) with the category deliberately
  lying: a "Mobile plans" offering whose spec names the TV CFS is fulfilled as
  TV and draws no number; the same category with a CFS-less spec still draws
  one.
- **The gate: every sellable spec names a CFS** — `ops/arch/cfs_check.py`
  judges the *live* catalog (an Active offering with a spec and a retail
  category; billing-only Insurance and Top-ups, the Bundles container and the
  wholesale line, which has its own CFS, are out of scope) and exits 2 on the
  first spec without a resolvable CFS that declares a family. It runs on every
  pull request against the smoke fleet, beside the row-level-security check,
  and in the proof run. Zero is the only passing count. It found two things on
  its first day: the Sports Pass sharing the iPhone's product spec (one spec,
  two fulfilment families — fixed in the bundle seed), and duplicate spec
  names that let a re-run of one seed re-point an offering at an unstamped
  twin (why the CFS seed runs last).
- **Step 2 is built (25 September 2026): the chain under every CFS.** The
  product-catalog component serves **TMF634** beside TMF633 (ADR-0021: one
  deployable, the standard path, extraction later along the API). Seven
  **resource specifications**, one per seam the orchestrator drives — number,
  SIM profile, online-charging subscriber, network-slice binding, wholesale
  access, partner entitlement, customer premises equipment — each naming its
  **seam** and never a vendor; `tenants.yml` keeps choosing the vendor. Seven
  **resource-facing services** (TMF633 `serviceType: RFS`) each name the
  resource spec they realise in the standard `resourceSpecification[]`, and
  every CFS lists the RFS it needs as `reliesOn` relationships whose edge
  declares what the RFS **consumes** (the product-spec characteristics it
  reads — values stay where the product manager edits them) and whether it is
  **required** for every order or only when the product or the address calls
  for it. TV entitlement, device shipment and security feature honestly need
  no RFS: nothing outside this BSS realises them today. Seeded for GenAlpha and
  Taranga by `ops/seed/seed_resource_facing_services.py`, which runs last and
  refuses to rewrite a CFS (a first run clobbered one through a name clash).
- **The orchestrator records what it realised — behaviour unchanged.** At
  order time the SOM walks offering → product spec → CFS → RFS → resource spec
  and, at each seam it actually exercises (number, SIM, charging, slice,
  wholesale access, partner entitlement), writes a **realisation** on the
  service: which RFS (when the CFS declared one), the seam, the vendor that
  served it, the external reference. TMF638's service view lists them as
  `supportingService[]` with a `declared` flag; TMF639's issued resource
  references its resource specification. The same adapters run, the same
  numbers are drawn; a wrong catalog entry is a red check, never a wrong
  activation.
- **The gate: catalog and orchestrator agree** — `ops/arch/cfs_check.py` now
  also compares, for every CFS the orchestrator has realised services for,
  what the catalog declares with what was realised: red when a service
  realised a seam its CFS does not declare, or a *required* RFS was never
  realised by any service of that CFS; an optional RFS nobody needed is a
  note. It judges against the catalog as it is now, so declaring the missing
  RFS is the fix and turns it green; services whose CFS is gone or that
  predate step 2 carry no evidence and are counted, not judged; an
  inventory it cannot reach is red. Same step as the CFS gate, on every pull
  request and in the nightly. Proven by `cfs_realisation_test.js` (suite
  #229): the seam the catalog forgot goes red, declaring it goes green.
- **The offering page shows the chain.** The admin console's Product Offering
  editor carries a read-only *Decomposition* panel: product spec → CFS
  (family) → each RFS and what it reads → resource spec (seam) → who provides
  the seam for this tenant (its configured vendor, or the fleet's built-in
  adapter) — in operator language, honest when a link is missing.
- **Step 3 is built (25 September 2026): the orchestrator obeys the list.**
  One **executor** in the SOM walks a fixed seam order — wholesale access,
  partner entitlement, number (or `edge-gpu` for a compute product), SIM,
  charging, slice, customer premises equipment — and runs exactly the
  resource-facing services the CFS declares, dropping an optional one when
  the product carries none of the characteristics its edge says it consumes,
  and skipping a seam whose adapter says the environment does not call for it
  (no fibre owner at that address). The **set** of seams is catalog data; the
  **order** stays code, because a catalog that could reorder seams could
  provision a SIM before a number exists. Each seam is a **seam adapter**
  behind a registry keyed by seam name, so a seam nobody serves in this fleet
  is recorded and logged rather than thrown, and adding one — a content
  platform for TV, a market hub for electricity — is one adapter class plus
  catalog data, with no change to the executor.
- **The executor is the only reader of the product specification.** It builds
  the map of consumed values from the spec and the order item and hands it to
  the adapter; an adapter no longer reaches into the catalog for its own
  characteristics. The mapping on the CFS→RFS edge is therefore what actually
  flows, not documentation.
- **Families are labels now.** `mobile`, `internet`, `tv`, `device`,
  `partner`, `security`, `compute`, `billing-only` mean something to people
  and screens; the code keys on the declared seams. Two decisions that used to
  read an offering's *name* are gone: the stadium slice is an RFS on the slice
  seam consuming `deliveryPath`, and Edge AI is the `compute` family on the
  `edge-gpu` seam with its own CFS and RFS. Insurance and top-ups name a
  **billing-only** CFS with zero RFS: nothing to provision is a catalog fact,
  and a top-up that carries a slice profile declares the slice RFS as optional,
  so the boost pass still rides the customer's line.
- **The category table is a counted debt.** A product spec that names no CFS
  is still fulfilled by `componentType(category)`, and the service says so
  with a realisation on seam `category-fallback`. `cfs_check.py` prints the
  count, `ops/arch/ratchet.sh --live` pins it and refuses an increase, and a
  ceased service leaves the count. Zero on both tenants today; deleting the
  table is the last commit of that debt, not part of this step.
- **The product manager picks a pattern by name.** The Product Specification
  form carries a *Fulfilment* field listing the tenant's customer-facing
  services in words ("Mobile line — a network line", "Billing-only product —
  nothing to provision") with the consequences beneath; no seam, RFS or vendor
  is a control. The product copilot proposes the pattern from the description
  and characteristics and names what is missing ("no charging plan: charging
  will not be provisioned"); Create is a click, and the assignment runs
  through the governed action `assignFulfilmentPattern` with a receipt, so an
  agent uses the same door.
- **A dry run says what will happen before anyone orders.** The SOM walks the
  same plan without calling a single adapter and returns the steps, the values
  each consumes, the vendor per seam, and a verdict; the offering page shows it
  in words, and launch governance carries it as a readiness item that goes red
  when a required seam has no adapter in this fleet.

The catalog is now the decomposition and the orchestrator obeys it, checked
both ways: the gate compares what is declared with what was realised, and the
ratchet counts every order still fulfilled by the old table.

## What closes it

Three bounded steps, each provable by a suite and checkable by the claims gate:

1. **CFS on every sellable spec (TMF633) — built, see above.** One
   `ServiceSpecification` per fulfilment family in the service catalog the
   product-catalog component already serves, its reference on each retail
   product spec; the SOM reads `serviceSpecification[0]` and falls back to
   `componentType(category)` only while a spec names none; `cfs_check.py`
   counts sellable specs without a CFS on the live catalog, and zero is the
   only passing count.
2. **RFS and resource specs (TMF634) — built, see above.** Under each CFS,
   the resource-facing services it needs, each naming a `ResourceSpecification`
   that names the seam (`tenants.yml` picks the vendor); the CFS→RFS edge
   declares what each RFS consumes and whether it is required; the
   orchestrator records what it realised and the gate checks the two agree;
   the offering page shows the chain.
3. **The decomposition table becomes data — built, see above.** The SOM obeys
   the RFS list through a registry of seam adapters, families are labels, the
   name-based decisions and the billing-only category test are catalog data,
   and `componentType()` survives only as a counted fallback the ratchet lets
   fall. Step 2's realisation records and gate are the safety net it runs
   under. What is left of it is one line of debt: deleting the table when the
   count reaches zero for good, which means the eleven ordering suites and any
   legacy offering naming a pattern first.

Estimate: steps 1 and 2 took two days between them (25 September 2026), most
of it proof; step 3 is a week, with the existing adapters unchanged.

## Honest limits

- All three steps are built. The truthful demo answer today is: the product
  spec names its customer-facing service, the CFS names the resource-facing
  services it needs and what each consumes, and the orchestrator runs exactly
  those — checked both ways on every pull request. What is *not* true: the
  category table is gone (it is counted debt at zero, not deleted), and a new
  seam still needs an adapter written before any catalog entry can use it.
- The seam **order** is code and stays code. A catalog that could reorder
  seams could provision a SIM before a number exists.
- A CFS declaring a seam no adapter serves in this fleet is recorded, logged
  and shown as a red dry run; it is not a runtime error and not a silent
  skip. Adding a product line — a content platform, an electricity supply —
  is catalog data *plus* its adapters.
- The eleven ordering suites and any offering whose spec names no pattern
  still ride the counted fallback. The ratchet stops that number rising; it
  does not make migrating them free.
- The dry run's readiness item refuses a launch only where launch governance
  is switched on for the tenant. GenAlpha runs with it off, so the refusal
  was proven on Taranga.
- The disagreement gate can only judge a CFS that has realised services; a
  CFS nobody has ordered since step 2 has no evidence yet, and the 5,000-odd
  services that predate step 2 carry no realisation rows and are counted,
  not judged.
- `required` on a CFS→RFS edge is authored by the seed from what the code
  does today. A product manager who changes it is trusted; the gate tells
  them when the orchestrator disagrees.
- The offering page knows the vendor only for seams the tenant configures
  (today the online-charging provider); every other seam is served by the
  fleet's built-in adapter and the panel says so. The vendor that actually
  served an order lives on the service's realisation, not on the page.
- Vendor names on realisations are the adapters' own (`own-pool`,
  `house-sim-issuer`, the tenant's OCS provider); they describe this fleet,
  not a product decision.
- The gate judges only what an Active offering sells under a retail category.
  Offerings with no category or no spec (mostly suite fixtures) are printed
  and not judged; the debris sweep owns them.
- A CFS whose family disagrees with its offering's category is a *warning*,
  not a failure: overriding the category is the point of authoring a CFS, but
  it is also where a data mistake would silently change fulfilment — read the
  warnings.
- The seed maps categories to families with a fixed table; a tenant with its
  own category names (Taranga, ENet) gets its CFS by running the seed against
  its realm (`BSS_REALM=taranga`) only if its categories use the same names —
  otherwise the mapping is authored by hand in the console.
- Vendor rows summarise public product documentation and TM Forum
  specifications; they are not an evaluation of any vendor's release, and
  names in the table are the vendors' own.
- Activation on the laptop is a stand-in (TMF640 mock): the resources are
  real records, the network is not.
