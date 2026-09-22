# genalpha-bss — working instructions

A vendor-neutral, multi-tenant, AI-native telecom BSS on TM Forum ODA. Forty
Spring Boot components (one database and one Kafka topic each) behind one
gateway, seven channels, an operational ontology, a decision log. Proof is a
numbered browser suite per feature. Read this file first; the rules that
matter are enforced by tools (see *Checks*), not by this prose.

## Where things live

- `services/<component>/` — one Spring Boot service per ODA component (Java 17 source on a Java 25 runtime image, Maven, Flyway). `src/main/resources/db/migration` and `db/migration-postgresql` **share one version space**.
- `apps/` — channels: `storefront`, `mobile`, `csr-console` (React); `admin-console`, `business-console`, `dealer-console`, `partner-console` (vanilla JS, being migrated — see conventions).
- `ontology/` — the registry YAML: concepts, capabilities, governed actions, agents. `services/ontology` serves it; `ops/ontology/gen-sdk.mjs` regenerates the SDK.
- `ops/e2e/` — the browser suites (Playwright, `node <name>_test.js`), `ops/ctk/` — TM Forum conformance kits, `ops/seed/` — demo data, `ops/cloud/aws-demo/` — the hosted demo box.
- `docs/` — one document per arc, each ending in an **Honest limits** section; `docs/adr/` — decision records; `docs/engineering-conventions.md` — the rules.
- `infra/` — Keycloak realms, brand assets; `deploy/helm` — the chart; `docker-compose.yml` — the laptop fleet.

## Build, run, prove

```bash
cd services/<x> && mvn -q clean package -DskipTests      # no mvnw in this repo
docker compose build <x> && docker compose up -d --no-deps --force-recreate <x>
cd ops/e2e && node <suite>_test.js                        # one suite at a time on the laptop
bash ops/run-all-suites.sh                                # ~40 min, writes ops/e2e/.proof-run/
ops/arch/ratchet.sh                                       # architecture ratchet (also a pre-commit + edit hook)
```

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
5. The architecture ratchet is green: no new untyped public returns, no front-end file grew.
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

## Security with teeth

- **A request body is a record, never a raw map.** A record cannot carry a field it does not declare, so mass assignment is impossible by construction. The ratchet counts PATCH/PUT/POST handlers with a raw `Map` body; the count may only fall.
- **Tenant, owner and state come from the token and the store, never from the body.** Row-level security is the wall; the service is the door.
- **Tokens are validated in every component** (39 `SecurityConfig` copies), not at the gateway: the gateway stamps the tenant from the hostname and routes. A drift check across the copies is a follow-up.
- **CI runs CodeQL and dependency review** (`.github/workflows/security.yml`); Dependabot keeps dependencies current. Only a high-severity dependency finding blocks the merge (`fail-on-severity: high`); CodeQL findings appear as code-scanning alerts and block nothing until branch protection requires them — a follow-up.
- **Every component has a threat model** in `docs/threat-model/`; touching a trust boundary means updating it.
- Secrets never enter the repo — the secret gate (`ops/scan-secrets.sh`) runs as a pre-commit hook only on clones that ran `ops/install-hooks.sh`; CI has no secret scan yet (follow-up). PII redaction before a model call is the rule; today `AiGovernor` redacts only the ledger copy and the provider receives the prompt as written, and the `Redactor` knows email and phone only — redact-before-send is scheduled in the intelligence typing batch. Every model call is metered and logged.

## Discretion and safety

- Never print or commit secrets. Keys live in `~/.hermes/.env` and the repo-root `.env` only.
- No prospect or customer names in the repo or docs. Public wording for the licence is "source-available", nothing more.
- Never commit report PDFs (`docs/*.pdf` is ignored; the operators' manual is the one tracked exception).
- Confirm before anything outward-facing or hard to reverse: deploys to the box, posts, emails, deletions.

## When starting work

Read `docs/engineering-conventions.md`, then the arc's doc if one exists, then the relevant ADRs in `docs/adr/`. Prefer a small verified step over a large one; run the suite that covers what you touched before moving on.
