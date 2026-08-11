# Track C — Independent per-component fulfillment + bring-your-own logistics seam

**Status:** PLAN (awaiting go). Research done; no code written yet.
**Why now:** demo-found gap. A bundle order today fulfils *atomically* — the
whole order sits at one state and flips to `completed` as a unit. Real operators
fulfil each component on its own clock (mobile/eSIM instant, device ships, fiber
waits for an install visit) and the order shows *partial* progress. Plus there is
no real logistics: `trackingRef` is a free-text string, no carrier is ever called.
This is the single arc that lifts the product from "looks like a PoC" to "this is
how an operator actually fulfils."

---

## 1. What we found (grounded in the code)

| Area | Today | File |
|---|---|---|
| Decomposition | **Already per-component** — one ServiceOrder + ServiceInstance per item, recurses into bundle children | `service-orchestration/.../OrchestrationService.java:104-286,469-482` |
| Mixed-bundle gate | If ANY item has a `place`, **all** activation waits for whole-order completion | `OrchestrationService.java:138-144` |
| Completion | **Atomic** — one `ordering.complete()` (digital) or `maybeCompleteOrder()` (physical) flips the whole order | `OrchestrationService.java:281-283`, `fulfilment/.../FulfilmentService.java:172-188` |
| Per-item state | **None** — items live in a JSON blob; order has a single `state` string | `ProductOrderService.java:459-496` |
| Shipping | **One shippingOrder per order**, all physical items, one place; `trackingRef` free-text is the entire carrier concept | `FulfilmentService.java:65-83,236-253` |
| SIM/MSISDN | **Rich model exists** — ResourcePool, MSISDN draw, SimCard mint (real ICCIDs). No eSIM/physical distinction | `OrchestrationService.java:240-325`, `entity/SimCard.java` |
| Appointment | TMF646 exists; workOrder minted on `AppointmentCreateEvent`; **1 workOrder per order** | `appointment/.../AppointmentService.java`, `FulfilmentEventListener.java:73-88` |
| Seam pattern | OCS is canonical: blank base-URL ⇒ no-op, fail-open, mock under `integrations/` | `som/.../RestOcsProvisioningClient.java`, `integrations/mock-ocs/` |
| The bundle | Live "GenAlpha One Home & Mobile" = TV Max + Sports Pass + Mobile Unlimited 5G + Fiber 1000 + **Choose-your-phone** choice group (seed `seed_genalpha_one.py` had a fixed iPhone; `reshape_bundle.py` turned it into a choice group). €49 one-time fiber install fee. | `ops/seed/seed_genalpha_one.py`, `ops/seed/reshape_bundle.py` |

**Consequence:** the change surface is smaller than feared. We are NOT rebuilding
decomposition or the resource layer. We are adding (a) a per-item state machine +
partial rollup, (b) independent activation timing, (c) a logistics seam, (d) an
eSIM/physical fork.

---

## 2. Standards we target (don't reinvent)

- **TMF622 Product Ordering** — owns the **per-order-item state machine**
  (`acknowledged → inProgress → completed`, + `held/cancelled/failed`) and the
  **`partiallyCompleted`** parent rollup. This is the backbone.
- **TMF641 Service Ordering** — the decomposition target (already have it).
- **TMF640 Service Activation** — the "instant" digital/eSIM track (mock today).
- **TMF700 Shipping Order** — owns "ship a physical thing." Extend the existing one.
- **TMF684 Shipment Tracking** — the normalized tracking-event feed the carrier
  seam emits. **TMF697 Work Order** — the fiber install execution (have it).
- Carrier seam sits *behind* TMF700 and *emits* TMF684-style events.

---

## 3. The design

### 3a. Backbone — per-order-item state machine + derived rollup
- Give each `productOrderItem` its own `state` (persisted, not JSON-blob-only):
  `acknowledged → inProgress → completed` (+ `cancelled/failed`).
- Parent order `state` becomes **derived**: any item non-terminal ⇒
  `partiallyCompleted` (new) or `inProgress`; all items terminal ⇒ `completed`.
- Every fulfillment track (activation, shipping, work order) reports completion
  into the *item*, then the rollup recomputes the parent. Nothing flips the
  parent directly anymore.
- **Preserve today's terminal behavior:** when the parent finally reaches
  `completed`, the existing provisioning/stock/payment/commitment side-effects in
  `ProductOrderService.patch()` still fire exactly once (same-state no-op guard
  already protects races).

### 3b. Independent activation timing (SOM)
- Replace the all-or-nothing mixed-bundle gate (`OrchestrationService.java:138-144`):
  - **Digital / eSIM items** → activate immediately, mark item `completed` now.
  - **Physical items** (device, physical SIM, router) → item `inProgress`,
    hand to the shipping track.
  - **Fiber / install items** → item `inProgress`, hand to the work-order track.
- The fast items no longer wait on the slow ones. Billing for a fast item can
  trigger on its own completion (bill the eSIM now, the fiber on install).

### 3c. Logistics seam (copy the OCS pattern exactly)
- **`LogisticsClient`** interface (fail-open docstring) + **`RestLogisticsClient`**
  gated on `${bss.downstream.logistics-base-url:}` — blank ⇒ no-op, try/catch warn.
- `logistics-base-url: ${LOGISTICS_BASE_URL:}` in application.yml;
  `LOGISTICS_BASE_URL: http://mock-logistics:8080` in compose.
- **Carrier-agnostic interface** (superset of Bring/Helthjem/PostNord):
  `bookShipment`, `getLabel`, `getTracking`, `cancelShipment`,
  `findServicePoints`, + inbound `onDeliveryEvent` webhook.
- **Two interchangeability seams:**
  1. **Normalized status vocab** — every adapter maps carrier raw statuses to one
     internal enum (`CREATED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RETURNED,
     EXCEPTION, CANCELLED`), keeping `carrierRawStatus` for audit.
  2. **Service-level abstraction** — callers ask for `HOME_STANDARD / PICKUP_POINT
     / LOCKER / RETURN`; each adapter maps to the carrier's product code.
- **`integrations/mock-logistics/server.js`** — Bring-shaped mock: `bookShipment`
  returns `consignmentNumber` + label + tracking URL; a timer (or a warehouse
  PATCH) advances status and POSTs a delivery webhook. Self-describing header
  naming Bring/Helthjem/PostNord as the real stand-ins.
- Per-tenant carrier selection via the `_<TENANT>` env-suffix registry pattern
  (same as PIM/AI).

### 3d. Fulfilment — shippingOrder per physical component
- Mint a **shippingOrder per physical item** (not one per order); key dedupe by
  `(productOrderId, orderItemId)`.
- On mint, call `LogisticsClient.bookShipment` → store `trackingNumber`, `labelRef`,
  `carrierShipmentId` on the shipping order.
- Carrier `onDeliveryEvent` (`DELIVERED`) → that **order item** → `completed` →
  parent rollup. Replaces the whole-order `maybeCompleteOrder`.
- **Idempotent + monotonic:** dedupe events on `(carrierShipmentId, status, ts)`;
  never regress `DELIVERED`. (Reuse the outbox/TMF688 idempotency discipline.)

### 3e. eSIM vs physical SIM + the binding gate
- Add a **SIM-type** attribute (spec characteristic / offering): `esim | physical`.
  - **eSIM** → TMF640 activation emits a profile (QR/activation-code stand-in),
    **no shipping track**, completes in seconds.
  - **Physical SIM** → shipping track — but **`bookShipment` is gated on the
    ICCID↔MSISDN binding being ready** (don't ship a dead SIM). This is the one
    deliberate cross-track dependency.

### 3f. Order-time validation (backlog 5b)
- Reject an under-configured bundle at **create** with a clear message
  ("pick a phone") instead of acknowledging-and-hanging. The cardinality gate
  `validateBundleComposition()` (`ProductOrderService.java:308-348`) already
  enforces `numberRelOfferLowerLimit/UpperLimit` — confirm why the stuck order
  bypassed it (storefront likely submitted the bundle without the choice group)
  and close the gap at the API, not just the UI.

### 3g. Storefront — partial progress + tracking
- **My orders / My page** renders per-item state, not one order badge:
  *Mobile — active · number 4XX… · SIM · iPhone — shipped, tracking #, out for
  delivery · Fiber — install booked for the 14th.*
- A tracking link (the mock returns a fake tracking URL; a real carrier returns
  a real one).

---

## 4. Phasing (each phase leaves the tree green)

| Phase | Deliverable | Risk |
|---|---|---|
| **C0** | Per-item state machine + derived parent rollup; preserve terminal side-effects | Med — touches completion/billing trigger path |
| **C1** | Independent activation timing in SOM (digital now, physical/fiber on their tracks) | Med |
| **C2** | Logistics seam (`LogisticsClient` + `mock-logistics`) + shippingOrder-per-item + delivery-event completion | Med |
| **C3** | eSIM vs physical SIM fork + ICCID↔MSISDN dispatch gate | Low-Med |
| **C4** | Fiber install track via workOrder/appointment completes its item; 5b order-time validation | Low |
| **C5** | Storefront partial-progress + tracking UI | Low |
| **C6** | **New numbered E2E suite** (#88) proving a mixed bundle fulfils on independent tracks + carrier booking + delivery + eSIM-instant + fiber-install; then **re-prove full 87/87** | — |

---

## 5. Scope boundaries (honest)

**IN:** per-item state + partial rollup, independent activation, carrier-agnostic
logistics seam with a Bring-shaped mock, eSIM/physical fork + binding gate, fiber
install track, order-time validation, storefront partial rendering, one new E2E
suite, full regression.

**OUT (named, not silent):**
- **Real carrier accounts.** Adapters coded to the seam; only the mock is
  exercised in CI. Bring/Helthjem/PostNord real integration = onboarding runbook
  (they gate credentials behind a customer relationship).
- **Returns / RMA reverse logistics** — model `RETURN` as a service-level now,
  full reverse flow + credit-note reconciliation is a follow-up.
- **Per-SKU ship weights/dimensions** — carriers price/reject on these; add real
  values to the catalog when wiring a real carrier (mock ignores them).
- **Pickup-point / locker picker at checkout** — seam supports `findServicePoints`;
  storefront picker is a follow-up.

---

## 6. Estimate

Realistically **~1 week** of focused work (was "2-3 days" before the logistics
seam was added), phased so each phase is independently shippable and the 87/87
baseline is re-proven at the end. Billing-critical paths (C0) get the most care.

---

## 7. Decisions (locked 2026-08-10)

1. **Carrier = Helthjem.** The `mock-logistics` adapter is shaped after Helthjem's
   contract — **poll-style tracking** (a `getTracking` poller synthesizes the
   normalized events), not webhook push. Bring/PostNord remain fixture-coded
   adapters behind the same carrier-agnostic seam; choosing Helthjem for the mock
   does not lock the seam. Real Helthjem access is gated to existing customers
   (onboarding runbook, not this arc).
2. **eSIM AND physical SIM — both offered, chosen at checkout.** A mobile plan
   carries a **SIM-type choice** (`esim | physical`):
   - **eSIM** → TMF640 activation emits a profile (QR/activation-code stand-in),
     **no shipping track**, completes in seconds.
   - **physical SIM** → shipping track via Helthjem, `bookShipment` **gated on the
     ICCID↔MSISDN binding** being ready. This is the flagship demo contrast: the
     same plan fulfils two ways, on two clocks, in one view.
3. **Demo bundle = keep the live 5-component "choose-your-phone" shape.** Perfect
   stress case: eSIM/TV/Sports instant + phone ships + fiber installs.
