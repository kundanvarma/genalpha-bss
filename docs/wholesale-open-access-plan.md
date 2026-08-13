# Wholesale / open-access fibre — becoming an access seeker (and, later, a provider)

*Design doc. A new product line, not an extension of the retail BSS: sell retail
broadband on top of a third party's fibre (open access), split by access layer
(L2 VULA / L3 activated bitstream), ordered and settled operator-to-operator.*

## 1. The opportunity

Nordic fibre is opening. Lyse/Altibox — now the largest broadband base in Norway —
has said it will open its network to third parties, and Nkom keeps extending
VULA/SMP obligations to localised fibre networks. Denmark is ahead: Norlys already
opened its fibre to all providers. That turns "own the fibre or don't play" into a
**wholesale market**: a retail ISP (a NextGenTel) can sell broadband on top of an
infrastructure owner's fibre without laying a metre of it.

Two roles the platform could play — this plan scopes the first, sketches the second:

- **Access seeker** (recommended first): *we are the retail ISP.* We buy wholesale
  access from one or more owners and sell retail broadband on top. Higher value,
  realistic first step, phaseable (start L3 resale, add L2 differentiation).
- **Access provider** (later): *we are the fibre owner opening up.* We sell wholesale
  to retailers. Leans on our multi-tenancy, but needs the inter-operator order-intake
  server side + wholesale billing to retailers + controlled cross-tenant access.

## 2. The two layers (the wholesale product taxonomy)

- **Layer 2 — VULA / bitstream.** The owner hands over an Ethernet/bitstream at a
  handover point; **we run our own IP/BNG, routing, CGNAT, value-add**. Maximum
  differentiation and margin control; heavier to operate. Regulated in Norway under
  Nkom's Market 3a VULA decision.
- **Layer 3 — activated / IP bitstream.** The owner delivers an **IP service**; we
  largely **resell + bill + support**. Lowest barrier to entry, least
  differentiation. The right place to prove the wholesale plumbing cheaply.

The integration standard between operators is **MEF LSO Sonata** (TM Forum-aligned):
serviceability → quote → order → inventory → trouble ticket → wholesale billing,
all operator-to-operator. Our adapter targets that shape even against a dev mock.

## 3. Where we stand today (honest audit)

**Wholesale is not modeled.** A repo-wide grep for
`wholesale|accessSeeker|bitstream|VULA|interconnect` returns zero functional hits,
and the capability map already declares it out of scope
(`docs/capability-map.md:72,125-126`). Scorecard:

| Capability | Status | Evidence |
|---|---|---|
| Access-owner / wholesale party | ❌ absent (weak seam) | `PartyRole.roleType` is free-form but only customer/dealer/household are modeled; TMF668 `PartnershipType.roleTypeJson` is an unused vocabulary slot |
| L2/L3 access product (CFS/RFS) | ❌ absent | no `ServiceSpecification`/`ResourceSpecification` entities — fibre is a flat retail SKU; coverage_map knows `technology`, not access *layer* |
| Upstream inter-operator ordering | ❌ absent | `OrchestrationService` = "TMF640 stands in: instant mock activation. A real adapter would call the network"; the partner adapter is OTT (Netflix), not network |
| Wholesale serviceability (multi-owner) | ❌ absent | `coverage_map` has no owner column — single-owner-implicit, technology+bandwidth only |
| Wholesale billing / settlement | ❌ absent | "settlement" = credit-note state; the only "supplier" is the retailer's UBL invoice to its own consumer (opposite direction) |
| Multi-tenancy as wholesale | ⚠️ SaaS-only | strong RLS isolation, but designed to *prevent* the cross-tenant consumption a marketplace needs (`third-operator-plan.md`) |

**Reusable seams (patterns already proven):**
- **Adapter-registry** — the fulfilment `CarrierRegistry`/`CarrierRouter` and the OTT
  `PartnerEntitlementClient` are the exact template for a `WholesaleAccessAdapter`
  (one impl per owner, mock in dev).
- **TMF668 partnershipType + TMF651 agreement** (`services/agreement`) — free-form
  `roleTypeJson` can encode `accessProvider`/`accessSeeker` + wholesale terms.
- **TMF645 coverage_map** (`services/qualification`) — the natural home for
  footprint-by-owner (add `access_owner` + `access_layer`).
- **The just-shipped bundle decomposition** — a retail fibre order is now a tracked
  component leaf; wholesale adds "what's underneath it": the access input that
  component `reliesOn`. The CFS/RFS layering is a natural continuation of that arc.

## 4. Target model

A retail broadband product is realised over a **wholesale access input** bought from
an owner — the SID CFS/RFS split, made real:

```
Retail ProductOffering "Fibre 1000"                    (billed to the CONSUMER)
  └─ realized by CFS "Broadband Access"                 (customer-facing service)
        └─ rides RFS "Wholesale Access"                 (resource-facing service)
              • accessLayer = L2-VULA | L3-activated
              • accessOwner = <infrastructure owner party>
              • handover / bandwidth profile
              ↑ ordered UPSTREAM from the owner (MEF Sonata), billed BY the owner
```

- **Serviceability answers by owner+layer:** "at this address, NordAccess offers
  L3 up to 1000 and FjordFiber offers L2 up to 500" — the retailer picks one.
- **The retail order decomposes downward:** the fibre component (from the bundle
  decomposition work) now carries an *upstream* access order to the chosen owner;
  the retail line activates only when the wholesale access is confirmed active
  (the same `reliesOn`/deferred-until-live machinery, one layer down).
- **Two money flows, opposite directions:** we bill the consumer (retail, exists);
  the owner bills us (wholesale COGS — new accounts-payable side).

Demo owners are **fictional** infrastructure parties (e.g. "NordAccess", "FjordFiber")
— never real brands, consistent with the project's naming doctrine.

## 5. Phased plan (access seeker) — each phase built + proven

**Phase W1 — Wholesale party + agreement.**
Seed a fictional access-owner organisation party with an `accessProvider` role; add
an `accessSeeker` role for us. Encode the wholesale relationship as a TMF668
partnershipType (`roles:["accessProvider","accessSeeker"]`) with a TMF651 agreement
carrying the commercial terms (per-line wholesale rate, term). Reuses
`services/agreement` + `party-account` free-form roles.

**Phase W2 — L2/L3 access product (CFS/RFS).**
Introduce the catalog's first real service/resource layer: a wholesale **access
product** distinct from the retail SKU, with `accessLayer` (L2-VULA / L3-activated)
and a link from the retail offering to the access input it rides. Model as new
catalog entities (ServiceSpecification/ResourceSpecification) or, lighter, as a
wholesale ProductSpecification + a `realizedBy` characteristic — decision in build.
The retail "Fibre 1000" gains an underlying access input per owner.

**Phase W3 — Multi-owner wholesale serviceability.**
`coverage_map += access_owner, access_layer`; a cross-owner query returns every
owner that serves an address, their layer and bandwidth ceiling. The retail order
(and the shop's footprint line) show "who can serve you and how" and let the order
carry the chosen owner. Extends TMF645, no invented offering→owner mapping.

**Phase W4 — Upstream access-seeker ordering (Sonata adapter).**
A `WholesaleAccessAdapter` registry (mirroring `CarrierRegistry`) places an
access-seeker order to the owner's OSS — a dev **mock Sonata server** — and completes
asynchronously when the owner confirms activation. Wired into `OrchestrationService`
to *replace the mock activation for fibre*: the retail fibre component now waits on a
real upstream order (the decomposition's deferred/`reliesOn` path, one layer down),
and the process timeline shows the wholesale leg.

**Phase W5 — Wholesale billing / settlement.**
The accounts-payable side that doesn't exist: the owner bills us per active access
line (a supplier bill), reconciled against active wholesale inventory; revenue books
it as COGS so **retail margin is visible** (retail price − wholesale rate). Distinct
from retail customer billing; no touching the consumer's bill.

**Phase W6 — Suite + demo.**
End-to-end: a consumer orders Fibre 1000 → serviceability offers NordAccess (L3) and
FjordFiber (L2) → the order places an upstream Sonata access order → activation
confirmed → the retail line goes live → the consumer is billed retail and the owner
bills us wholesale → the margin shows. One suite, proving the whole two-sided chain.

## 6. The access-provider path (later — sketch, not scoped here)

If we ever want to *be* the fibre owner opening up: implement the **Sonata server
side** (accept access-seeker orders from retailers), **wholesale billing to
retailers**, and a **controlled cross-tenant consumption path** that punches through
the RLS walls (today absolute by design). The multi-tenancy is a real head start
here; the settlement and order-intake are the new work. Bigger arc — decide after W1–W6.

## 7. Deliberately deferred / honest boundaries
- The upstream OSS is a **dev mock Sonata server**, not a certified MEF integration —
  the adapter targets the Sonata *shape* so a real owner slots in later.
- **L2 means we'd run IP/BNG** — the plan models the *commercial + order + billing*
  layer, not an actual BNG/CGNAT network stack (that's OSS/network, out of scope).
- No **quote** step in W1–W6 (Sonata Quote) — serviceability + order first; quoting
  is a fast follow if wholesale rates need per-order negotiation.
- Regulatory nuances (equivalence-of-input, migration/switching between owners) are
  named but not built in the first pass.
