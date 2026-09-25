# genalpha-bss — working instructions

A vendor-neutral, multi-tenant, AI-native telecom BSS on TM Forum ODA. Forty
Spring Boot components (one database and one Kafka topic each) behind one
gateway, seven channels, an operational ontology, a decision log. Proof is a
numbered browser suite per feature. Read this file first; the rules that
matter are enforced by tools (see *Checks*), not by this prose.

## Where things live

- `services/<component>/` — one Spring Boot service per ODA component (Java 21 source on a Java 25 runtime image, Maven, Flyway). `src/main/resources/db/migration` and `db/migration-postgresql` **share one version space**.
- `apps/` — channels: `storefront`, `mobile`, `csr-console` (React); `admin-console`, `business-console`, `dealer-console`, `partner-console` (vanilla JS, being migrated — see conventions).
- `ontology/` — the registry YAML: concepts, capabilities, governed actions, agents. `services/ontology` serves it; `ops/ontology/gen-sdk.mjs` regenerates the SDK.
- `ops/e2e/` — the browser suites (Playwright, `node <name>_test.js`), `ops/ctk/` — TM Forum conformance kits, `ops/seed/` — demo data, `ops/cloud/aws-demo/` — the hosted demo box.
- `docs/` — one document per arc, each ending in an **Honest limits** section; `docs/adr/` — decision records; `docs/engineering-conventions.md` — the rules.
- `infra/` — Keycloak realms, brand assets; `deploy/helm` — the chart; `docker-compose.yml` — the laptop fleet.

## Build, run, prove

```bash
cd services/<x> && mvn -q clean package -DskipTests      # no mvnw; needs a JDK 21+ (java.version 21 — a JDK 17 fails with "release version 21 not supported"). On this laptop /usr/libexec/java_home knows no JDK; use brew's: export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
docker compose build <x> && docker compose up -d --no-deps --force-recreate <x>
cd ops/e2e && node <suite>_test.js                        # one suite at a time on the laptop
bash ops/run-all-suites.sh                                # ~40 min, writes ops/e2e/.proof-run/
ops/arch/ratchet.sh                                       # architecture ratchet (also a pre-commit + edit hook)
ops/arch/claims.sh                                        # claims gate: the prose must match the code
```

- The **Testcontainers** tests (`PostgresMigrationTest`, 29 components — the only ones that meet real Postgres) skip silently on this laptop unless Colima is spelled out. They pass in CI, where Docker is native:

```bash
DOCKER_HOST=unix://$HOME/.colima/default/docker.sock TESTCONTAINERS_RYUK_DISABLED=true \
  mvn -B verify -DargLine="-Dapi.version=1.44"
```

  `api.version` must be a JVM system property — the `DOCKER_API_VERSION` env var is ignored and the client negotiates 1.32, which Colima refuses. Ryuk (the reaper) cannot start here, and without it disabled every test is "skipped: Docker is not available", which reads exactly like a pass.
- Gateway `http://localhost:8080`, Keycloak `http://localhost:8085` realm `bss`; staff `demo/demo`, product `pat@bss.local/pat`, customer `paula@family.example/paula`.
- `docker compose build -q` hides failures: check the `Built` line or the container's created time.
- Flyway "more than one migration with version N" = stale `target/` → `mvn clean`, and remember the two migration folders share numbers.
- Run browser suites **one at a time**; parallel suites plus rebuilds starve the laptop and Kafka restarts. Never build or run suites during a demo.
- macOS has no `timeout`; `docker compose stop` with fifty names stalls — stop containers one at a time.

## Definition of done for an arc

1. A numbered browser suite in `ops/e2e` proves the behaviour end to end, through the gateway, with a real token.
2. `docs/<arc>.md` says what was built, in operator language, and ends with **Honest limits**.
3. README rows and diagrams updated when components or suites change; the capability map when a capability lands.
4. Any domain-event change updates insight, campaign and bss-bridge consumers, then the martech sweep is rerun.
5. The architecture ratchet is green: no new untyped public returns, no front-end file grew. The claims gate is green: every number and every "enforced" in the README is still true.
6. Screens speak operator language — names, never keys or UUIDs — and you looked at a screenshot before handing over.
7. Nothing goes to the hosted box without being asked; the deploy recipe is in `ops/cloud/aws-demo/README.md`.

## Rules with teeth

- **Standards are the contract.** TM Forum payloads stay standard on the wire; house fields ride beside them, never instead of them.
- **Typed core, open edge.** Records at service boundaries; `Map<String, Object>` only at the TM Forum wire behind a mapper, and only for characteristics and extensions. The ratchet counts public untyped returns per service and the count may only fall.
- **Every table carries `tenant_id` with a row-level-security migration.** Every write publishes through the outbox. Every front end sends `X-Channel`; agents send `X-GenAlpha-Agent`.
- **No business logic in a front end.** Channels ask; the catalog's configurator prices, policy decides, the ontology governs.
- **Seams for anything vendor-specific**, one adapter per vendor, a stand-in in the fleet, chosen per tenant in `tenants.yml`.
- **AI proposes, a human decides.** Copilots return a card; Create is a click. Agents act only through registered ontology actions with a receipt.
- **UI**: React with components under 300 lines for channels; no new vanilla-JS files; the vanilla consoles shrink desk by desk (see conventions). Vanilla consoles build DOM with `createElement`: true for admin-console (40 `innerHTML` uses remain); partner-console still renders with `innerHTML` in 14 places — a follow-up.
- **Prices are positive; discounts are rules.** Laws are data a tenant cannot undercut.
- **A claim is a promise the code keeps, or it is not written.** Every number in a document (suite counts, component counts, stack versions) is generated or checked, never typed from memory; every "off by default" is the *application* default, with demo opt-in in `docker-compose.yml` only; every "enforced in CI" names a workflow step that exists. `ops/arch/claims.sh` checks these and fails the build. Adding a fact to the README means adding its check. This rule exists because an outside review found five stale claims at once — none of them a bug, and together worth more than a bug, because a claim that turns out to be stale makes every other claim suspect.
- **A gate you have never seen fail is not a gate.** Before trusting any check, break something on purpose and watch it go red. A script that prints failures and exits 0 reads exactly like a pass to everything downstream.

## Security with teeth

- **A request body is a record, never a raw map.** A record cannot carry a field it does not declare, so mass assignment is impossible by construction. The ratchet counts PATCH/PUT/POST handlers with a raw `Map` body; the count may only fall.
- **Tenant, owner and state come from the token and the store, never from the body.** Row-level security is the wall; the service is the door. A new table is unprotected by default, so `python3 ops/security/rls_check.py` proves the wall against the live catalogue, not the migrations: every table with a `tenant_id` has a policy, no runtime role is a superuser or owns a tenant table, and a session pinned to one tenant cannot read another's rows. Run it against a fleet that is up before calling an arc done — and it now also runs on every pull request against the smoke fleet (`Row-level security holds on the live database`, in `browser-proof.yml`), because the live catalogue is the only place this can be checked. It found eight unprotected tables the first time.
- **Tokens are validated in every component** (39 `SecurityConfig` copies), not at the gateway: the gateway stamps the tenant from the hostname and routes. A drift check across the copies is a follow-up.
- **CI runs CodeQL, dependency review, the architecture ratchet and the secret gate** (`.github/workflows/security.yml`); Dependabot keeps dependencies current. Every action is pinned to a commit SHA. Only a high-severity dependency finding blocks the merge (`fail-on-severity: high`); CodeQL findings appear as code-scanning alerts and block nothing until branch protection requires them — a follow-up. `ci.yml` packages all 42 modules and builds every image, but runs **tests** for five services only; turning the whole reactor's tests on needs one local proof run first, because several Testcontainers tests have never executed anywhere.
- **Security headers are set once, at the gateway** (`SecurityHeadersFilter`), because the browser only talks to the gateway and a component's own headers would never reach a page: a content policy that refuses inline script, nosniff, a frame rule, no referrer, and transport security only when the request actually arrived over TLS. The admin console alone gets `unsafe-eval` for its command palette; that permits eval, never inline script, so the `innerHTML` risk stays covered there too.
- **Every component has a threat model** in `docs/threat-model/`; touching a trust boundary means updating it.
- Secrets never enter the repo (`ops/scan-secrets.sh`, pre-commit on hooked clones and over the whole tree in CI). A tenant's machine credentials are its own: onboarding mints them per operator and carries no account across (`docs/tenant-onboarding-secrets.md`). Operator-supplied text is never interpolated into `tenants.yml` — it is validated and emitted as a quoted scalar, because that file governs every tenant's trust settings and a hosted operator's own team can reach the brand fields. Every prompt is redacted before it leaves the process — email, phone, IBAN, account, ICCID, IMEI, card, national id, labelled address — with reversible placeholders so the caller still sees real values; a tenant may opt out with `ai-raw-exposure`, and the ledger row says so. Names are not recognised. Every model call is metered and logged.

## Discretion and safety

- Never print or commit secrets. Keys live in `~/.hermes/.env` and the repo-root `.env` only.
- No prospect or customer names in the repo or docs. Public wording for the licence is "source-available", nothing more.
- Never commit report PDFs (`docs/*.pdf` is ignored; the operators' manual is the one tracked exception).
- Confirm before anything outward-facing or hard to reverse: deploys to the box, posts, emails, deletions.

## When starting work

Read `docs/engineering-conventions.md`, then the arc's doc if one exists, then the relevant ADRs in `docs/adr/`. Prefer a small verified step over a large one; run the suite that covers what you touched before moving on.

## Agent skills

The `mattpocock-skills` plugin is installed (decided 25 Sep 2026). Every new arc starts with `grill-me` (the design interview), then `to-spec` (the arc's spec as a GitHub issue), then `to-tickets` (tracer-bullet tickets); an arc too big for one session gets a `wayfinder` map. Building still answers to the ratchet, the claims gate and the numbered suites — those have teeth; the skills shape the planning half.

### Issue tracker

GitHub Issues on this repo, via `gh`. See `docs/agents/issue-tracker.md` — and remember the repo is public, so discretion rules apply to issues too.

### Triage labels

The five canonical labels, unchanged: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: the glossary is `CONTEXT.md` at the repo root (created lazily by `domain-modeling`), decisions are `docs/adr/`. See `docs/agents/domain.md`.
