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
- **There is no TMF634 resource catalog.** Numbers, SIM profiles, OCS rate
  plans and slice profiles are real resources with real adapters, but they are
  not described as resource specifications anywhere a buyer can browse.
- **The family is data; the family's *behaviour* is still code.** The SOM
  knows exactly six families; authoring a seventh CFS (say, fixed-wireless
  access) is refused with a log line until the orchestrator learns what it
  means, and the characteristics above (`chargingSpecId`, `sliceProfile` …)
  still ride the product spec rather than the CFS→RFS edge.

The catalog now *is* the mapping for the first hop, product spec → CFS; the
hops below it (CFS → RFS → resource spec) are steps 2 and 3.

## What closes it

Three bounded steps, each provable by a suite and checkable by the claims gate:

1. **CFS on every sellable spec (TMF633) — built, see above.** One
   `ServiceSpecification` per fulfilment family in the service catalog the
   product-catalog component already serves, its reference on each retail
   product spec; the SOM reads `serviceSpecification[0]` and falls back to
   `componentType(category)` only while a spec names none; `cfs_check.py`
   counts sellable specs without a CFS on the live catalog, and zero is the
   only passing count.
2. **RFS and resource specs (TMF634).** Under each CFS, the resource-facing
   services it needs — *MSISDN*, *SIM/eSIM profile*, *OCS subscriber*, *slice
   binding*, *Sonata access* — each pointing at a `ResourceSpecification` that
   names the adapter seam (`tenants.yml` picks the vendor). The characteristics
   above (`chargingSpecId`, `sliceProfile`, `zone` …) become **characteristic
   mappings on the CFS→RFS edge**, which is exactly where Vlocity and OSM keep
   them.
3. **The decomposition table becomes data.** `componentType()` retires; the
   SOM walks product spec → CFS → RFS → resource spec, and the console's
   Offering workspace shows the chain on the offering page — the thing the
   demo question was really asking to see.

Estimate: step 1 is a day (seeds, one DTO field already there, one SOM read,
one gate); steps 2–3 are the wholesale plan's W2 generalised to retail — about
a week, with the existing adapters unchanged.

## Honest limits

- Step 1 is built; steps 2 and 3 are not. The truthful demo answer today is:
  the product spec names its customer-facing service and the orchestrator
  obeys it; below the CFS, the resource-facing layer is still the adapters and
  characteristics described in the *short answer*, not catalog data.
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
