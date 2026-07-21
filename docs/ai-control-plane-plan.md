# The AI control plane — plan

*2026-07-21. The intelligence service is already a proto-control-plane:
it authenticates AI calls (`ai:use`, per-tenant tokens), holds the
provider keys (`LlmRouter`), routes per-tenant/per-tier, redacts PII and
audits every completion. Two pillars are missing, and this arc adds them:
**budget** (metered spend with fail-closed ceilings) and a **unified
governed choke point** (so meter+budget+audit happen in ONE place for
every caller, and agent ACTIONS — not just completions — are on the
ledger). The result: authenticate + budget + audit, for every LLM and
agent/tool call, as the user asked.*

## What exists (verified)

- `LlmRouter implements LlmAdapter`, `@Primary` — **every in-process LLM
  call already goes through it.** That makes it the natural choke point.
- `LlmAdapter.complete(...)` returns bare `String` — no token usage. So
  metering estimates tokens from text (the industry chars/4 heuristic),
  identical for the stub and real providers; exact provider `usage` is
  a noted seam for later.
- `AiAudit` today: tenant, useCase, provider, model, prompt, response,
  createdAt. Written by each service by hand (`CopyAssistantService`,
  `CopilotService`, `ProductCopilotService`, …) — decentralized.
- Per-tenant AI config lives in `TenantRegistry.TenantEntry` (provider,
  base-url, key, model, fast/smart tiers), bound from `infra/tenants/
  tenants.yml` — the same registry every other seam uses.

## The design — govern at the router, meter from text

### 1. The governed choke point
`LlmRouter` gains `complete(String useCase, Tier tier, String system,
String user)` — the ONE governed path: **kill-switch → budget check
(fail-closed) → route → meter → central audit → return**. The existing
`complete(tier, system, user)` still works (routes through the governed
path as `useCase="unclassified"`), so nothing breaks; call sites migrate
to pass their useCase and DROP their hand-rolled `AiAudit` save — audit
centralizes. This is the rate-limiter/DNC-wash pattern applied to spend:
fail-CLOSED on a metered resource.

### 2. Meter (tokens + cost)
`AiAudit` gains `promptTokens`, `completionTokens`, `costMicros`, `tier`,
`latencyMs`, `outcome` (ok | refused-budget | refused-disabled | error).
Tokens = `ceil(chars/4)`; cost = tokens × the model's rate. A small
per-model price table (`bss.ai.prices`, micros per 1K tokens, with a
default) lives in config — a data table, swappable, honest that it's an
estimate until real provider usage is wired.

### 3. Budget (the new pillar)
Per-tenant fields in the registry: `aiEnabled` (kill-switch, default
true) and `aiBudgetMicros` (spend ceiling per rolling window, default 0
= unlimited so no suite starves). Before each call the governor sums
`costMicros` over the window from `ai_audit` (indexed on tenant+createdAt)
and REFUSES when the ceiling is crossed — a clean domain error, audited
as `refused-budget`, never a half-charged call. Kill-switch off →
`refused-disabled`. Fail-closed both ways.

### 4. Agent actions on the same ledger
`AiAudit` gains `action` and `resourceRef`. When a copilot/agent EXECUTES
(ProductCopilot creates an offering, the advisor adopts a draft), it calls
`governor.recordAction(useCase, action, resourceRef, outcome)` — so the
trail answers "which AI touched which customer/resource, and did it
write?", not just "what did it say". The MCP tools
(draft/submit/create/accept) get the same record on the intelligence side.

### 5. The stragglers → one door
`quote`'s narrative call is the one potential bypass. If it calls
intelligence over HTTP, it is already governed by the choke point (note
and confirm). If it has its own adapter, it is routed through the same
governor (or the intelligence API) so there is genuinely ONE governed
door — the map agent settles which.

### 6. Governance API + console
`GET /ai/v1/governance` (ai:use / catalog:write): per-tenant spend this
window, budget, remaining, kill-switch state, and the recent action
trail. The console "AI Audit" tab grows a spend/budget/actions header —
the operator sees the tab, not the table.

## The proof (suite #59, ai_control_plane_test.js)
1. **Metered**: a normal AI call (campaignCopy on the stub) returns, and
   its audit row now carries token counts and a non-zero `costMicros`.
2. **Budgeted, fail-closed**: a tenant dialed to a tiny budget makes calls
   until the ceiling; the next call is REFUSED (clean error, audited
   `refused-budget`), and NO partial/hidden charge appears.
3. **Kill-switch**: `aiEnabled=false` → the next AI call refuses cleanly,
   audited `refused-disabled`; flip back → it flows.
4. **Action trail**: a copilot execution shows an `action`+`resourceRef`
   row, not just a completion.
5. **Governance view**: `GET /ai/v1/governance` reports spend, budget,
   remaining and the trail; a second tenant's numbers are isolated (RLS).
6. **Fallback holds**: a tenant with budget 0 (unlimited) behaves exactly
   as before — governance is opt-in per tenant, not a global tax.

## Order of work
1. Migration V8 (audit columns + spend index) + V9 RLS-grant; `AiAudit`
   entity + repo (spend-sum + trail finders).
2. Registry fields (`aiEnabled`, `aiBudgetMicros`) + tenants.yml + compose
   dev dials; price table config.
3. `LlmRouter` governed `complete(useCase, …)` + `recordAction`; meter +
   budget + central audit.
4. Migrate call sites (CopyAssistant, Copilot, ProductCopilot, advisor,
   quote-narrative) to the governed path; drop hand-rolled audits.
5. Governance controller + console header.
6. Suite #59, regressions (copilot, advisor, model_routing, martech),
   docs (README/book/manual/architecture), commit.

## Shipped

Suite #59 (`ops/e2e/ai_control_plane_test.js`) green — all six checks:
metered (119 tokens / 238 micros on the ledger row), budgeted fail-closed
(429 + `refused-budget` audit), kill-switch (403 → flip → flows), the
advisor's adoption on the action trail (`catalog.createDraftOffering` →
offering id), the governance view with the RLS wall holding against
nova, and the no-budget-row fallback (unlimited-and-enabled — opt-in,
not a tax).

Landed: `AiGovernor` (the one governed door at the router), V8 audit
meter columns + `ai_budget` table (V9 RLS), all six call sites migrated
off hand-rolled audits (including the three that never audited:
next-best-offer, knowledge-ask, advisor-narrative — every AI turn is on
the ledger now), `GET/POST /ai/v1/governance[/budget]`, the console's
AI Audit tab showing tokens/cost/outcome/action.

The suite's failure taught the arc its lesson: the budget refusal's own
audit row was being erased by the caller's rollback — governance rows
now commit in their OWN transactions (the billing run's REQUIRES_NEW
lesson reapplied), because the ledger records what happened regardless
of what the caller's transaction later decides.

Regressions green: copilot, product_advisor, model_routing (aurora
re-minted first — live-minted realms die with the Keycloak container;
a recorded environmental fact, not a regression), martech. Noted seams
for later: exact provider `usage` capture in the HTTP adapters; a
separate `ai:admin` authority for budget-setting; MCP-tool actions when
that surface is rebuilt.
