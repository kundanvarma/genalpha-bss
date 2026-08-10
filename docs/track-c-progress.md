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
- **C0 — per-item order states.** Each item has its own state; the order derives
  `partiallyCompleted` → `completed` from its items.
- **C1 — independent activation.** Digital services activate in seconds; items
  that ship/install wait on their own track. The mobile no longer waits for fiber.
- **C2 — Helthjem logistics seam.** Physical items are **booked with a carrier**
  (real tracking number), the parcel moves on its own, and its **delivery
  completes that item**. Carrier-agnostic + fail-open (OCS-seam pattern);
  Bring/PostNord drop in behind the same interface later.
- **C3 — eSIM vs physical SIM, both offered at checkout.** eSIM activates
  instantly with no parcel; a physical SIM ships via Helthjem and completes on
  delivery. Two clocks, one plan.
- **C4 — 5b validation + fiber installs (not ships).** An under-configured bundle
  is refused at order time; fiber's place is the engineer's address (workOrder),
  not a parcel.
- **C5 — visible in the shop.** `My orders` shows each component's live status
  ("⚡ eSIM active", "📦 On its way · HJ… (Helthjem)", "🔧 Install booked").

**Proven:** the new **suite #88 `track_c_test.js` passes GREEN** — it exercises
the whole arc (independent fulfillment, Helthjem booking + auto-delivery, eSIM vs
physical SIM, 5b validation) on the live stack.

**How to see it:** order a mobile plan and pick **eSIM** vs **Physical SIM** in
the cart; or order a digital + physical mix. Open **My orders** — the digital
part goes active immediately, the parcel arrives on its own with Helthjem tracking.

**⚠️ NOT merged — one open regression (C6):** `storefront_test.js` fails on the
bundle-checkout provisioning count (expects 5 provisioned products, gets 1). It's
a completion→provision timing interaction, not a broken core (`provision()` works
for simple orders; suite #88 is green). Details + diagnosis in the C6 log entry
below. **`main` stays frozen at 87/87 — nothing merged — so nothing is at risk.**
Finish before merge: fix this regression, run the FULL sweep, re-run the SOM unit
test. `fulfilment_test` (updated) and `bundle_test` pass.

**Honest simplifications:** a deferred item's backend service still activates
optimistically at order time (only its *item state* shows pending). Helthjem
access is gated to customers, so only the mock runs; the adapter is coded to the
seam. Per-item install completion rides the whole-order backstop.

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
| C3 | eSIM vs physical SIM checkout choice + routing | ✅ done + proven |
| C4 | 5b validation (proven) + fiber installs-not-ships + SOM test fix | ✅ done |
| C5 | storefront partial-progress + tracking view | ✅ built (order page) |
| C6 | suite #88 GREEN; fulfilment+bundle green; storefront_test regression (provisioning) OPEN; NOT merged | ⚠️ partial |

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

**C3 done — eSIM vs physical SIM, both offered at checkout.** The Cart shows a
SIM choice for any mobile line: **⚡ eSIM** (activates instantly, no parcel) or
**📦 Physical SIM** (delivered by Helthjem, tracked). The elegant part: the
difference IS place-or-no-place, which C1/C2 already route — eSIM line carries a
`simType=esim` characteristic and no place (→ completed instantly); physical SIM
carries `simType=physical` and is marked shipping (→ Helthjem parcel → completes
on delivery). `performCheckout` gained a `simType` param + `isMobileLine` helper;
Cart adds the toggle + folds physical-SIM into needsShipping; Orders labels
"⚡ eSIM active" vs "📦 On its way · HJ… (Helthjem)". **Proven E2E via API:**
eSIM order → completed instantly; physical-SIM order → SIM parcel booked
(HJ1989856722) → delivered ~15s → completed. Files: `checkout.js`, `Cart.jsx`,
`Orders.jsx`, `styles.css`. NOTE: backend mints the same ICCID for both (eSIM
also has an ICCID); the eSIM QR/activation-code artifact on My-page is polish.

**C4 done — order-time validation (5b) + fiber installs-not-ships + SOM test fix.**
5b: verified the existing cardinality gate already rejects an under-configured
bundle at ORDER TIME — ordering "GenAlpha One Home & Mobile" with no phone →
HTTP 400 "'Choose your phone' requires exactly 1 selection(s), but 0 were made".
So the earlier stuck order was a pre-validation artifact; the safety net is in
place (storefront client-side prevention = optional polish). Fiber/broadband:
`FulfilmentEventListener.installsRatherThanShips()` excludes install lines from
the carrier parcel — a fiber's place is the ENGINEER's address (workOrder), not a
shipping address, so it no longer gets wrongly booked as a Helthjem parcel; it
completes via the install/workOrder path + whole-order backstop. Updated SOM
`OrchestrationTest` to assert per-item `updateItemState(...,completed)` instead of
the old whole-order `complete()`. Files: `FulfilmentEventListener.java`,
`OrchestrationTest.java`. NOTE: per-item install completion (workOrder → complete
the specific fiber item) still rides the whole-order backstop; a fuller version
threads the fiber item id onto the workOrder — follow-up.

**C6 (partial) — acceptance suite green, one regression open, NOT merged.**
- **Suite #88 `track_c_test.js` — GREEN.** Proves the whole arc end-to-end:
  independent per-component fulfillment, Helthjem booking + auto-delivery,
  eSIM vs physical SIM, 5b order-time validation. This is the acceptance test.
- **Regression probe** of the most-affected existing suites:
  - `fulfilment_test.js` — was RED (asserted newborn shipping order
    `acknowledged`; C2 now books it → `inProgress` + Helthjem tracking). UPDATED
    to the new model (carrier books + auto-delivers; install-only fiber gates via
    workOrder). Now GREEN.
  - `bundle_test.js` — GREEN (no change needed).
  - `storefront_test.js` — **RED. OPEN REGRESSION.** After a bundle checkout
    (bundle ×2 + configured phone + solo), staff-completes the order and expects
    5 provisioned products on My page; gets 1. Diagnosis so far: the order is
    built correctly (log confirms bundle ×2 + configured chars) and `provision()`
    works in general (products ARE created for simple orders); NOT a JSON-column
    truncation (max item JSON 1375 < 4000). The fault is in the completion→
    provision interaction under per-item + carrier auto-delivery: the bundle
    order's completion timing changed, so `provision()` appears to run against an
    order that yields only the fallback product. NEEDS a focused trace of one
    bundle order across SOM/fulfilment/ordering logs. **Until fixed, do NOT merge
    to main.**
- **Full 87-suite sweep NOT run** (only the 3 highest-risk suites probed).
- **SOM `OrchestrationTest`** assertion updated (per-item), not re-run under
  Testcontainers.
- **main remains frozen at 87/87 — nothing merged.** The branch holds C0–C5 +
  #88, all committed.

### NEXT (to finish C6 → merge)
1. Fix the storefront_test provisioning regression (trace one bundle order; likely
   ensure `provision()` runs once with the full item set at final completion, and
   the auto-delivery/rollup doesn't complete-then-block it).
2. Run the FULL `ops/run-all-suites.sh` sweep; fix any further fallout.
3. Re-run SOM `OrchestrationTest`.
4. Merge `track-c-fulfillment` → `main` only when green.

**C6 continued — storefront regression RESOLVED (was a fragile test, not a bug).**
Root cause found via ground-truth probing: `provision()` is correct — the bundle
checkout provisions exactly 5 products (confirmed on the inventory API for the
real customer; sub == party-id so they're all "own"). My page actually renders
8 rows (5 products + 3 "My SIM" service line-rows). The old test asserted a raw
`.row === 5`, which only passed because the services fetch settled slower than
products; Track C shifted that timing, so the count raced (saw 1). FIX: assert
the 5 provisioned products on the inventory API (deterministic) + that My page
renders the bundle and its Samsung line. No product code changed. storefront_test
now GREEN. **Steps done:** (1) storefront regression fixed ✅; (3) SOM
`OrchestrationTest` re-run under Testcontainers — PASS (1/0/0) ✅. **Step (2) full
`ops/run-all-suites.sh` sweep RUNNING** (multi-hour on this fleet; results in
`ops/e2e/.proof-run/results.tsv`). Merge decision pending the sweep.
