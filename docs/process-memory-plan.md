# Process flows + agent memory — TMF701 and the loop that compounds — plan

*2026-08-03. Two gaps, one arc. First: this BSS runs its order flows by
event choreography — great at running them, bad at explaining them.
There is NO failure state anywhere in the order path: a "failed" order
is a silent SOM wait (physical fulfilment that never got its callback),
a `held` approval, or an assurance problem — and the one component that
knows the cross-system timeline (Live Flow) is deliberately ephemeral.
Second: the intelligence component is governed but STATELESS — every
diagnosis would start from scratch; nothing compounds. This arc gives
the fleet an explicit TMF701 process layer (stuck becomes VISIBLE), and
gives the AI an IG1547-aligned memory (diagnosis becomes CUMULATIVE).
The headline proof is a curve no stateless agent can fake: the same
failure, auto-diagnosed from procedural memory by the suite's end.*

## Research findings

- **TMF701 Process Flow Management** (public spec + API repo, v4/R19
  line): `processFlow` instances with nested `taskFlow` subresources,
  a design-time SPECIFICATION half and a run-time half, event-triggered
  in event architectures, taskFlows completable automatically or
  manually, state PATCH-able. Exactly the shape for projecting flows we
  already run — the spec-as-data + facade tricks the house has used
  twice (TMF654, TMF658).
- **IG1547 ("Context Management for AI-Native Operations")** is TM
  Forum's new reference for how agents get, keep and improve context
  (AI-Native Blueprint Project). The full guide is MEMBER-GATED, so
  this design is "IG1547-aligned by its public description" and built
  from the standard agent-memory taxonomy — WORKING context (assembled
  per incident), EPISODIC memory (traces of investigations), SEMANTIC
  context (the resource/process graph we already have as TMF APIs),
  PROCEDURAL memory (approved runbooks) — plus the promotion principle:
  operational insight graduates to structural knowledge only when it
  proves stable. Nothing in this repo reproduces gated or third-party
  internal material.
- **The industry gap this fills**: RAG-over-logs retrieves what is
  accessible, not what is relevant, and has no structural way to get
  better at a RECURRING failure. Even agent frameworks that automate
  retrieval leave "curating runbooks from resolved incidents" as a
  human activity. The compounding loop — trace → recurrence → drafted
  runbook → human approval → auto-diagnosis — is the deliverable.

## Repo recon (what exists, what's missing)

- **No failure state exists**: SOM's ServiceOrder knows only
  inProgress/completed; mock activation always succeeds; the physical-
  fulfilment path WAITS silently when the callback never comes;
  ordering's `held` (family approvals) is the only real held state;
  listeners swallow unprocessable events. "Stuck" is invisible today.
- **The correlation recipe is already written** — the flow service's
  `correlationKeys()` stitches party/order/intent/quote/object across
  every `bss.*.events` topic, including the three-way order-id
  fallback (ordering events carry `id`, SOM events carry
  `productOrderId` as a SIBLING field — the join key trap). But Live
  Flow is SSE-only, in-memory, no persistence — an observability
  surface, not a data tap. The memory service is net-new persistence
  reusing a proven recipe.
- **The AI control plane is ready to host the agent**: LlmAdapter with
  the deterministic-stub seam (scenarios keyed on system-prompt
  substrings — suites need no API keys), AiGovernor (kill-switch,
  budget, meter, ONE audit row per call), and `ai_audit` already
  carries `action` + `resource_ref` columns designed for agent acts.
  The knowledge component's pgvector pattern (embedding outside the
  JPA entity, cosine top-5, stub/real embedding seam) is reusable for
  fuzzy runbook matching later.
- **Fault injection goes through PUBLIC APIs** (the ai_slice precedent:
  a posted alarm, never an internal hook): a physical-fulfilment order
  whose callback never arrives = deterministic stuck; a family order
  dead-ending in `held` = deterministic held; the assurance alarm path
  = deterministic problem.
- **L0's write path exists**: assurance already opens tickets machine-
  to-machine (`openTicket` + note PATCH); notes are author-stamped
  server-side, so the agent's diagnosis is visibly the machine's.
- Port **8116** is the next free; `services/revenue` is the newest
  scaffold to clone (listeners, machine interceptor, TickGuard, outbox,
  RLS — all current).

## Design

### Component #36: `process` (TMF701, port 8116)

**Specifications as data** (the design intent): seeded, editable
`processFlowSpecification` rows for the flows the fleet actually runs —
*Order to activation (digital)*, *Order with physical fulfilment*,
*Order held for approval* — each naming its `taskFlowSpecification`
steps (placed → [approval] → provisioned → activated → completed) and
what each step OWES: an expected next event and a time allowance.

**Instances by projection**: listeners on `bss.ordering.events` +
`bss.som.events` build one `processFlow` per productOrder (the flow
service's correlation recipe, persisted this time) and advance its
`taskFlow`s as the real events arrive. Alongside, an **event journal**
row per correlated event (type, source, time, digest) — the
cross-system timeline as data, per flow.

**Stuck becomes a STATE**: a TickGuard sweep walks inProgress flows;
a task past its owed time allowance goes to `failed` (physical-wait
and held flows get their own allowances), and
`TaskFlowStateChangeEvent(state=failed)` / `ProcessFlowStateChangeEvent`
publish on `bss.process.events` — the trigger everything downstream
listens for. PATCH lets an operator (or later an agent at higher
autonomy) retry/complete/cancel a taskFlow — TMF701's own lever.

This half stands alone: even with the AI switched off, the fleet gains
"every order flow is inspectable, and stuck is a state, not a
grep" — plus a console **Process flows** tab (spec + instances +
timeline).

### The memory (in `intelligence`, IG1547-aligned)

**Working context, assembled per failure** — the incident agent
listens on `bss.process.events`; on a failed task it assembles: the
SPEC (design intent), the failed task (handed vs owed), the flow's
event journal (the timeline), plus two memory lookups. Assembly is
orchestration over APIs that now exist — no new modelling.

**Episodic memory** — every investigation writes a trace:
`failure_signature` (deterministic: spec id + failed task + terminal
event pattern — exact match in P1, embedding similarity later),
context digest, hypothesis, confidence, proposed action, the HUMAN
VERDICT (useful / not, with note — mandatory, the house rule "every
movement carries its cause" wearing an ops hat), time-to-diagnose,
and `source` (llm | runbook).

**Procedural memory** — when a signature recurs N times (default 3)
with useful verdicts, the agent drafts a candidate RUNBOOK (diagnosis
template + proposed action). A human approves, edits or rejects.
Approved runbooks are VERSIONED, carry provenance (the trace ids that
produced them), and are REVOCABLE — revocation sends the next
occurrence back to the LLM path. The next occurrence of an approved
signature is auto-diagnosed ON SIGHT: no LLM call, `source=runbook`,
and the audit row says so.

**L0 autonomy, and the wall above it**: the agent's only write is a
ticket note (the assurance machine-ticket pattern) — diagnosis posted,
nothing touched. L1 (proposed action in the note, human executes) falls
out of runbooks naturally. L2 (auto-remediation) is OUT OF SCOPE: safe
retry needs idempotency guarantees per taskFlow that only the process
layer's maturity can earn — the plan says so rather than hiding it.
Diagnosis calls run through AiGovernor like every other AI use: killed,
budgeted, metered, audited.

**The curve as an endpoint**: `GET /ai/v1/incident/stats` — traces by
week, % auto-diagnosed from procedural memory, median time-to-
diagnosis. The learning curve is DATA the suite asserts, not a slide.

## The proof (suite #72, process_memory_test.js)

1. TMF701 visibility, AI off: an order placed and activated shows a
   completed processFlow whose taskFlows match the spec and whose
   journal carries the cross-system timeline; a physical-fulfilment
   order with no callback goes `failed` at the sweep — stuck is a
   state.
2. First failure: the agent assembles context, diagnoses via the
   deterministic stub, posts the note to a ticket (L0 — the note's
   author is the machine), writes the trace; the duty dev's verdict is
   mandatory and recorded.
3. Recurrence: the same signature three times with useful verdicts →
   a candidate runbook appears with provenance; approval versions it.
4. THE HEADLINE: the fourth identical failure is auto-diagnosed from
   procedural memory — no LLM call (the audit row proves it),
   `source=runbook`, and `/incident/stats` shows the curve a stateless
   agent cannot produce.
5. Revocation: revoke the runbook → the next occurrence goes back to
   the LLM path — bad memory cannot compound silently.
6. Walls: nova sees no flows, traces or runbooks; verdicts and
   approvals demand staff roles; the agent's credentials are read-only
   plus ticket-note-only.

## Order of work

1. **P1 — the process layer**: component #36 `process` (specs as data,
   projection listeners, event journal, stuck sweep, TMF701 API,
   events), wiring (compose/gateway/realms/DB), console Process-flows
   tab, suite #72 legs 1 (+ regressions). Standing value before any AI.
2. **P2 — the agent + episodic memory**: incident listener, context
   assembly, governed diagnosis (stub scenario + real-model prompt),
   L0 ticket notes, traces + verdicts, suite legs 2.
3. **P3 — the compounding loop**: promotion (N + approval), versioned
   revocable runbooks with provenance, auto-diagnosis, stats endpoint,
   console Runbooks tab, suite legs 3–6; capability map gains the
   process-orchestration row (✅ #72) and the books tell the arc.
4. **P4 (open, honestly)**: embedding-similarity signature matching
   (knowledge's pgvector pattern), L1 surfaced in consoles, L2 behind
   per-taskFlow idempotency guarantees the process layer must earn
   first.

## Shipped

**P1 — 2026-08-03, suite #72 green (three legs).** Component #36
`process` (port 8116, `/tmf-api/processFlowManagement/v4`) is live:
three seeded editable specs (digital / physical / held), flows
projected from `bss.ordering.events` + `bss.som.events` with the
Live-Flow correlation recipe persisted (including the
productOrderId-as-sibling join-key trap), the per-flow event journal,
the TickGuard stuck sweep, `bss.process.events` state changes, the
taskFlow PATCH lever, party-scoped customer reads (self-service order
tracking fell out for free), and a console Process-flows tab. Proven
live: kai's digital order projected to a COMPLETED flow whose journal
stitched ProductOrderCreate → ServiceOrderStateChange →
ProductOrderStateChange; a physical order with no installer callback —
a PUBLIC-API fault injection, place on the order item — went FAILED at
'fulfilled' with the owed-time message after the spec's allowance was
shrunk to 8s AS DATA; the operator lever resumed it; walls held.
Regressions green (storefront — one flake diagnosed as billing-run
contention between back-to-back runs, clean alone; console).
Empirical find: `product.place` survives ordering's event payload
end-to-end, so physical-vs-digital spec pick needs no new modelling.

**P2 — 2026-08-03, suite #72 at six legs, all green.** The incident
agent is live in `intelligence`: a failed taskFlow on
`bss.process.events` triggers CONTEXT ASSEMBLY (design intent from the
spec, the failed step with its owed-time message, the flow's
cross-system timeline — all machine reads under bss-intelligence,
which gained ordering:read + ticket:write file-AND-live, default-roles
lesson applied), then a GOVERNED diagnosis through the same
kill-switch/budget/meter/audit doors as every AI call, with a
deterministic stub scenario so suites need no API keys. L0 exactly as
promised: the agent's ONLY write is a ticket + diagnosis note (the
machine is the visible author); the episodic trace stores signature
(`spec:task`), context digest, hypothesis, confidence, proposed
action, source and diagnose-time; and the human VERDICT is mandatory
(400 without `useful`, staff-only — a customer got 403 on camera),
one per investigation. Proven live: signature
`order-physical:fulfilled`, confidence 0.72, 65ms, ticket noted,
verdict recorded. Regression green: copilot (the stub gained a
scenario without disturbing its neighbours).
