# genalpha-bss — a composable, multi-tenant BSS on TM Forum ODA

A vendor-neutral telecom **Business Support System** built as **24 TM Forum ODA components**
(Spring Boot microservices exposing TMF Open APIs) plus **three web channels**, behind one API
gateway. Any OIDC identity provider, any PostgreSQL, any Kafka-protocol broker — nothing
operator-specific is hardcoded. Two demo operators run side by side on a single deployment to
prove it.

**Every feature is verified end-to-end in a real browser** — six Playwright suites drive the
storefront, guest checkout, both consoles, tenant isolation and role administration against the
full stack. The original five core components also pass the official TM Forum CTKs with zero
failures.

- 📐 **[Architecture views](docs/architecture.md)** — component map, tenancy model, order-to-bill flow, event backbone
- 🧩 **[ODA Composer](docs/composer.html)** — pick the modules a deployment needs; dependencies enforced; output is a Helm values override

## The modules

**Core commerce (always deployed)**

| Component | TMF API | Port | What it does |
|---|---|---|---|
| product-catalog | TMF620 | 8081 | Offerings, prices, bundles, commitment terms |
| product-ordering | TMF622 | 8082 | Order capture, validation, completion orchestration |
| product-inventory | TMF637 | 8083 | What each customer has, provisioned per order item |
| party-account | TMF632 / TMF666 / TMF669 | 8084 | Individuals, organizations, accounts, party roles |
| gateway | ODA exposure | 8080 | Single entry point; white-label host → tenant routing |

**Optional components** (leave any out via the [composer](docs/composer.html) — channels adapt)

| Component | TMF API | Port | What it does |
|---|---|---|---|
| product-stock | TMF687 | 8086 | Device shelf: reserve at order, consume at completion |
| payment | TMF676 | 8087 | Authorize/capture behind a PSP adapter (mock PSP in dev) |
| billing | TMF678 | 8088 | Billing runs: recurring + usage + discounts on one bill |
| qualification | TMF679 | 8089 | Serviceability: where fiber-class offerings can be delivered |
| appointment | TMF646 | 8091 | Installer slots, booked at checkout |
| trouble-ticket | TMF621 | 8092 | Support cases, org-scoped for partner agents |
| party-interaction | TMF683 | 8093 | Every touchpoint on the customer timeline |
| communication | TMF681 | 8095 | Event-driven notifications (the martech door) |
| shopping-cart | TMF663 | 8096 | Server-side carts: guest secret-id, claim on login, abandonment events |
| usage | TMF635 / TMF677 | 8097 | Mediation intake, rating, allowance meters, overage charges |
| agreement | TMF651 | 8098 | Commitment periods minted automatically at order completion |
| promotion | TMF671 | 8099 | Promo codes: anonymous validation → redemption → bill discount |
| user-roles | TMF672 | 8100 | Tenant admins manage staff via TMF API over their own IdP |
| geographic-address | TMF673 | 8101 | Address validation + standardization at checkout |
| recommendation | TMF680 | 8102 | Cross-sell: what this customer lacks, bundles first |
| payment-method | TMF670 | 8103 | Tokenized card vault: save at checkout, pay bills one-click |

**Production (OSS)** — the layer below the BSS, thin but real

| Component | TMF API | Port | What it does |
|---|---|---|---|
| service-orchestration | TMF641 / TMF638 / TMF640 / TMF685 | 8104 | Digital orders decompose, activate (drawing MSISDNs from resource pools) and complete themselves |
| assurance | TMF642 / TMF656 | 8105 | Critical alarms become service problems; the CSR console shows live outages |

**Channels** — one build each, white-labeled per tenant by hostname

| Channel | Path | For |
|---|---|---|
| storefront | `/shop` | Self-service: guest browse → configure → cart → checkout → bills → support (React + Vite PWA) |
| csr-console | `/csr` | Assisted service: customer 360, ticket queue, org-scoped agents |
| admin-console | `/console` | Back office: catalog and stock |

## Multitenancy (pool model, hardened)

Two operators — **GenAlpha** (`localhost`) and **Nova Telecom** (`*.nova.localhost`) — share one
deployment:

- **Identity**: tenant = verified OIDC issuer. Each tenant is a Keycloak realm in dev; a Cognito
  pool or Entra tenant works identically in production. Machine-to-machine calls use the *acting
  tenant's* credentials.
- **Data**: `tenant_id` on every domain row, enforced twice — in code on every query, and by
  **PostgreSQL Row-Level Security**: services run as restricted roles that see zero rows without
  a session tenant, even for SQL with no predicate at all.
- **Anonymous traffic**: the gateway maps hostname → tenant, so each operator's storefront,
  CSR console and admin console show only their world. Cross-tenant access reads as 404 everywhere.

## Quickstart

Prereqs: JDK 17, Maven, Docker (with compose), ~8GB free memory for the full stack.

```bash
mvn -q package -DskipTests            # images use the host-built jars
docker compose build                  # seconds, not minutes
docker compose up -d                  # ~25 containers; wait for healthy

# demo data (idempotent; order matters) — see ops/README.md
for s in seed_genalpha_one reshape_bundle link_prices seed_stock \
         seed_serviceable_areas seed_usage_allowances seed_agreement_terms \
         seed_promotions seed_resource_pools seed_nova; do python3 ops/seed/$s.py; done
```

Then browse:

| URL | What |
|---|---|
| http://localhost:8080/shop/ | GenAlpha storefront (self-register, or browse as guest) |
| http://localhost:8080/csr/ | CSR console — `agent-anna` / `agent` |
| http://localhost:8080/console/ | Admin console — `demo` / `demo` |
| http://shop.nova.localhost:8080/shop/ | Nova Telecom's white-label storefront (own realm, own catalog) |

Demo cards: `4242 4242 4242 4242` pays, anything ending `0002` declines. Promo code: `WELCOME10`.
Serviceable fiber postcodes start with `111`, `222` or `333`.

## Verification

```bash
mvn -q clean test -Dapi.version=1.44        # ~250 tests incl. real-Postgres migrations + RLS proofs
cd ops/e2e && npm i playwright && npx playwright install chromium
node storefront_test.js && node guest_test.js && node console_test.js \
  && node csr_test.js && node tenant_test.js && node roles_test.js
```

The storefront suite alone walks ~40 assertions: register → configure a bundle (phone choice,
color, storage) → cart ×2 → serviceability gate → installer slot → pay → order → stock
consumption → payment capture → commitment agreement → usage meters → billing run with overage
and promo discount → pay the bill with the vaulted card → notifications — all through the UI.

## Deploying

`deploy/helm/genalpha-bss` carries the whole stack (both realms, per-service RLS roles, the
second tenant's issuers). Choose modules with the [composer](docs/composer.html):

```bash
helm install genalpha-bss deploy/helm/genalpha-bss -f my-modules.yaml
```

Terraform stacks for EKS and AKS live under `deploy/terraform`.

## Stack

Java 17 · Spring Boot 3.2 · Spring Security (multi-issuer resource server) · JPA/PostgreSQL
(+ RLS) · Flyway · Kafka (transactional outbox) · Keycloak 26 (dev IdP) · React + Vite ·
Playwright · Helm · Terraform · GitHub Actions.

## CI

`.github/workflows/ci.yml` builds and tests every service on each push. CI is the source of
truth for "it builds and passes".
