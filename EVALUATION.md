# Evaluating genalpha-bss

A guide for teams doing a serious, hands-on evaluation: bring the full
platform up on your own infrastructure, walk the customer journeys, and
verify every claim against the test estate. Budget ~45 minutes from clone
to browsing the storefront, most of it waiting for containers.

Evaluation is free: all non-production use is permitted under the
[license](LICENSE), no keys, no registration, no phone-home. What you
stand up here is the same code that runs production — there is no
separate "community edition".

> **Prefer a guided first run?** We're happy to do the bring-up with you
> on a screen-share — you drive, we navigate, ~45 minutes, and you end
> with a running system and a map of the architecture. Ask us.

## 1 · Machine

The platform is deliberately honest about its shape: ~40 Spring Boot
services plus consoles, storefronts, an IdP, Kafka, Postgres and a fleet
of demo integrations — **~90 containers** in the full dev composition.

| | Minimum | Comfortable |
|---|---|---|
| Memory free for Docker | 16 GB | **24–32 GB** |
| CPU | 8 cores | 12+ cores |
| Disk | 40 GB | 60 GB |

A cloud VM is the smoothest path: AWS `m5.2xlarge` (8 vCPU / 32 GB),
GCP `e2-standard-8`, or Azure `D8s_v5`, running Ubuntu 22.04+ with
Docker Engine + compose v2. Laptops work if Docker's memory limit is
actually raised (Docker Desktop defaults are far too low).

Also needed on the host: **JDK 17, Maven, Python 3** (images copy
host-built jars — the build takes seconds, not the 30-minute in-container
alternative).

## 2 · Bring-up

```bash
git clone https://github.com/kundanvarma/genalpha-bss.git && cd genalpha-bss

mvn -q package -DskipTests      # host-built jars (seconds)
docker compose build            # image assembly (fast, thin layers)
docker compose up -d            # the fleet; first boot pulls base images
sleep 300                       # let ~90 containers reach steady state
docker compose up -d            # second pass catches any boot-order stragglers
```

Readiness check — both must return 200 before seeding:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8085/realms/bss/.well-known/openid-configuration
curl -s -o /dev/null -w '%{http_code}\n' "http://localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=1"
```

Then load the demo estate (idempotent; order matters):

```bash
for s in seed_genalpha_one reshape_bundle link_prices seed_stock \
         seed_serviceable_areas seed_coverage_map seed_usage_allowances seed_agreement_terms \
         seed_promotions seed_resource_pools seed_ai_slice seed_verified_identity seed_nova \
         seed_catalog_taxonomy seed_family_max seed_vas seed_content \
         seed_device_content seed_color_pricing seed_ocs_charging; do python3 ops/seed/$s.py; done
```

**One known first-boot wrinkle** (identity warms up last): if API calls
return 401/500 in the first minutes after the very first boot, restart
the machine-identity services once and give them a minute — this is a
token-cache warmup, not a fault:

```bash
docker restart bss-user-roles && sleep 25 && docker restart bss-product-ordering bss-som
```

## 3 · The tour (first 30 minutes)

Full persona table with every login is in the
[README Quickstart](README.md#quickstart). The evaluation-optimal path:

1. **Guest shopping** — http://localhost:8080/shop/ : browse the
   LOB-tabbed catalog, configure the *GenAlpha One Home & Mobile* bundle
   (pick a phone, pick a colour — colour-conditioned pricing is live).
2. **Buy it** — self-register, checkout (card `4242 4242 4242 4242`,
   promo `WELCOME10`, fiber postcodes start `111`/`222`/`333`). Watch
   per-component fulfilment: digital services activate instantly, the
   physical SIM ships via the carrier mock and goes live on delivery.
3. **The operator's side** — http://localhost:8080/console/ (`demo`/`demo`):
   catalog, orders, billing, audiences. Then the CSR desk —
   http://localhost:8080/csr/ (`agent-anna`/`agent`) — find your customer.
4. **Multi-tenancy** — http://shop.nova.localhost:8080/shop/ : a second
   operator, own realm, own catalog, own language (Norwegian), same
   deployment. Onboarding another is a form in the admin console
   (Operators tab), not a project.
5. **B2B** — http://localhost:8080/biz/ (`bianca@acme.example`/`bianca`):
   org, members, consolidated invoicing; the member mobile view at
   `/app/` (`emil@acme.example`/`emil`).
6. **Wholesale** — http://localhost:8080/partner/ (`demo`/`demo`):
   open-access fibre seeker desk; check Bergen `5020`, buy an L2 SKU,
   watch the order ride MEF Sonata cross-tenant to the fibre owner.

The 6-minute end-to-end film and guided demo links are at the top of the
README if you want the map before the territory.

## 4 · Verify the claims

The claims are only worth what you can check:

- **Unit + migration tests** (~250, real Postgres + RLS proofs):
  `mvn -q clean test -Dapi.version=1.44`
- **Browser/e2e contract suites** (200+, Apache-licensed — yours to keep
  and extend regardless of anything):
  ```bash
  cd ops/e2e && npm i playwright && npx playwright install chromium
  node guest_test.js          # a quick one
  node storefront_test.js     # the big retail journey
  bash ../run-all-suites.sh   # the full battery (hours; wants the 32GB machine)
  ```
- **TM Forum conformance**: [CTK scorecard](docs/ctk-conformance.md).

## 5 · What's mocked, what's real, what's optional

- **By design, external rails are seams with demo mocks**: PSPs
  (Klarna/Vipps/PayPal-shaped), carriers (Helthjem/Bring/PostNord-shaped),
  registries, e-invoice/mailbox rails, an ESP, social. Production swaps
  each mock for the real adapter behind the same interface — that seam
  architecture is the point; the mocks make the whole machine runnable
  on your laptop.
- **AI ships in deterministic stub mode** — zero keys, zero network, all
  AI features functional. Point it at a real model (local Ollama, OpenAI-
  compatible, or Claude) with two env vars — see
  [README → AI with a real model](README.md#ai-with-a-real-model-optional).
- **Skip for a first evaluation**: the `workforce` compose profile
  (autonomous AI workers — needs a worker image build and a model key;
  happy to demo it live instead), and Grafana/Prometheus if memory is
  tight.
- **Kubernetes**: a Helm chart under `deploy/helm` has run cluster soaks;
  the compose fleet above is the fastest evaluation path.

## 6 · Reading

- [Architecture](docs/architecture.md) — the component map, the event
  spine, multi-tenancy.
- [Operator's Manual](docs/manual/) — module-by-module operations.
- [LICENSING.md](LICENSING.md) — the deal in plain words: free in
  production under 20,000 subscribers, commercial above, every release
  Apache-2.0 two years after it ships.

Questions during the evaluation — architecture, scaling, integration
seams, roadmap — are welcome at any depth. That conversation is the part
we're best at.
