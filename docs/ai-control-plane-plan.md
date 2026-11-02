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

## Fallback, EU-only route, real token counts (#422, 2026-11-02)

Three additions to the governed door, each off until a tenant asks for it.

**A second provider when the first is down.** A tenant may name a fallback
model per tier in `infra/tenants/tenants.yml`, written exactly like its main
one. When the main provider cannot be reached, times out, answers a server
error (5xx) or says it is overloaded (429), the same prompt — already
redacted, same canary — goes once to the fallback. Anything else (a 400, a
bad key) is not retried, because it would fail there too. The AI audit page
then shows two rows: the failed attempt (`error`, as before) and the answer,
whose `fallbackFrom` names the provider it stood in for. With no fallback
configured nothing changes: the call fails exactly as it did.

```yaml
      - id: <tenant>
        ai-provider: anthropic
        ai-api-key: ${AI_API_KEY_<TENANT>:}
        # tried once when anthropic is down; tier keys win over the shared ones
        ai-fallback-provider: openai-compatible
        ai-fallback-base-url: https://api.mistral.ai
        ai-fallback-api-key: ${AI_FALLBACK_API_KEY_<TENANT>:}   # an env reference, never a literal key
        ai-fallback-model: mistral-small-latest
        ai-fallback-jurisdiction: EU
        # optional per tier: ai-fallback-{provider,base-url,api-key,model,jurisdiction}-{fast,smart}
```

**An EU-only route.** `ai-data-residency: eu` makes the governor refuse any
model whose jurisdiction is not EU or self-hosted — before the prompt is sent.
The caller gets a 403 that says which model broke the rule and what to change,
and the ledger gets a `refused-residency` row. The rule applies to a fallback
too: an EU-only tenant whose EU model is down is never rescued by a US one; the
original failure stands and the refused fallback is its own ledger row. The
model door applies the same rule to digital workers (`worker-ai-jurisdiction`).
Any other word than `eu` refuses every call — a residency rule with a typo must
not quietly mean "anywhere".

Jurisdiction is still derived from the provider kind when nobody declares it,
exactly as before: `anthropic` = US, `openai-compatible` = self-hosted, the
stub = none. An operator declares it with `ai-jurisdiction` (shared, or
`ai-jurisdiction-fast` / `-smart`), using one of three words: `EU`, `US`,
`self-hosted`. The shared declaration covers a tier only while that tier uses
the shared provider and address; a tier moved to another provider does not
inherit "EU" from the one it left.

```yaml
        ai-data-residency: eu
        ai-provider: openai-compatible
        ai-base-url: https://api.mistral.ai
        ai-model: mistral-small-latest
        ai-jurisdiction: EU
```

**Real token counts.** When a provider's answer carries its own counts
(Anthropic `usage.input_tokens` / `output_tokens`, cached-prompt tokens
included; OpenAI-style `usage.prompt_tokens` / `completion_tokens`), the
ledger meters those. The characters-divided-by-four estimate remains for an
answer without them (the stub, a server that leaves the block off), and each
row says which it used (`tokenSource`: `provider` or `estimate`).

A fix found on the way: the router built every tenant's adapter from ONE
shared HTTP builder, and the Anthropic adapter adds its key as a default
header — so an adapter built later (another tenant, or now a fallback)
carried the earlier provider's key to its own endpoint. Each adapter now gets
its own copy; `AiFallbackResidencyTest` asserts the fallback never sees the
first provider's key, and went red when the copy was removed.

Proof: `AiFallbackResidencyTest` (13 tests, two loopback stand-in providers
behind the real router and adapters), `ModelDoorResidencyTest` (1), and the
audit view's new keys pinned in `DtoRoundTripTest`. Each new guarantee was
seen to fail with its code switched off. There is no browser suite for this
arc: the fleet has no stand-in provider that can be told to fail on demand.

### Honest limits

- **Jurisdiction is a declaration, not a measurement.** The BSS does not
  look up where an endpoint really runs. `openai-compatible` defaults to
  "self-hosted" as it always has — so an operator who points it at a public
  US API and declares nothing passes the EU-only rule. Declare
  `ai-jurisdiction: US` for such endpoints; the default is kept only so that
  nothing changes for tenants that have not asked for residency.
- **EU means the EU word.** EEA or UK endpoints are not a fourth word; an
  operator decides whether to declare them `EU`, and owns that decision.
- **One retry, one fallback.** No backoff, no circuit breaker: while the main
  provider is down, every call pays its failure (up to the client timeout)
  before the fallback answers. Each of those failures is a ledger row.
- **A failed attempt may still have reached the provider.** A timeout or a 5xx
  can come after the provider read the prompt; the ledger records the attempt
  as `error` with an estimated prompt size and no cost, because no provider
  told us what it charged.
- **The fallback answers in its own words.** Prompts are not tuned per model;
  a JSON-shaped answer from a smaller fallback may be poorer.
- **Usage covers what the provider reports.** Cached-prompt tokens are counted
  as plain input at the model's one rate (the copilot price table has no cache
  rates, unlike the model door's). A server that omits `usage` is estimated.
- **No browser suite.** The behaviour is proven by unit and MockMvc tests
  against loopback providers, not end to end through the gateway.
- **No tenant uses any of it yet.** No key was added to `tenants.yml`; every
  tenant runs exactly as before until an operator writes one.
