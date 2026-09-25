# Architecture decision records

An ADR here is a short note (25–60 lines) that records one rule the system
is built on: what forced the choice, the rule itself in concrete terms, what
it costs and what it buys, and — the part that matters most in this
codebase — **which suite, test, ratchet or review holds it**. A rule with
no check is prose; the conventions file says why prose is not enough
(`docs/engineering-conventions.md`, `docs/engineering-principles-agent-era.md`).

ADRs are not design docs. The build is described in `docs/<arc>.md`; the ADR
points there under "Related". An ADR is written when a decision locks a
design, and again when a decision is replaced (the new one says what it
replaces and why; the old one is marked superseded, never deleted).

Dates: where the docs or the git history name the day, the ADR uses it.
Where a rule grew as practice rather than as one decision, the status
reads "2026 (recorded 2026-09-22)". Nothing here is back-dated to look
more deliberate than it was; 0016 says so explicitly.

## The records

| # | Decision | In one line |
|---|---|---|
| [0001](0001-tmf-open-apis-are-the-contract.md) | TM Forum Open APIs are the contract | House views ride beside the standard payload, never instead; the CTKs are the proof, intentional gaps are named. |
| [0002](0002-one-component-one-database.md) | One component per service, one database each | No shared schema; a component reaches another only through its API or its events. |
| [0003](0003-multitenancy-pool-with-two-locks.md) | Multitenancy: pool + two locks | `tenant_id` with row-level security, one issuer per tenant, hostname routes guests; 404 never 403. |
| [0004](0004-events-choreograph-outbox-no-workflow-engine.md) | Events choreograph | Outbox in the same transaction, one topic per component, idempotent consumers, no central workflow engine. |
| [0005](0005-one-api-gateway.md) | One API gateway | Per-issuer tokens, `X-Tenant-Id` from the host, `X-Channel` / `X-GenAlpha-Agent` everywhere, browse cache on the catalog route only. |
| [0006](0006-seams-for-vendor-and-country-systems.md) | Seams for vendor and country systems | Interface + adapter per vendor + a mock in the fleet + per-tenant selection in `tenants.yml`; fail soft. |
| [0007](0007-law-as-data.md) | Law as data | Statutory packs (collections ladder, notice periods, roaming caps) a tenant cannot undercut. |
| [0008](0008-prices-positive-discounts-are-rules-named-algorithms.md) | Prices, discounts, algorithms | Prices are positive; a reduction is a rule; only `perUnitAbove` and `stepped`; no free-form formulas. |
| [0009](0009-one-configuration-service-channels-never-price.md) | One configuration service | TMF760 on the catalog prices and validates every channel and the bill; channels ask, never compute. |
| [0010](0010-identity-rule-for-quantity.md) | The identity rule for quantity | Identity → N products; fungible → one product with a quantity. |
| [0011](0011-operational-ontology-governed-actions.md) | Operational ontology | The registry of what the BSS can do; agents act only through governed actions with check/execute under the caller's token and a receipt. |
| [0012](0012-ai-proposes-a-human-decides.md) | AI proposes, a human decides | Copilots return cards, Create is a click; every model call metered and logged; the stub provider runs every suite. |
| [0013](0013-decision-log-and-decisionpoint-seam.md) | Decision log + DecisionPoint seam | Every adaptive choice is a decision with policy, reason and outcome; learning contracts are tenant configuration. |
| [0014](0014-launch-governance.md) | Launch governance | Tenant modes none / envelope / always, pre-approved envelopes, an Approvals desk; the TMF620 resource stays standard. |
| [0015](0015-proof-by-numbered-suites.md) | Proof by numbered suites | A feature exists when its suite is green through the gateway with a real token; docs end with honest limits. |
| [0016](0016-typed-core-open-edge.md) | Typed core, open edge | Records at service boundaries, `Map<String,Object>` only at the TMF wire behind mappers; the ratchet. Replaces the earlier practice. |
| [0017](0017-channels-react-small-components-ratchet.md) | Channels: React, small components | No new vanilla-JS files; the consoles migrate desk by desk under the 300-line ratchet. |
| [0018](0018-screens-speak-operator-language.md) | Screens speak operator language | Names never keys; quiet healthy states; a goal line and a ? drawer on every page. |
| [0019](0019-hosted-demo-one-box.md) | Hosted demo | The same Compose fleet on one box, Caddy TLS, one hostname per tenant; deploy only on request. |
| [0020](0020-licensing-wording-no-prospect-names-no-pdfs.md) | Public repo hygiene | Source-available (BUSL-1.1) wording; no prospect names in the repo; no report PDFs in git. |
| [0021](0021-tmf634-served-by-product-catalog.md) | TMF634 in product-catalog | The resource catalog is served by the product-catalog component on the standard path; a resource spec names a seam, never a vendor; extraction later is along the API path. |

## How to add one

1. Copy the shape of any record: **Title · Status · Context · Decision ·
   Consequences · Enforced by · Related.** Keep it between 25 and 60 lines.
   Plain English an operator could read; no marketing.
2. Number it next in sequence (`NNNN-slug.md`, four digits) and add a row
   to the table above.
3. Status is `accepted, <date>` with the date the decision was taken. If
   the date is only "when the practice started", say so.
4. "Enforced by" must name a real check — a suite number and file, a test
   class, `ops/arch/ratchet.sh`, a CTK — or say honestly that the rule is
   held by review and name the follow-up.
5. "Related" points at the `docs/*.md` that describe the build, never the
   other way round: the ADR is the rule, the doc is the story.
6. To change a rule, write a new ADR that says what it replaces and why,
   and mark the old one `superseded by NNNN`. Do not edit the old
   decision's text.
