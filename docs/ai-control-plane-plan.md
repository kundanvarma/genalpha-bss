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
configured nothing changes: the call fails exactly as it did. The warning
logged when the fallback is tried names the failed provider's error class and
HTTP status only — never the error's text, which a provider may fill with the
prompt it could not process.

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

A route's jurisdiction is decided in this order:

1. **The address the call really goes to.** A call to a known public endpoint
   — `api.openai.com` or `api.anthropic.com` — is US, whatever the provider
   kind and whatever was declared: a declaration cannot move a public
   endpoint. A blank base URL counts as the provider's public endpoint,
   because that is where the call goes (`anthropic` with no `ai-base-url`
   calls `api.anthropic.com`; at the model door, `openai-compatible` with no
   `worker-ai-base-url` calls `api.openai.com`).
2. **The operator's declaration**, `ai-jurisdiction` (shared, or
   `ai-jurisdiction-fast` / `-smart`), using one of three words: `EU`, `US`,
   `self-hosted`. The shared declaration covers a tier only while that tier
   uses the shared provider and address; a tier moved to another provider
   does not inherit "EU" from the one it left.
3. **The provider kind's default**: `anthropic` = US, `openai-compatible` =
   self-hosted, the stub = none (the stub sends nothing, whatever is declared).

The deployment's shared default model — the one every tenant without its own
stack uses — follows the same order. Its declaration is `AI_JURISDICTION`
(`bss.intelligence.jurisdiction`), blank by default; so `AI_PROVIDER=openai-compatible`
with `AI_BASE_URL=https://api.openai.com` is US, and an EU-only tenant without
its own stack is refused rather than sent there.

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

Proof: `AiFallbackResidencyTest` (18 tests, two loopback stand-in providers
behind the real router and adapters, plus a request factory that records where
a call would have gone and sends nothing), `ModelDoorResidencyTest` (1),
`ModelDoorIsolationTest` (the door's blank-URL case), and the audit view's new
keys pinned in `DtoRoundTripTest`. Each new guarantee was seen to fail with
its code switched off. Of the five added on 2026-11-03, the three endpoint
tests and the log test were written against the old code first and failed
there; the `AI_JURISDICTION` test needs a setting the old code did not have,
and was seen to fail with the setting ignored. There is no browser suite for this
arc: the fleet has no stand-in provider that can be told to fail on demand.

### Honest limits

- **Jurisdiction is a declaration, not a measurement — except for two hosts.**
  The BSS knows exactly two public endpoints by name (`api.openai.com`,
  `api.anthropic.com`, both US). Any other address is what the operator
  declares, else the provider kind's default — and `openai-compatible` still
  defaults to "self-hosted", so an operator who points it at some *other*
  public API (Mistral, Groq, Azure OpenAI, a US region of anything) and
  declares nothing passes the EU-only rule. Declare `ai-jurisdiction: US` for
  such endpoints. The table is matched on the exact host: a proxy or gateway
  in front of OpenAI is whatever its own address says.
- **No EU arrangement on the two public hosts can be declared.** Because the
  host wins over the word, an operator with a contractual EU processing term
  on `api.openai.com` or `api.anthropic.com` cannot label it EU; it must use a
  regional endpoint address (which is then a declaration like any other).
  That is deliberate — the rule fails closed.
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

## Tracing — one key for a trace, a ledger row, a receipt and a decision (#422)

*Built 2026-11-02.* Every model call and every governed action now leaves an
OpenTelemetry span, and the span's **trace id** is written beside the evidence
the BSS already kept. One 32-character id answers "show me everything that
happened for this request": the trace in a collector, the AI ledger row
(`ai_audit.trace_id`), the ontology's receipt rows (`action_receipt.trace_id`)
and the decision in insight's log (`context.traceId`).

**What is traced.**

- *intelligence* — each model call through the governor is a span named
  `chat <model>` (for example `chat claude-sonnet-4-5`), carrying the
  provider, the model, the token counts, the tenant and the use case. A
  digital worker's call through the model door is the same kind of span, with
  the provider's own token counts. A failed call is marked as an error with
  the error's *class* only. When the tenant's fallback answers, the span is
  renamed for the model that served (`gen_ai.provider.name`,
  `gen_ai.request.model`, as the ledger's served row says), keeps the one it
  stood in for in `bss.fallback_from` with that failure's class in
  `bss.fallback_error_type`, and is not an error; only a fallback that fails
  too marks the span as an error.
- *ontology* — each governed action execute is a span named
  `execute_tool <action>` (for example `execute_tool changePlan`), carrying the
  action, the tenant, the agent, the channel, how it ended (executed, refused,
  refused by the component, filed for approval, uncertain, not attempted), the
  status the caller got, and the decision, receipt and approval ids.
- Both components' inbound requests and their outbound calls to other
  components are traced by Spring Boot itself, and the outbound calls carry a
  W3C `traceparent` header, so a caller's trace continues through them.

**What is never recorded.** No prompt, no answer, no system instruction, no
action input, no refusal sentence. The OpenTelemetry conventions offer opt-in
attributes for message content; this code has no path that writes them, and a
test fails if one appears. Content stays where the redactor and the RLS wall
already govern it: the ledger and the receipt.

**Pointing it at a collector.** Nothing leaves the process until an operator
names one. Set either of these on the `intelligence` and `ontology`
containers (both were tried against a stand-in collector):

```
MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces
# or the OpenTelemetry-standard name, which Spring Boot 4.1 maps to the same setting
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces
```

Unset — the application default, and how the laptop fleet and CI run — there
is no exporter at all: no network call, and no startup failure for want of a
collector. Spans are still made in-process, so ledger rows and receipts still
get a trace id. Sampling follows the caller's decision when a `traceparent`
arrives and otherwise keeps every trace (`TRACING_SAMPLING_PROBABILITY`,
default `1.0` here rather than Spring Boot's `0.1`, because a ledger row that
points at a dropped trace nine times in ten is not a join key).

**Finding the rest of a trace by hand.**

```sql
-- intelligence
SELECT created_at, use_case, model, outcome FROM ai_audit WHERE trace_id = '…';
-- ontology
SELECT recorded_at, phase, action, status FROM action_receipt WHERE trace_id = '…';
-- insight
SELECT decided_at, decision_point, action FROM decision_log WHERE context::jsonb ->> 'traceId' = '…';
```

**Which conventions.** The OpenTelemetry GenAI semantic conventions are in
"Development" status and have no tagged release. This follows
`open-telemetry/semantic-conventions-genai` at commit `16f31308`
(2026-11-02): `docs/gen-ai/client-inference.md` for the model call
(`gen_ai.operation.name=chat`, `gen_ai.provider.name`, `gen_ai.request.model`,
`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `error.type`) and
`docs/gen-ai/gen-ai-spans.md` "Execute tool span" for the action
(`gen_ai.operation.name=execute_tool`, `gen_ai.tool.name`,
`gen_ai.tool.type=extension`). House facts ride beside them under `bss.*`
(`bss.tenant`, `bss.use_case`, `bss.decision_id`, `bss.receipt_id`, …), never
instead of them. The pin is in `GenAiTracing.SEMCONV_PIN` and
`ActionTracing.SEMCONV_PIN`.

**Proof.** `AiCallTracingTest` (intelligence) runs a governed call against an
in-memory exporter: one `chat` span with the standard attributes, no content
in any attribute, error spans with the class and not the message, a fallback
span that names the provider that served and is not an error, and the
ledger row keyed by the span's trace id (and no id, rather than zeros, when
tracing is absent). It was seen to fail when a prompt was put on the span.
`ExecuteToolSpanTest` (ontology) runs an executed and a refused action: one
`execute_tool` span each, both receipt rows and the decision's `context`
carrying its trace id, the span naming the receipt and the decision, and no
input or refusal wording on the span.

### Honest limits

- **Only two components are traced.** The gateway and every other
  component still make no spans of their own, so a trace today starts at
  intelligence or ontology (or at whoever sent the `traceparent`), and the
  Kafka hop — the decision travelling to insight — is not part of the trace:
  the join to the decision log is the `context.traceId` value, not a span
  link. The fleet-wide row in `production-acceptance.md` stays open.
- **The conventions are not stable.** Attribute names have already moved once
  (`gen_ai.system` became `gen_ai.provider.name`); when they move again the
  pin above is what to compare against, and a backend that reads the older
  names will not see these spans as GenAI spans.
- **Token counts on governor spans are the ledger's**: the provider's own
  `usage` when its answer carries it, else the chars/4 estimate flagged
  `bss.usage.estimated=true` (the stub, a server that leaves `usage` off).
  The model door's counts are always the provider's.
- **A fallback leaves one span, not two.** The first attempt and the
  fallback share the call's span; the first attempt's duration is inside it
  and its failure is the `bss.fallback_error_type` attribute, not a child
  span of its own.
- **The provider name is the house name** (`anthropic`, `openai`,
  `openai-compatible`, `stub`). The first two match the conventions'
  well-known values; the others are custom values the conventions allow.
- **The outbound `traceparent` reaches the model providers too**, because the
  adapters use the same instrumented HTTP client as every other call. It is a
  random id with no customer in it, but it is a correlation id leaving the
  box. Suppressing it per destination is not built; an operator who objects
  today can turn tracing off for the component (`MANAGEMENT_TRACING_ENABLED=false`,
  not tried here), at the price of the join key.
- **The trace id is outside the receipt hash chain** on purpose — it is a
  pointer to telemetry, not evidence, and rows hashed before it existed must
  stay verifiable — so it can be edited without breaking the chain.
- **The decision log has no `trace_id` column.** The id rides in the
  decision's `context`, as the idempotency key does, so insight needed no
  migration and no consumer changed; the cost is that the decision's
  "Context used" sentence now lists `traceId` too, and a lookup reads JSON
  rather than an index. A column plus an index in insight is the next step if
  the lookup becomes routine.
- No numbered browser suite proves this through the gateway yet: the
  evidence is the two unit tests and a manual run of the built ontology jar
  against a stand-in collector (spans exported only once an endpoint was set;
  a caller's `traceparent` continued on its outbound calls).
