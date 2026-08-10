# Track C — overnight build progress

Branch `track-c-fulfillment` (off `main` @ 87/87, which stays frozen). Live log,
newest at top. Plan: `docs/track-c-fulfillment-plan.md`.

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
| C1 | independent activation timing (digital now, physical/fiber on tracks) | — |
| C2 | Helthjem logistics seam + mock-logistics + ship-per-item + delivery completes item | — |
| C3 | eSIM vs physical SIM checkout choice + ICCID↔MSISDN dispatch gate | — |
| C4 | fiber install track completes its item + order-time validation (5b) | — |
| C5 | storefront partial-progress + tracking view | — |
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
