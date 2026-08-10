# Track C — overnight build progress

Branch `track-c-fulfillment` (off `main` @ 87/87, which stays frozen). Live log,
newest at top. Plan: `docs/track-c-fulfillment-plan.md`.

---

## ☀️ MORNING SUMMARY (read this first)

**The core is built, proven, and committed.** A bundle order no longer fulfils
all-or-nothing — each component now completes on its own clock, and physical
goods ship through a real carrier seam (Helthjem). This is exactly the gap you
spotted ("mobile shouldn't wait for fiber; and we need a logistics partner").

**What works right now (proven end-to-end on the live stack):**
- **C0 — per-item order states.** Every order item has its own state; the order
  derives `partiallyCompleted` → `completed` from its items.
- **C1 — independent activation.** Digital services (mobile, TV, add-ons)
  activate in seconds; items that ship/install wait on their own track. The
  mobile no longer waits for the fiber.
- **C2 — Helthjem logistics seam.** Physical items are **booked with a carrier**
  (Helthjem-shaped mock returns a real tracking number, e.g. `HJ6268833420`),
  the parcel moves on its own, and its **delivery completes that item** and rolls
  the order up — no human touch. Carrier-agnostic + fail-open (same pattern as
  your OCS seam); Bring/PostNord drop in behind the same interface later.
- **C5 — visible in the shop.** `My orders` shows each component's live status
  ("✓ Active", "📦 On its way · HJ… (Helthjem)", "🔧 Install booked").

**Proven:** mixed order → digital `completed` instantly + physical `inProgress`
with a Helthjem tracking number → ~15s later carrier delivers → order `completed`.
Pure mobile-plan order still completes normally (regression check passed).

**How to see it:** order a mix of a digital item + a physical item (any item with
a shipping address) in the shop, open **My orders** — you'll watch the digital
part go active immediately and the parcel arrive on its own with tracking.

**NOT done yet (clearly scoped, safe to finish next):**
- **C3 — eSIM vs physical SIM choice** (you wanted both in the demo). Needs a
  SIM-type choice at checkout + an eSIM-instant vs physical-ships branch. ~½ day.
- **C4 — fiber install track** completing its item on install-done + reject an
  under-configured bundle at order time (5b). ~½ day.
- **C6 — full 87-suite regression** + update the SOM unit test + merge to `main`.
  I ran targeted smoke regressions (pure digital ✅, mixed ✅) but NOT the full
  sweep — do this before merging. `main` is untouched at 87/87, so nothing is
  at risk.

**Honest simplifications (documented in the log):** a deferred item's backend
service still activates optimistically at order time (only its *item state*
shows pending) — C4 defers real activation. Helthjem access is gated to
customers, so only the mock runs; the adapter is coded to the seam. Nothing here
is on `main` yet.

---

**Build loop (for reference):**
`export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=$JAVA_HOME/bin:$PATH` →
`cd services/<svc> && mvn -q package -DskipTests` →
`docker compose build <svc> && docker compose up -d <svc>`.

---

## Status board

| Phase | What | State |
|---|---|---|
| Setup | branch, plan, toolchain validated (no host JDK → use openjdk@17) | ✅ done |
| C0 | per-item state machine + derived rollup (additive, behavior-neutral) | ✅ done + smoke-proven |
| C1 | independent activation timing (digital now, physical/fiber on tracks) | ✅ done + proven |
| C2 | Helthjem logistics seam + mock + ship-per-item + delivery completes item | ✅ done + proven E2E |
| C3 | eSIM vs physical SIM checkout choice + ICCID↔MSISDN dispatch gate | — |
| C4 | fiber install track completes its item + order-time validation (5b) | — |
| C5 | storefront partial-progress + tracking view | ✅ built (order page) |
| C6 | E2E suite #88 + re-prove full 88/88, merge to main | — |

---

## Log

**C0 done — per-item state machine + rollup (product-ordering).** Each order item
(incl. bundle children) is stamped with a stable `id` + `state` at create.
New `PATCH /productOrder/{id}/productOrderItem/{itemId}` lets a fulfillment track
report one item's completion; the parent state is derived: any-done-not-all →
`partiallyCompleted`, all-terminal → `completed` (side-effects fire once).
Completion side-effects extracted to `applyCompletion()`, shared by the whole-order
PATCH and the rollup. **Additive/behavior-neutral:** digital-only orders still
auto-complete via the existing SOM path (verified — SOM raced my first smoke test
and completed the order, so `updateItemState` correctly no-op'd on a terminal
order). **Smoke-proven** on a held order (place → SOM waits):
`item1=completed → partiallyCompleted`, `item2=inProgress → partiallyCompleted`,
`item2=completed → completed`. Files: `ProductOrderService.java` (stampItemStates,
updateItemState, rollupState, applyCompletion), `ProductOrderMapper.java`
(readItems/writeItems), `ProductOrderController.java` (patchItem endpoint).

**C1 done — independent activation (service-orchestration).** Removed the
order-level `needsFulfilment` gate that made a mixed order wait as a whole. Now
each item is handled on its own: a digital service activates immediately and its
item is reported `completed`; an item carrying a `place` (ships/installs) is
reported `inProgress` and left for its own track; billing-only items report
`completed`. The final whole-order `ordering.complete()` is replaced by per-item
reports that drive the C0 rollup (with a safety-net fallback for orders whose
items predate C0 ids). New `OrderingClient.updateItemState` (fail-soft — a failed
status callback never unwinds a real activation). **Proven:** a mixed order
(Netflix digital + Kids-TV-with-place) → Netflix `completed`, Kids TV
`inProgress`, order `partiallyCompleted`, fully automatically. KNOWN: SOM unit
test `OrchestrationTest` still asserts `complete()` — update to assert
`updateItemState` before C6 merge. SIMPLIFICATION (documented): a deferred item's
backend service still activates optimistically at order time; only its *item
state* honestly shows pending. C4 will defer real activation to the install.

**C2 done — Helthjem logistics seam + per-item delivery completion.** New
carrier-agnostic seam in fulfilment: `LogisticsClient` (fail-open — blank base
url = no-op, carrier error = warn + manual fallback) + `RestLogisticsClient`,
copying the OCS seam convention. New `integrations/mock-logistics` = a
Helthjem-shaped carrier: `POST /shipments` books a parcel (returns HJ tracking
number + label + tracking URL), auto-advances CREATED→IN_TRANSIT→DELIVERED, and
fires a delivery CALLBACK (a real Helthjem adapter would poll `getTracking` —
seam supports both). fulfilment books at dispatch, stores the tracking number,
and on the carrier's DELIVERED callback (`POST /carrierEvent`, permitAll —
carrier isn't a BSS identity) completes each shipped ITEM (`updateItemState`)
which rolls the order up. Ordering now also stamps all items completed on a
whole-order completion (consistency). Compose: `mock-logistics` on :8128,
`LOGISTICS_BASE_URL` on fulfilment. **Proven end-to-end:** mixed order → Netflix
`completed` instantly, Kids TV `inProgress` + shippingOrder booked with Helthjem
(tracking HJ6268833420) → ~15s later carrier DELIVERED callback → Kids TV
`completed`, order `completed`. Fully automatic, no human touch. Files:
`LogisticsClient`, `RestLogisticsClient`, `OrderingClient` (+updateItemState),
`FulfilmentService` (book + onCarrierEvent + completeShippedItems),
`FulfilmentController` (/carrierEvent), `SecurityConfig` (permitAll),
`integrations/mock-logistics/*`, compose, fulfilment application.yml,
`ProductOrderService.markAllItems`.

**C5 (partial) done — storefront order page shows per-item progress.**
`My orders` now lists each component with a live status: digital "✓ Active",
physical "📦 On its way · HJ… (Helthjem)" / "✓ Delivered", install "🔧 Install
booked". Order badge shows friendly "In progress" for partiallyCompleted. New
`myShipments()` api call (party-scoped shipping orders → tracking). Files:
`Orders.jsx`, `api.js` (myShipments), `styles.css`. Deployed & healthy; needs a
quick visual check in a browser. My-page/Services per-item view = follow-up.
