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

## Build findings (milestone 1, in progress)

- 31 services carry a `bss.tenants.registry`; 14 of them embed their
  MACHINE identity (machine-client-id/secret) as yml DEFAULTS — compose
  sets no OIDC_CLIENT_ID anywhere. Spring config imports replace list
  properties WHOLESALE, so the shared file must be the UNION of all
  per-tenant fields (unknown fields are ignored by binding) with
  `${ENV:default}` placeholders — and the machine identity must move to
  per-service compose env (`OIDC_CLIENT_ID`/`OIDC_CLIENT_SECRET`, values
  scripted out of each service's current yml defaults) so the shared
  placeholders resolve per container exactly as today.
- The compose sweep is: per Java service, add SPRING_CONFIG_IMPORT env,
  the OIDC machine env pair, and the `./infra/tenants:/config:ro` mount.
- `infra/tenants/tenants.yml` drafted (billing-shaped; needs the union
  of esp/bank/distribution/pim fields from communication, catalog et al).

## Shipped

`ops/onboard-tenant.sh <id> "<Name>" <locale> <currency> [#color]` does
the afternoon: (1) clones nova's realm shape — 21 clients, all roles and
machine service accounts, personas dropped, object UUIDs stripped so the
clone mints its own; (2) appends the tenant block to the shared registry
(idempotent; backchannel jwks/token URIs pinned to keycloak:8080 — the
empty-placeholder default tried browser-host discovery from inside the
containers, found the hard way); (3) restarts the Java fleet (config
only); (4) seeds a staff-token catalog in the operator's currency.
Suite #49 (third_operator_test.js) proves it: operator "fjord" (Fjord
Mobil, da/DKK) went realm→registry→catalog in ~2 minutes, its first
customer activated with MSISDN + SIM and was billed a PRORATED 128.52
DKK of the 249/mo plan — the newborn operator inherits honest billing —
and the row-level walls hold in both directions against genalpha.

## Operator-as-a-form + zero-restart + white-label (shipped)

- **Zero-restart**: every registry-bearing service (31) carries a
  generated `TenantFileRefresher` — its own daemon executor (no
  @EnableScheduling needed), snakeyaml re-read of /config/tenants.yml,
  ${ENV:default} resolution matching Spring's, reflection binding onto
  whatever fields THIS service's TenantEntry knows. NEW tenants join the
  running fleet within one interval; changes to existing tenants remain
  restart-territory (deliberate). The lazy issuer resolvers make security
  follow the registry for free.
- **Operator-as-a-form**: POST /onboarding/v1/operator (user-roles, which
  already owns identity admin) — master-admin realm clone from the
  mounted nova template, tenant block appended to the WRITABLE registry
  mount, own registry refreshed inline, and the seeder WAITS for the
  fleet to honor the newborn's first token before seeding the catalog
  (the race found live). Guarded twice: roles:admin + host-tenant-only.
  Console: an Operators tab — five fields, one save.
- **White-label**: free — the gateway serves /app/tenant-config.json by
  Host from its own live registry, so shop.<id>.localhost wears the new
  brand, locale, currency and color the moment the gateway refreshes.
- Suite #50 (operator_form_test.js): nova's admin is REFUSED (only the
  host mints); five form fields make "Aurora Tele" (sv/SEK); the fleet
  honors aurora's first token with zero restarts; the manifest wears the
  brand; Astrid buys the seeded plan and activates.

## Live mutation of existing tenants (shipped — the last follow-up)

The refreshers now UPDATE serving tenants in place: any field changed in
the shared file follows within one interval — seams, brand, hosts —
except identity (`id`, `issuer`, key endpoints), which stays
restart-territory because the security resolvers cache per issuer; the
boundary is enforced in code, not wished away. The console's Operators
tab edits form-born operators (PATCH /onboarding/v1/operator/{id});
SEED operators (genalpha, nova — the protected list) refuse the form:
their config is env. Two live-fire findings: a failed suite run briefly
renamed nova via the unguarded endpoint and the refreshers HEALED it
fleet-wide the moment the file was corrected — live mutation cutting
both ways; and re-onboarding a realm invalidates cached machine tokens,
so onboarding now evicts them (IdpAdminClient.evictTokens — a seam
method, so the mock IdP in tests simply ignores it).

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
