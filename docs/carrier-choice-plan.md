# Carrier choice — operator-configured menu + customer picks (Nordic delivery)

**Status:** C-P1 shipped (carrier persisted + de-hardcoded UI) · C-P2–P4 design · **Depends on:** the logistics seam (`LogisticsClient`), the shipping-order flow, the checkout `simType` pattern, the Integrations console tab

A CSP running this BSS should offer **several carriers** (Helthjem, Posten/Bring, PostNord…),
and the shopper should **choose how it arrives** — home delivery, or a pickup point / parcel
locker. Decision locked: **both** — the operator configures the menu, the customer picks from it.

---

## 1. What we found (grounded in the code)

- **The seam is already carrier-agnostic** — `LogisticsClient.book/track` return a normalized
  vocabulary; `Booking` carries `carrier`/`serviceLevel`. Today one `RestLogisticsClient` is
  wired to ONE global carrier (`bss.downstream.logistics-carrier`, default Helthjem).
- **C-P1 (done):** the carrier is now persisted on the shipping order (from `booked.carrier()`,
  V3 column) and de-hardcoded in the shop (`Orders.jsx` reads `ship.carrier`; `Cart.jsx` is
  carrier-agnostic). So the shop already *follows the data* — the rest is choosing the data.
- **The choice-rides-the-order pattern already exists.** `checkout.js` C3: a physical SIM sets a
  `simType` characteristic on the line; `FulfilmentEventListener` reads the order to mint the
  parcel (physical items + `place`, with an install-vs-ship heuristic). Delivery method rides the
  *same* rails — one more characteristic, read at the same point.
- **`LogisticsClient.Booking.request(...)`** takes `serviceLevel` but no carrier / method /
  pickup-point — the fields the booking needs to grow.
- **The Integrations tab** (just shipped) has a Logistics card that's read-only today — the
  config home for the operator menu (the CMS card is the live template to copy).

## 2. Best practice (researched, cited)

- **Pickup points are a first-class Nordic delivery method, not an add-on.** 35% of Norwegians
  chose a service point as their last delivery; it's up to 30% cheaper than home delivery; the
  networks are huge (PostNord 19k+ service points + 5.7k+ lockers) ([PostNord parcels][pn],
  [PostNord locker][pnl]). Posten/Bring pickup points = post offices, grocery stores, lockers
  ([Posten pickup][posten]).
- **The checkout pattern:** the customer *chooses their own pickup point* at checkout — select
  method (home vs pickup) → for pickup, search nearby points by postcode and pick one
  ([Shipmondo/HubBox checkout][hubbox]).
- **Bring/Posten API shape** ([Bring Developer][bring]): a **Booking API**
  (`POST {base}booking/api/booking` → consignment number + label PDF + tracking URL), a
  **Pickup Point API** (returns point id, name, address, hours, lat/long, distance), and a
  **Shipping Guide API** (services + prices for an address). A pickup-point booking sets
  `pickupPoint` (id + country) under `parties`; service codes distinguish home (door) vs
  `0340/0344` pickup/locker. This maps cleanly onto our `AssetProvider`-style adapter seam.

## 3. The design

### 3a. Operator side — per-tenant carrier config (the menu)
Extend the shipped `content_provider_config` pattern to logistics: a per-tenant, RLS-scoped
`carrier_config` (or the shared `integration_binding` from the console plan) with, per tenant,
the **enabled carriers** and each one's `base_url`, `secret_ref`(s), and the **delivery methods
+ service codes** it offers (`home`, `pickupPoint`, `locker`). A `CarrierRegistry` (mirrors
`AssetProviderRegistry`) resolves adapters by name at runtime. Global env (Helthjem) stays the
**fallback default**, so nothing breaks and single-carrier deploys need no rows. This surfaces in
the **Integrations → Logistics card**, made live exactly like the CMS card (list carriers, toggle
enabled, configure, Test connection).

### 3b. Carrier adapters
- **Helthjem** (built-in, exists) — home delivery, poll + callback.
- **Bring/Posten** (new) — Booking API + **Pickup Point API** + Shipping Guide; home + pickup +
  locker via service codes. This is the one that unlocks pickup points.
- **PostNord/Strålfors** — later (Shipment v3 + OAuth2).
Adapters declare which delivery methods they support, so the checkout only offers real options.

### 3c. Customer side — delivery-method picker at checkout
When the cart ships (a physical SIM / device), `Cart.jsx` shows delivery options built from the
tenant's enabled carriers × methods:
- **Home delivery** — carrier + service level.
- **Pickup point / locker** — search by the shipping postcode → a list of nearby points (id,
  name, address, hours, distance) → pick one.
The choice rides the order as characteristics — `deliveryMethod` (`home|pickupPoint|locker`),
`carrier`, `pickupPointId` — **exactly like `simType`**. New read-only storefront face:
`GET /.../pickupPoints?carrier=&postcode=` proxied to the carrier's Pickup Point API (anonymous,
fail-open, cached), so the picker can list points.

### 3d. Fulfilment — book the chosen carrier/method
`FulfilmentEventListener` already reads the order to mint the parcel; it also reads the delivery
characteristics and passes them into an extended `LogisticsClient.Booking.request(..., carrier,
deliveryMethod, pickupPointId)`. `CarrierRegistry.get(carrier)` books via that adapter; the
shipping order stores carrier (C-P1) + the pickup point; `Orders.jsx` shows "Pick up at <point>"
vs "On its way (<carrier>)" — already reading `ship.carrier`, one more field for the point.

## 4. Phasing

- **C-P1 — carrier persisted + de-hardcoded UI.** ✅ shipped.
- **C-P2 — operator menu.** `carrier_config` + `CarrierRegistry` + the live Integrations Logistics
  card + the **Bring/Posten adapter** (home + pickup), proven against a Bring-shaped mock (extend
  `mock-logistics` or add `mock-bring` with a Pickup Point API). Global Helthjem stays fallback.
- **C-P3 — the checkout picker.** Delivery-method + pickup-point selection in `Cart.jsx`, the
  `pickupPoints` search face, the choice riding the order → fulfilment books it → `Orders.jsx`
  shows the method/point. An E2E suite (home vs pickup, per-tenant menu).
- **C-P4 — routing + reach.** Rule-based operator routing (postcode/weight → carrier), locker
  support, PostNord adapter, real-credential opt-in profile (like real Strapi).

## 5. Scope boundaries (honest)

- **Proven against mocks first.** Real Bring/Posten needs an API key + customer number; the
  adapter is built to the documented wire and proven against a Bring-shaped mock (pickup points
  included), with real creds as an opt-in profile — the same honesty as the real-Strapi proof.
- **No live rate-shopping / real labels.** Labels stay a reference (as today); the Shipping Guide
  price is shown if the carrier returns it, not a full rate engine.
- **Pickup-point search is the carrier's**, proxied and cached — we don't build a points database.
- **PSP-style safety** doesn't apply — carriers are fail-open (a booking failure → manual
  warehouse flow, exactly as today).

## 6. Decisions to lock

- **6a. Config home:** a carrier-specific `carrier_config` vs the shared `integration_binding`
  (console plan 7b). → *Proposed: follow the `content_provider_config` shape now; converge on the
  shared table if the console plan adopts it.*
- **6b. Delivery choice on the order:** characteristics (`deliveryMethod`/`carrier`/
  `pickupPointId`, mirroring `simType`) vs a shipping-order attribute. → *Proposed: characteristics
  — same rails as SIM, read at the same point.*
- **6c. Demo carriers:** Helthjem (home) built-in + a Bring-shaped mock (pickup) for C-P2/P3;
  real Bring creds as a C-P4 opt-in. → *Proposed: yes.*
- **6d. Pickup-point face home:** on `fulfilment` vs a new `logistics` face. → *Proposed:
  fulfilment (it already owns the carrier seam).*

---

[pn]: https://www.postnord.com/services/parcel-deliveries/
[pnl]: https://www.postnord.com/services/parcel-deliveries/out-of-home/postnord-parcel-locker/
[posten]: https://www.posten.no/en/receive/flexibility/packages-to-pick-up-locations
[hubbox]: https://www.hub-box.com/networks/postnord/
[bring]: https://developer.bring.com/api/
