# The third operator in an afternoon — MVNO tenant onboarding (plan)

The claim to prove: standing up a NEW operator (an MVNO) on the running
deployment is an afternoon's configuration, not a project. Today genalpha
and nova are baked into every service's `application.yml` as two registry
entries with `${ENV}`/`${ENV_NOVA}` pairs — a third tenant would mean
editing ~30 yml files and rebuilding. That is the wall this arc removes.

## Design

1. **Tenants as a FILE, not a build**: one shared `infra/tenants/tenants.yml`
   defining `bss.tenants.registry` for ALL tenants, mounted read-only into
   every service at `/config/tenants.yml` and loaded via
   `spring.config.import: optional:file:/config/tenants.yml`. When the file
   is absent (unit tests, bare local runs) the built-in two-tenant yml
   stays authoritative — the file, when present, wins. Env vars still
   override single values (Spring property precedence puts env above
   config imports), so existing compose env keeps working.
   Onboarding = append a tenant block + restart the fleet (no rebuild).
   Zero-restart dynamic registries are a named follow-up, not this arc.
2. **One onboarding script**: `ops/onboard-tenant.sh <id> <name> <locale> <currency>`
   - kcadm: create realm (roles, bss-demo/bss-biz/machine clients + service
     accounts with the SAME roles as nova's — scripted from a template),
   - append the tenant block to `infra/tenants/tenants.yml`,
   - restart the fleet, wait for health,
   - seed: tenant manifest (logo/name/color/locale/currency via document
     store like nova's), a starter catalog (two offerings via TMF620),
     staff user.
3. **Hostname routing**: guest/white-label routing is hostname-driven
   (`shop.nova.localhost`); the new tenant gets `shop.<id>.localhost` —
   verify the manifest lookup is registry-driven, not hardcoded.
4. **Proof (suite #49, third_operator_test.js)**: run the onboarding for
   a run-unique tenant "fjord"; then WITHOUT any rebuild: staff signs in,
   a customer registers on the fjord storefront hostname, buys the seeded
   plan, service activates, a bill cuts in fjord's currency — and
   genalpha/nova still pass their isolation checks (a fjord token reads
   nothing of theirs). The suite asserts the afternoon: wall-clock from
   script start to first activated service.

## Open questions to resolve while building

- Which services read tenant config beyond the registry (channel manifest
  in document store — seeded, fine; Kafka topics are shared — fine).
- Keycloak realm template: export nova's realm to a template with
  placeholders (client secrets per tenant!).
- The `default-tenant` and pre-tenancy fallbacks stay genalpha.

## Answered along the way (dealer questions)

- The dealer console UI lives at `/dealer-app/` (gateway route,
  bss-biz OIDC client) — now linked from the README channels table.
- INTERNAL retail stores: same rails, deliberately. Norway's
  kassasystemlova puts fiscal duties (certified cash register, journal,
  Z-reports, receipts) on the POS LAYER — the BSS must not be a cash
  register. Own stores run a certified POS integrated via the same
  /dealer/v1 machine API (an agreement row for the operator's own retail
  org, commission 0 or internal transfer pricing); subscriptions and SIMs
  flow through the BSS as today, in-store cash/card stays in the POS's
  fiscal domain, and the BSS order is the fulfilment record.
