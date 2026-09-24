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

- **The seam exists, the data does not.** `ProductSpecificationDto` already
  carries TMF620's `serviceSpecification[]` reference list, and the SOM's
  TMF638 service view already faces a `serviceSpecification` reference — but
  no retail seed fills the field, so retail offerings name no CFS. The
  wholesale plan calls this out in its own words: *"no ServiceSpecification /
  ResourceSpecification entities — fibre is a flat retail SKU"*.
- **There is no TMF634 resource catalog.** Numbers, SIM profiles, OCS rate
  plans and slice profiles are real resources with real adapters, but they are
  not described as resource specifications anywhere a buyer can browse.
- **Decomposition is code, not data.** `componentType(category)` and the named
  characteristics above are a decomposition table — a good one, proven by
  suites — but it lives in `OrchestrationService`, so adding a new family
  (say, a fixed-wireless access product) is a code change, and a buyer's
  architect cannot read the table off the catalog.

None of this is a defect in what works. It is the difference between "the
catalog carries the intent" (true) and "the catalog *is* the mapping" (not
yet).

## What closes it

Three bounded steps, each provable by a suite and checkable by the claims gate:

1. **CFS on every sellable spec (TMF633).** Author one `ServiceSpecification`
   per fulfilment family — *Mobile line*, *Broadband access*, *TV entitlement*,
   *Device shipment*, *Partner activation*, *Security feature* — in the service
   catalog the product-catalog component already serves, and put its reference
   on each retail product spec. The SOM reads `serviceSpecification[0]` and
   falls back to `componentType(category)` only while a spec names none; the
   claims gate then counts "sellable specs without a CFS" and the count may
   only fall.
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

- This document describes the mapping; it does not build it. Until step 1
  lands, the truthful demo answer is the *short answer* above, not the table.
- Vendor rows summarise public product documentation and TM Forum
  specifications; they are not an evaluation of any vendor's release, and
  names in the table are the vendors' own.
- Activation on the laptop is a stand-in (TMF640 mock): the resources are
  real records, the network is not.
