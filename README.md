# BSS on TM Forum ODA — Java / Spring Boot (production track)

Production-oriented Business Support System aligned to **TM Forum ODA**, implemented as Spring Boot microservices exposing **TMF Open APIs**. Inspired by **Discobole** (open, standards-based order-to-bill components) and **Totogi BSS Magic** (ontology-first, AI-generated BSS). Verified via **GitHub Actions CI** (build + test + Docker image).

## Status — all four services scaffolded
- ✅ **product-catalog (TMF620)** — catalog, category, productSpecification — port 8081
- ✅ **product-ordering (TMF622)** — productOrder — port 8082
- ✅ **product-inventory (TMF637)** — product — port 8083
- ✅ **party-account (TMF632 + TMF666)** — individual, organization, billingAccount — port 8084

All four share the same production template: Spring Boot 3.2, JPA/PostgreSQL (H2 for tests), DTO/mapper, validation, `@type` handling, `@RestControllerAdvice` errors, springdoc OpenAPI, a MockMvc test, and a multi-stage Dockerfile. `docker compose up --build` runs all four + Postgres + Keycloak. CI builds/tests/containers every service on each push.

## Stack
Java 17 · Spring Boot 3.2 · Spring Web · Spring Data JPA · PostgreSQL (H2 for tests) · springdoc-openapi · Maven · Docker · GitHub Actions.

## Run product-catalog locally
Requires JDK 17 + Maven, and a Postgres (or use Docker).
```bash
cd services/product-catalog
# with a local Postgres on 5432 (db: product_catalog / user: postgres / pass: postgres)
mvn spring-boot:run
# API base: http://localhost:8080/tmf-api/productCatalogManagement/v4
# OpenAPI UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health
```
Run tests (no DB needed — uses H2):
```bash
mvn -B verify
```
Build the container:
```bash
docker build -t bss/product-catalog .
docker run -p 8080:8080 -e DB_URL=jdbc:postgresql://host.docker.internal:5432/product_catalog bss/product-catalog
```

## CI
`.github/workflows/ci.yml` runs on every push/PR: sets up JDK 17, runs `mvn verify` (compile + tests), and builds the Docker image. Add a job per new service as they land. **CI is the source of truth for "it builds and passes"** — this is where the Java toolchain actually runs.

## Try the API
All endpoints require a JWT — see the **Security** section below for getting a token
from the bundled Keycloak. Everything goes through the gateway on port 8080:
```bash
curl -X POST localhost:8080/tmf-api/productCatalogManagement/v4/productOffering \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"5G Unlimited","lifecycleStatus":"Active","version":"1.0"}'

curl -H "Authorization: Bearer $TOKEN" \
  "localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?offset=0&limit=10"
```

## Roadmap
1. ✅ All four services scaffolded on the shared Spring Boot template.
2. ✅ Cross-service order-to-cash orchestration (order → inventory → billing account).
3. ✅ TMF688 domain events over Kafka.
4. ✅ OAuth2/OIDC resource-server security (vendor-neutral; Keycloak bundled for dev) + API gateway.
5. ✅ TM Forum Open API conformance — all five CTKs passing (TMF620/622/632/637/666).
6. ✅ Observability (Micrometer/Prometheus/Grafana) + infrastructure-as-code (Helm chart, EKS/AKS Terraform).

## Build status
All four services compile and their tests pass under JDK 17 + Maven 3.9, locally and
in CI (which also runs the Testcontainers Postgres and Kafka tests and builds every
Docker image). The full `docker compose up` stack — all seven containers — has been
exercised end to end manually (2026-07-09): Keycloak-issued user token, offering and
billing account created, order validated via machine-to-machine calls, completion
provisioning the product into inventory, and both TMF688 events consumed from Kafka.
That flow is verified but not yet automated in CI.

Local note: very new Docker daemons (29+) reject the API version Testcontainers'
docker-java opens with; run tests with `-Dapi.version=1.44` if containers report
"client version 1.32 is too old".

## Schema management
Each service owns a private database and applies its own **Flyway** migrations from
`src/main/resources/db/migration` at startup. Hibernate runs with `ddl-auto: validate`,
so a mismatch between the JPA entities and the migrated schema fails startup instead of
silently altering tables. The test suite runs the same migrations against H2, which means
entity/schema drift is caught by CI.

Each service also has a `PostgresMigrationTest` that runs the migrations against a real
PostgreSQL 16 via Testcontainers, loading the production config rather than the H2 test
profile. It is skipped when Docker is unavailable and runs in CI, so the schema is
verified on the engine it actually deploys against — not only on H2.

To add a column: write a new `V2__*.sql` alongside the entity change. Never edit an
applied migration — Flyway checksums them.

If you ran an older revision that used the shared `bss` database, reset the volume
before starting: `docker compose down -v`.

## Pagination
Every list endpoint takes TMF630-style `offset` (default 0) and `limit` (default 20,
max 100) query params and returns the slice ordered by id, plus `X-Total-Count`
(total rows) and `X-Result-Count` (rows in this response) headers. Invalid values
produce a 400 with the TMF error body.
```bash
curl -i "localhost:8081/tmf-api/productCatalogManagement/v4/productOffering?offset=20&limit=10"
```

## Building
An aggregator POM at the repo root builds and tests everything in one command:
```bash
mvn -B verify
```
Each service also remains independently buildable from its own directory.

## Container images
Each Dockerfile is multi-stage: the Spring Boot jar is exploded into layers
(dependencies / loader / snapshot-dependencies / application, least- to most-volatile)
so a code-only change rebuilds a single layer. The runtime stage runs as a non-root
user (uid 1001, compatible with Kubernetes `runAsNonRoot`), declares a `HEALTHCHECK`
against `/actuator/health`, sizes the heap with `-XX:MaxRAMPercentage=75`, and execs
the JVM as PID 1 so SIGTERM reaches it for graceful shutdown.

## Security
Every service is a pure **OAuth2 resource server**: it never performs login or issues
tokens — it validates JWTs from whatever OIDC issuer `OIDC_ISSUER_URI` points at.
Any spec-compliant provider works unchanged (Keycloak, IdentityServer/Duende,
Ping Identity, Okta, Entra ID, Auth0, Cognito); the provider is deployment
configuration, not a code dependency.

Authorization is scope-based and coarse: reads require `<service>:read`
(`catalog:read`, `ordering:read`, `inventory:read`, `party:read`), writes
(POST/PATCH/DELETE) require the matching `<service>:write`. `/actuator/health` and
the OpenAPI docs stay open for probes and discovery. Providers differ in which claim
carries permissions (`scope`, `scp`, `realm_access.roles`, ...), so the claim paths are
configurable via `bss.security.authority-claims` — switching identity providers is
configuration, not code.

`docker compose up` includes a **Keycloak** (port 8085) with a pre-provisioned `bss`
realm and a `demo`/`demo` user holding all scopes — a working secured system out of
the box, no cloud account needed. Try it:
```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=bss-demo \
  -d username=demo -d password=demo \
  http://localhost:8085/realms/bss/protocol/openid-connect/token | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" \
  "localhost:8081/tmf-api/productCatalogManagement/v4/productOffering"
```
Without a token the same request returns 401; with a token lacking `catalog:write`,
POST returns 403. Tests fabricate JWTs via `spring-security-test`, so the full
401/403/200 matrix runs in CI with no identity provider.

## API gateway
`services/gateway` (Spring Cloud Gateway) is the single entry point, on port **8080**
in compose. It routes by TMF API path to the owning service — no path rewriting,
since every service serves its full TMF path:

| Path prefix | Service |
|---|---|
| `/tmf-api/productCatalogManagement/**` | product-catalog |
| `/tmf-api/productOrderingManagement/**` | product-ordering |
| `/tmf-api/productInventoryManagement/**` | product-inventory |
| `/tmf-api/party/**`, `/tmf-api/accountManagement/**` | party-account |

The gateway forwards `Authorization` headers untouched: the services stay the
authorization enforcement point, so the gateway adds a front door without a second
security implementation to keep in sync. Downstream locations come from
`CATALOG_URL` / `ORDERING_URL` / `INVENTORY_URL` / `PARTY_URL`. Direct service
ports (8081–8084) remain exposed in compose for debugging.

## Order-to-cash orchestration
`product-ordering` orchestrates the order lifecycle across the other services,
synchronously over their TMF APIs:

- **Creation** validates references: an order pointing at a `productOfferingId`
  unknown to the catalog, or a `billingAccountId` unknown to party-account, is
  rejected with 400. Orders without references are accepted untouched.
- **Completion** (`PATCH {"state": "completed"}`) provisions the ordered product
  into product-inventory (status `active`, carrying the offering and billing
  account references). Provisioning runs inside the order transaction: if
  inventory fails, the response is 502 and the order stays in its previous state.
- `completed` and `cancelled` are terminal — further changes are rejected, so an
  order can never provision twice.

Service-to-service calls authenticate via **OAuth2 client credentials**: the
ordering service has its own machine identity (`bss-ordering` in the dev realm)
whose scopes are exactly what orchestration needs — `catalog:read`, `party:read`,
`inventory:write` — so the API security model applies to machines the same way it
applies to users. Downstream locations come from `CATALOG_BASE_URL`,
`PARTY_BASE_URL`, and `INVENTORY_BASE_URL`; the token endpoint from
`OIDC_TOKEN_URI` (deliberately not issuer discovery, so no IdP is needed at
startup). The dev client secret in the bundled realm is for local use only —
override `OIDC_CLIENT_SECRET` in any real deployment.

## Domain events (TMF688)
Every resource mutation publishes a TMF688-style event to Kafka — per-service
topics `bss.{catalog,ordering,inventory,party}.events`, envelope
`{eventId, eventTime, eventType, event: {<resource>: {...}}}`. Event types follow
TMF naming: `<Resource>CreateEvent`, `<Resource>AttributeValueChangeEvent`,
`<Resource>DeleteEvent`, and `ProductOrderStateChangeEvent` on order state
transitions.

Delivery semantics, honestly stated: events publish **after the database
transaction commits** (no phantom events from rolled-back changes) and are
**best-effort** — a dead broker degrades to a logged warning, never a failed API
request. Exactly-once/at-least-once delivery would require a transactional
outbox; that is a known, deliberate gap. `docker compose up` includes a
single-node Kafka (KRaft); tests run against a no-op publisher for speed, event
emission is verified at the publisher boundary per service, and one
Testcontainers test in product-ordering asserts a real envelope lands on a real
broker.

Watch the stream:
```bash
docker exec -it bss-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic bss.ordering.events --from-beginning
```

## Observability
Every service (gateway included) exposes Prometheus metrics at
`/actuator/prometheus`, tagged with `application=<service>` — JVM, HTTP server,
datasource, and Kafka producer metrics out of the box, plus two business counters
from the event pipeline:

- `bss_events_published_total{event_type=...}` — TMF688 events that reached Kafka
- `bss_events_failed_total{event_type=...}` — events lost to broker failures

The failed counter matters: event publishing is deliberately best-effort (a dead
broker never fails an API request), so this metric is where those losses become
visible. Alert on it.

`docker compose up` includes **Prometheus** (port 9090) scraping all five services
every 10s, and **Grafana** (port 3000, admin/admin) with the Prometheus datasource
pre-provisioned — import dashboard `4701` (JVM Micrometer) for an instant overview.
The scrape endpoint is deliberately unauthenticated, like health; in production,
keep the actuator port network-internal.

## TM Forum conformance (CTK)
The official TM Forum Conformance Test Kits are public at
[github.com/tmforum-rand](https://github.com/tmforum-rand). **All five kits pass
with zero failures** against this stack, run through the gateway with a real
Keycloak token (191 requests, 2,062 assertions in total):

| CTK | API | Requests | Assertions |
|---|---|---|---|
| TMF620 v4.0.0 | Product Catalog | 47 | 396 |
| TMF622 v4.0.0 | Product Ordering | 9 | 63 |
| TMF632 v4.0.0 | Party | 29 | 320 |
| TMF637 v4.0.0 | Product Inventory | 8 | 85 |
| TMF666 v4.0.0 | Account | 98 | 1,198 |

What conformance required beyond the original scaffold: spec-mandatory
sub-resources stored and echoed verbatim (`productOrderItem`, `relatedParty`,
nested `productOffering`/`billingAccount` references, `productCharacteristic`,
`productPrice`); server-set fields (`state`, `lastUpdate`, `lifecycleStatus`
defaults); TMF630 attribute filtering and `?fields=` selection on every tested
resource (unknown filter attributes are rejected with 400); new spec resources
(`productOfferingPrice`, `billFormat`, `partyAccount`,
`billingCycleSpecification`, `financialAccount`, `settlementAccount`,
`billPresentationMedia`); the spec base path `/tmf-api/productInventory/v4`;
and an Organization without a `name` (the spec requires only `tradingName`).
The TMF637 kit creates nothing — it audits existing inventory, so seed at
least two spec-shaped products before running it.

To reproduce: clone the CTK, point `config.json` at
`http://localhost:8080/tmf-api/productOrderingManagement/v4/`, add an
`Authorization: Bearer <token>` header (see Security), start from a clean
database (`docker compose down -v && docker compose up -d`), and run the kit
with Node 16. Formal certification is a TM Forum program; the kit passing is
the technical prerequisite.

## Deploying (Helm + Terraform)
`deploy/helm/genalpha-bss` is a Helm chart installing all five services with
readiness/liveness probes on `/actuator/health`, non-root security contexts
(uid 1001), and — for local/dev clusters — in-cluster PostgreSQL, Kafka, and
Keycloak. The same chart runs unchanged on any conformant cluster:

```bash
helm install bss deploy/helm/genalpha-bss --namespace bss --create-namespace
```

On cloud, disable the in-cluster dependencies and point the values at managed
services (`config.dbHost`, `config.kafkaBootstrapServers`,
`config.oidcIssuerUri`, `image.prefix` for your registry). The vendor-neutral
service design means every environment difference is a values entry, not a
code change — Azure Event Hubs speaks the Kafka protocol, any OIDC issuer
works, any PostgreSQL works.

`deploy/terraform/aws` provisions EKS + RDS PostgreSQL; `deploy/terraform/azure`
provisions AKS + Azure Database for PostgreSQL Flexible Server (per-service
databases included). Both are Terraform-compatible HCL (validated with
OpenTofu) and end by handing values to the same chart — see the header comment
in each `main.tf` for the post-apply `helm install`.

Verified on a local Kubernetes (k3s): `helm install` brings up all eight pods,
and the full order-to-cash flow passes — token from in-cluster Keycloak,
gateway routing, M2M reference validation, inventory provisioning, and both
TMF688 events consumed from in-cluster Kafka.

## License
[Apache License 2.0](LICENSE) — aligned with TM Forum's own Open API assets and
chosen for its explicit patent grant, which matters in a standards-heavy telecom
domain.
