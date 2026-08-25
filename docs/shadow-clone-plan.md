# The shadow-operator clone — simulate by running the real thing

**Status:** SC-P1..P3 SHIPPED (2026-08-25) · **Depends on:** operator-as-a-form onboarding, deterministic pricing/billing engines, the tenant fleet file

## Thesis
Every commercial simulator in the industry is a *model* of the billing system —
a re-implementation that drifts. Here, tenants are cheap (a form, ~20s), so
"simulate" means: **clone the operator as a real sandbox tenant, mutate the
hypothesis inside the clone, run the same engines that cut real bills, read
the answer off the clone, throw the clone away.** Full fidelity by
construction; zero model drift.

## Shipped
- **SC-P1 the clone**: `POST /onboarding/v1/operator/{sourceId}/clone {id}` mints
  a sandbox operator (same onboarding machinery), stamps `sandbox: "true"` into
  its tenant block, and copies the source's catalog (categories → specs →
  prices → offerings, bundles last) and policy rules over the tenants' own
  staff tokens — ids remapped generically (any `{id:…}` reference anywhere in
  the payload follows the map). The fleet file is the registry; every service
  learns the newborn within one refresh.
- **SC-P2 the wall**: a sandbox tenant can never touch the outside world. The
  communication dispatcher (the email/SMS/push egress choke point) refuses
  delivery for `sandbox` tenants — the message stays in the in-app inbox with
  `deliveryStatus: sandbox-suppressed`, inspectable, never sent. The gateway
  manifest attests `sandbox: true` so every channel can badge it.
- **SC-P3 the answer**: `GET /portfolioDiff?tenantA&tenantB` (billing,
  `billing:admin`) prices BOTH portfolios with the same engine that cuts real
  bills (`monthlyFor`) and reports: matched offerings with per-name deltas,
  only-in-A / only-in-B, and the portfolio monthly totals — assumptions on its
  face, read-only.

Suite: `shadow_clone_test.js` — clones a live operator, proves the copy priced
identical, moves one price in the clone, reads exactly that delta off the diff,
proves the wall (an email in the sandbox is suppressed, not sent), deletes the
probe realm.

## Honest boundaries (follow-ups by trigger)
- ~~Subscriber-base seeding~~ **SHIPPED (2026-08-25)**:
  `POST /onboarding/v1/operator/{cloneId}/seedTwinBase {sourceId, count}` —
  the ONLY thing read from the source is the aggregate offering distribution
  (no name, email or id crosses); the clone mints proportional synthetic
  twins (`Tvilling …@twin.example`, fictional by construction, min 1 per
  offering so `count` is a scale target) each holding its product, and the
  REAL billing run bills them. Sandbox-only by guard — twins in production
  would be pollution. Proven in `shadow_clone_test` (45 twins, 40 bills).
- Wholesale rate-card copy + the remaining egress guards (payment PSP, insight
  GA4/ad destinations, social publish) — same one-line registry-gate pattern as
  the communication wall; communication is the highest-stakes egress and is the
  one proven here.
- Time compression (a simulated quarter) — billing runs are already on-demand;
  the loop is an orchestration, not an engine change.
