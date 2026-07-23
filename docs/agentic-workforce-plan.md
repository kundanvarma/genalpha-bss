# The agentic workforce — digital workers for back-office, call-center and care — plan

*2026-07-23. The BSS already lets agents BUY (suite #64: ACP + MCP retail
commerce). This arc lets agents WORK: an extended package where autonomous
agent frameworks — **Hermes Agent as the reference implementation** — are
employed as digital workers on back-office, call-center and customer-care
queues, with a **workforce dashboard** that shows who is working, what they
worked on, and the KPIs that prove reduced human workload. The selling
point, stated honestly: this BSS does not just tolerate AI agents — it
comes with the job descriptions, the badges, the approval gates and the
scoreboard to employ them.*

## Research findings

**Hermes Agent** (Nous Research, open-source, Feb 2026; 175k+ GitHub stars):
a persistent agent daemon on the operator's own infrastructure — systemd/
Docker deployment, cross-session curated memory, built-in cron scheduler,
16+ messaging platforms, skills in a portable `SKILL.md` format the agent
writes and improves itself, any LLM (OpenRouter / OpenAI-compatible / local
vLLM / Nous Portal).

**The integration hinge — confirmed**: Hermes is an **MCP client**. It
connects to external MCP servers over **stdio and SSE**, supports
**OAuth 2.1 PKCE** for remote servers, per-server tool filtering, and has
first-class `hermes mcp` install/configure/auth commands. Our MCP server is
therefore already the door; a Hermes worker simply adds it as a toolset.

**The consequence that shapes everything**: the BSS side needs NOTHING
Hermes-specific. What the BSS must supply is what any employer supplies —
**the job, the badge, and the audit** — over interfaces every agent
framework speaks (MCP + OIDC). Hermes is the reference employee, not a
dependency. A Claude agent, an OpenAI assistant runtime, or a homegrown
loop hires into the same three interfaces. Vendor-neutral, like the rest
of the product.

**Two honest boundaries found in research:**
1. Hermes brings its **own LLM** — its model costs do not pass through our
   AI control plane. Cost-per-task on the dashboard is therefore
   **self-reported by the worker** (fields on task completion) and labeled
   as such; the control plane remains authoritative only for calls made
   through OUR governed endpoints.
2. Hermes's own docs are thin on operator oversight — which is exactly the
   gap this package fills and sells: the BSS-side ledger and dashboard ARE
   the observability the agent framework doesn't ship.

## Design — the job, the badge, the audit, the scoreboard

### 1. The job: a task queue over REAL backlogs (intelligence service)

A new **workforce API** on the intelligence component (it already owns AI
governance and the action ledger): `/ai/v1/workforce/*`.

- `GET /tasks?kinds=` — the open work, DERIVED from queues that already
  exist, not a parallel copy: unassigned trouble tickets, the
  unapplied-cash worklist, order fallout (stuck/failed orders), porting
  exceptions, abandoned-cart follow-ups. Each task: `{id, kind, subjectRef,
  summary, tier}`.
- `POST /tasks/{id}/claim` — lease semantics (TickGuard-style row lease:
  a crashed worker's claim expires; two workers never hold one task).
- `POST /tasks/{id}/complete` — `{outcome, note, selfReported: {tokens?,
  costMicros?, model?}}`.
- `POST /tasks/{id}/escalate` — `{reason}` → lands in the human queue,
  counted (escalation rate is a KPI, not a failure to hide).

Backing table `workforce_task` (tenant_id + RLS like everything): the
ledger of who worked what, when, outcome, handle time, self-reported cost.

### 2. The badge: a staff identity, never a super-user

A digital worker is **staff**, hired per tenant:

- New composite role **`digital-worker`** in the realm: ticket read/write,
  interaction read/write, party read, billing read, ordering read,
  ai:use — deliberately NO payment:write, NO catalog:write, NO roles:admin.
- The console (Staff tab, existing TMF672 surface) mints
  `worker-hermes@<tenant>` and grants the bundle — **the badge IS the
  opt-in switch**: no badge, no workforce; revoke the badge and the worker
  is unemployed within a token lifetime. Same lever an operator already
  uses for humans.
- The worker authenticates to the MCP server with its badge credentials;
  every domain API call it makes carries that identity — so domain records
  (ticket history, interactions) already attribute to the worker like any
  employee.

### 3. The audit + approval gates: risk-tiered by blast radius

Tools are classified in the MCP layer:

- **T1 read** (list tickets, customer 360, worklists) — free.
- **T2 low-risk write** (assign/annotate/resolve ticket, log interaction,
  send status update via TMF681, match an unapplied payment to an invoice)
  — autonomous, ledgered.
- **T3 high-blast-radius** (refund, cease/terminate, erasure, credit
  adjustments) — the tool does NOT act: it files an **approval row**
  (`workforce_approval`), visible on the dashboard; a human approves and
  the action executes with the APPROVER'S token (the copilot stance,
  third outing: the model/agent never writes what a human must own).
  Refusals and approvals both keep their receipts.

Kill-switch: the existing per-tenant AI governance switch gates the
workforce API too — one lever stops copilots AND workers.

### 4. The scoreboard: the workforce dashboard (console "Workforce" tab)

The selling surface. One tab, answering an operations manager's questions:

- **Now**: which workers hold a badge, who claimed what, live.
- **Worked**: tasks completed today/this week, by worker and by kind;
  outcomes (resolved / escalated / approval-filed).
- **KPIs** (each computed from the ledger, definitions on the tab):
  - tasks completed autonomously vs escalated (deflection rate)
  - average handle time per kind
  - approval latency (filed → decided)
  - backlog burn-down per queue
  - **reopen rate** — tickets reopened after a worker closed them: the
    honesty metric that keeps the rest credible
  - cost per task (self-reported, labeled)
  - **estimated human-minutes saved** = completed × per-kind baseline
    minutes; the baseline is an OPERATOR-SET config value shown next to
    the number — an estimate that says it is one, never a claim
- Approvals queue with one-click approve/refuse.

Data: `GET /ai/v1/workforce/kpis` aggregating `workforce_task` +
`workforce_approval`; roles: staff with `ai:use` see it, approve needs the
domain authority the action itself needs.

### 5. The reference implementation: `integrations/hermes-worker/`

The extended-package artifact an operator deploys next to the BSS:

- README: `hermes mcp add` pointing at our MCP server; badge credentials
  via env; per-server tool filtering to the workforce toolset.
- **Job cards as SKILL.md** (Hermes's portable skill format):
  `care-triage.skill.md` (work the ticket queue: claim → read 360 →
  resolve or escalate → complete), `cash-matching.skill.md` (unapplied
  payments → invoice match), `fallout-clerk.skill.md` (stuck orders).
- Cron examples: "work the care queue every 15 minutes, morning summary
  to Slack."
- The honest line, in the README: any MCP-speaking agent runtime works;
  Hermes is the one we document end to end.

### MCP server additions (the workforce toolset)

`workforce_list_tasks`, `workforce_claim`, `workforce_complete`,
`workforce_escalate`, `care_ticket_get`/`care_customer_360` (staff-scoped
reads), `care_ticket_resolve`, `care_log_interaction`, `care_send_update`,
`backoffice_match_payment`, `request_approval` (the T3 door). All backed by
existing TMF APIs + the new workforce API; badge token via password or
client-credential per deployment; tools refuse without the badge.

## The proof (suite #65, agentic_workforce_test.js)

1. **Hiring**: console mints the worker badge (TMF672) → workforce API
   answers; a badge-less caller gets 401/403.
2. **A real shift** (scripted MCP client standing in for Hermes — CI
   cannot run a daemon): seed an unassigned ticket + an unapplied payment
   → worker lists tasks, claims both, resolves the ticket via TMF621 with
   its badge, matches the payment, completes both tasks with outcomes.
3. **The gate holds**: worker attempts a refund → NO refund exists; an
   approval row does; a human approves → the refund executes under the
   approver's token; the ledger shows filed-by-worker, approved-by-human.
4. **The scoreboard tells the truth**: KPIs reflect exactly the shift —
   2 completed, 1 approval, handle times, burn-down; reopen the ticket →
   reopen rate moves.
5. **Employment ends**: revoke the badge → next claim fails; kill-switch
   → workforce API refuses with the same honest 4xx the copilots give.
6. **Tenant wall**: nova's queue never shows genalpha's tickets.

Regressions: csr (ticket flows), ai_control_plane #59, agentic_commerce
#64, storefront.

## Order of work

**Phase 1 — the job + the badge + the shift** (build first):
workforce_task table + tasks/claim/complete/escalate deriving from ticket
+ unapplied-cash queues; `digital-worker` role bundle in both realm files;
MCP workforce + care toolset; suite #65 legs 1–2, 5–6.

**Phase 2 — gates + scoreboard**: T3 approval flow + workforce_approval;
order-fallout + porting queues; console Workforce tab with the KPI set +
baselines config; suite legs 3–4.

**Phase 3 — the package, TURNKEY** (decision 2026-07-23: ship Hermes as an
optional package so an opted-in operator has working agents from DAY 1):
`integrations/hermes-worker/` becomes a **compose profile** — the same
opt-in pattern as `--profile ai` (ollama): `docker compose --profile
workforce up -d hermes-worker` starts a containerized Hermes pre-wired to
the BSS MCP server, with the SKILL.md job cards preloaded, the cron
schedule set ("work the care queue every 15 min"), and a bootstrap step
that mints the badge (or refuses loudly if the operator hasn't granted
one — the badge stays the consent). Plus manual §16 "The digital
workforce"; landing page + README selling section ("agents that buy from
you — and agents that work for you"), honestly framed with the
reopen-rate and estimate labels.

## Open questions to settle during build

- Task derivation: poll the source queues on read (simple, always fresh)
  vs event-driven materialization (outbox listeners). Start with
  poll-on-read + short cache; the ledger only stores CLAIMED work.
- Badge auth for MCP: password grant for the worker user (dev) vs a
  per-worker confidential client (prod note). Start password, document
  the client-credential path.
- Whether `workforce_complete` should require the domain action to have
  actually happened (verify ticket state server-side) — YES where cheap:
  completing a ticket task checks the ticket is no longer open.

## Shipped

**Phase 1 — 2026-07-23.** Suite #65 (`ops/e2e/agentic_workforce_test.js`)
green: hired (badge minted + granted on TMF672; before the badge 403,
after it the queue answers), a real shift (queue derived live from a
seeded unassigned ticket + a parked camt.054 payment; claim leased; a
rival claim refused; completing an unresolved ticket refused 409; the
ticket resolved through the same TMF621 door a human uses; completion
with self-reported model usage on the ledger), honest escalation (the
unmatched cash could not be force-completed — escalated with a reason,
counted), the kill-switch (the tenant's one AI lever stops the workforce
mid-shift), fired (badge revoked → next shift never starts), and the
tenant wall (nova's own worker sees none of genalpha's backlog).

Landed: workforce API on intelligence (`/ai/v1/workforce/tasks` +
claim/complete/escalate/ledger; `workforce_task` V10 + RLS V11; lease
default 900s `bss.workforce.lease-seconds`; queue derived live from
`unworkedTickets()` + `unappliedCash()` — never copied); `workforce:use`
leaf + `digital-worker` composite in both realm files (intelligence SA
gained `billing:admin` for the worklist read); billing gained the missing
back-office act `POST /remittance/unapplied/{id}/apply` (open bill, exact
amount, same settle door, deterministic correlator — a mismatch is never
applied); 9 MCP workforce/care/back-office tools on the worker's badge
(`BSS_WORKER_USERNAME/PASSWORD`, refuses loudly when unbadged); and a
DESIGN FIX the suite forced: every minted login is born with the realm's
walk-in `customer` default, which party-scopes a worker into an empty
world of its own — so **granting `digital-worker` now sheds the walk-in
defaults in the TMF672 grant itself** (a hired worker is staff; firing
does not restore the persona). Regressions green: roles, csr,
ai_control_plane #59, agentic_commerce #64.

**Phase 2 — 2026-07-23.** Suite #65 extended and green end to end,
including a real browser leg on the dashboard:

- **The T3 gate** (`workforce_approval` V12+V13, `/ai/v1/workforce/
  approvals` file/list/approve/refuse): a worker FILES a high-blast-radius
  action as data (method + `/tmf-api/` path + body + reason — no other
  paths accepted); nothing executes; approving forwards the stored request
  through the gateway with the APPROVER'S own Authorization — a downstream
  refusal leaves the approval pending with the error surfaced. Proven: the
  filed refund moved no money; the worker could not approve its own ask
  (403 — filing is workforce:use, deciding is ai:use); the human's
  approval executed the refund; the refused ask left the money untouched.
- **The scoreboard** (`GET /ai/v1/workforce/kpis` + the console
  **Workforce tab**): completed/escalated/deflection, avg handle time,
  the REOPEN honesty metric (checked live against the ticket source,
  capped at 25), self-reported cost labeled as the workers' own word,
  human-minutes-saved carrying `estimate: true` + the operator-set
  baselines (`bss.workforce.baseline-minutes-ticket/-cash`), per-worker
  rows, approval stats. The dashboard is also the control room: the suite
  signs into the console, reads the KPI cards, and one Approve CLICK
  refunds a payment under the signed-in human's token.
- **MCP**: `request_approval` tool ("a filed approval is a good shift,
  not a failed one").
- **Real bug fixed en route**: `PaymentService.refund` NPE'd building the
  receipt for owner-less payments (staff/remittance-created) —
  `Map.of(null,…)`; refunds of back-office payments worked for the first
  time here.

Regressions green: storefront, remittance, console, ai_control_plane.

**Phase 3 — 2026-07-23.** The package: `integrations/hermes-worker/` —
`hire-worker.sh` (the badge bootstrap, smoke-tested against the live
stack: mint → grant → .env.worker shown once), `config.snippet.yaml`
(Hermes `~/.hermes/config.yaml` mcp_servers block with the tool whitelist
— the worker's hands exactly as wide as its job), and SKILL.md job cards
(care-triage every 15 min, cash-matching daily) encoding the floor rules
("do not argue with a 409; finish the work", "a near-match is a NO").
HONEST DEVIATION from the plan: Hermes ships via its official installer,
NOT a published Docker image (verified against their docs, July 2026) —
so day-1 is four documented commands rather than a compose profile; the
README says so and the profile lands the day Nous ships an image.
Docs/marketing: manual §16 "The digital workforce", memoir ch46 "The
hired hand", README capability clause + landing-page bullet and section
("agents that work FOR the operator"), counts at 65 suites / 46 chapters.
