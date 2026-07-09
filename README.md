# BSS on TM Forum ODA — Java / Spring Boot (production track)

Production-oriented Business Support System aligned to **TM Forum ODA**, implemented as Spring Boot microservices exposing **TMF Open APIs**. Inspired by **Discobole** (open, standards-based order-to-bill components) and **Totogi BSS Magic** (ontology-first, AI-generated BSS). Verified via **GitHub Actions CI** (build + test + Docker image).

## Status — all four services scaffolded
- ✅ **product-catalog (TMF620)** — catalog, category, productSpecification — port 8081
- ✅ **product-ordering (TMF622)** — productOrder — port 8082
- ✅ **product-inventory (TMF637)** — product — port 8083
- ✅ **party-account (TMF632 + TMF666)** — individual, organization, billingAccount — port 8084

All four share the same production template: Spring Boot 3.2, JPA/PostgreSQL (H2 for tests), DTO/mapper, validation, `@type` handling, `@RestControllerAdvice` errors, springdoc OpenAPI, a MockMvc test, and a multi-stage Dockerfile. `docker compose up --build` runs all four + Postgres. CI builds/tests/containers every service on each push.

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
```bash
curl -X POST localhost:8080/tmf-api/productCatalogManagement/v4/productOffering \
  -H "Content-Type: application/json" \
  -d '{"name":"5G Unlimited","lifecycleStatus":"Active","version":"1.0"}'

curl localhost:8080/tmf-api/productCatalogManagement/v4/productOffering
```

## Roadmap (see ../bss-oda/PROJECT_NOTES.md for the full backlog)
1. ✅ All four services scaffolded on the shared Spring Boot template.
2. Cross-service order-to-cash orchestration (order → inventory → billing account).
3. TMF688 domain events over Kafka.
4. API gateway + OAuth2/OIDC.
5. TM Forum Open API conformance (CTK).
6. Observability (Micrometer/Prometheus) + infrastructure-as-code for the chosen deploy target.

## Note on how this was built
Service code generated with Claude Fable and reviewed for correctness. Because the authoring sandbox has no Java 17/Maven/Docker, compilation and tests are validated by CI, not locally — which is exactly what the GitHub Actions pipeline is for.
