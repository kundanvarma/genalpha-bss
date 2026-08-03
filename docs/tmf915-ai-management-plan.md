# TMF915 AI Management — the standard face of the AI control plane — plan

*2026-08-03. The fleet's most differentiated substance — the governed-AI
control plane in `intelligence` — has never had a standard face. TMF915
(AI Management API Component Suite, v4.0.0) is TM Forum's answer to
"how does a service provider GOVERN AI deployed at scale": in-life
management of MODEL CONTRACTS — what each AI use is for, what serves
it, what it costs, and the operator's hand on the brake. Everything the
spec wants already exists here as working machinery, which is exactly
when a face is honest.*

## Research findings

- **The control plane is real and suite-proven** (suite #59): every
  model call goes through ONE door (`AiGovernor`: kill-switch → budget
  fail-closed → model → meter → one audit row); `ai_audit` carries
  use-case, provider, model, tier, tokens, cost, latency, outcome —
  including the refusals; `ai_budget` is the operator's tenant-wide
  budget + kill-switch; model routing is per-tenant config
  (fast/smart lanes, proven on the wire in the routing suite); the
  churn model is a genuinely VERSIONED trained artifact (weights,
  sample counts, trained-at).
- **A "scenario" is the natural model contract.** Eleven canonical
  use-case identifiers exist at the call sites (campaign-copy,
  product-copilot, incident-diagnosis, next-best-offer, …). Each has
  observed models, observed tiers, and a full metrics trail — all
  projectable from `ai_audit` with zero invention.
- **The honest gap: the brake is all-or-nothing.** The kill-switch is
  tenant-wide; there is NO way to suspend one scenario. TMF915's
  in-life contract management is precisely this lever — so the arc
  adds real substance: a per-contract switch, enforced in the
  governor's gate order, with its own audited refusal outcome.
- **The `ai:admin` seam was flagged long ago.** Budget-setting is
  gated `ai:use` today with a javadoc noting a production deployment
  would separate `ai:admin` — "the seam is the matcher". Introducing
  contract writes is the moment to open that seam: reads stay
  `ai:use`, contract suspension AND budget-setting become `ai:admin`.
- **The face pattern is `Tmf724Controller`**: separate TMF path over
  the internal service, static view mapper, dedicated security
  matcher.

## The design

`/tmf-api/aiManagement/v4` on `intelligence`:

- **`GET /aiModel`** (ai:use) — the models actually in service:
  projected from the audit ledger's distinct (provider, model, tier)
  pairs with the scenarios each has served, plus the churn model as a
  versioned trained artifact (sampleCount, positives, trainedAt). The
  ledger is the deployment record — the face reports what RAN, not
  what config promises.
- **`GET /aiModelContract` + `/{id}`** (ai:use) — one contract per
  scenario: state (active | suspended), the models and tiers that have
  served it, a monitoring block (calls, tokens, costMicros,
  avgLatencyMs, outcomes including every refusal class), and the
  governing guardrails (tenant kill-switch, budget, window).
- **`PATCH /aiModelContract/{id}`** (ai:admin) — the new lever:
  `{state: suspended|active, note}` upserts an `ai_contract` row. The
  governor checks it right after the tenant kill-switch: a suspended
  contract refuses with 403, audited as `refused-contract` — the
  refusal is evidence, same as budget refusals.
- **`ai:admin` opens**: new realm role (file + live, both realms),
  granted to the ops staff; budget POST moves behind it. Customers
  keep neither; staff keep both.

## The phase (one)

Migration (`ai_contract` + RLS), governor gate + audited outcome,
aggregate queries on the audit repo, the face controller, security
matchers, realm role file+live, gateway route (aiManagement →
intelligence; mvn package the gateway first). **Suite #81
`tmf915_test.js`**: models list names the serving model and its
scenarios; the campaign-copy contract carries real metrics (calls,
cost, latency from the suite's own traffic); SUSPEND campaign-copy via
the standard face → the next campaignCopy call 403s and the refusal is
AUDITED as refused-contract while knowledgeAsk still answers —
one scenario braked, the fleet unharmed; reactivate → 200; walls:
customer PATCH 403 (no ai:admin), customer read 403 (no ai:use), nova
sees only nova. Regressions serial: ai control plane (#59), model
routing, process-memory.

## Shipped

**2026-08-03, suite #81 green (four legs), first full run.** TMF915
lives at `/tmf-api/aiManagement/v4` on intelligence. The face told the
truth on its first breath: sixteen model contracts projected straight
from the audit ledger — campaign-copy alone showing 68 calls, the spend
in micros, the average latency, and every refusal class INCLUDING the
historical refused-budget and refused-disabled rows suite #59 left
behind months ago; five models the ledger proves have served (stub,
two local llamas, a real Anthropic haiku — and the trained churn
classifier with its sampleCount/positives/trainedAt training record).
Nothing registered, everything observed. The new substance: the
`ai_contract` per-scenario brake, checked in the governor's gate order
right after the tenant kill-switch — suspending campaign-copy over the
standard face 403'd the next call, AUDITED it as `refused-contract`,
and knowledgeAsk kept answering; reactivation restored service and the
contract remembers its last decision. The `ai:admin` seam the
governance controller always promised is OPEN: contract PATCH and
budget POST both require it (demo holds it, file + live in both
realms); a staff user with ai:use alone was refused on camera, a
customer saw nothing, and genalpha's suspension never crossed into
nova. Regressions green (serial): ai control plane #59, model routing
(after re-onboarding aurora — today's KC recreate had wiped the
dynamically-onboarded realms; the first operator-form run also tripped
on refresher lag against July's rebranded manifest, clean on re-run),
process-memory. That last one surfaced a real environment lesson: the
KC recreate had ALSO reverted accessTokenLifespan to the 300s default
(a live-only setting from months back), so long suites with
single-mint tokens started dying mid-wait with 401-objects. Fixed the
doctrine way — `accessTokenLifespan: 1800` in BOTH realm FILES and
live, helm synced: the file+live rule covers realm SETTINGS, not just
clients and roles.
