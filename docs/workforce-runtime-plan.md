# The closed loop — workers fully created and controlled by the BSS — plan

*2026-07-23. Kundan's challenge, and he is right: since Hermes ships as a
package WITH the BSS, the loop can close — "Hire" on the dashboard should
produce a RUNNING worker, not credentials to paste. And the worker's LLM
secret must be changeable ON THE FLY through config, like every other seam.
v1 (bring-your-own-runtime) stays as the fallback; this arc adds the
packaged runtime that makes hire = start and fire = stop.*

## Research findings

- Hermes v0.19.0 has **no official Docker image** (verified) but installs
  cleanly by script; the package BUILDS its own image (installer + baked
  config template + skills + `--yolo` + gateway/cron inside). Ordinary
  packaging.
- **Config precedence** (verified from their docs): CLI args >
  `config.yaml` > `.env` (secrets) > defaults; every key has an env-var
  counterpart — so a container can be configured ENTIRELY by env at start.
- **Hot reload** covers only minor keys; model/provider/key changes need a
  process restart — which our lease design makes free: a restarted worker
  abandons nothing (claims self-expire), so ROLLING RESTART is the
  reconfiguration primitive.
- Field-proven today (live run): `--yolo` mandatory headless; model needs
  ≥64K context AND real tool-calling competence (3B/8B hallucinate — and
  verified completion caught every fabrication); claude-haiku-4-5 ran the
  care shift flawlessly (~850 tokens).

## The two boundaries that stay (the honest "fully")

1. **Spawn-rights live in the PACKAGE, not the core BSS.** A small
   `worker-controller` service ships with the workforce package and holds
   the scoped right to start/stop worker containers. The dashboard talks
   to it WHEN DEPLOYED; without it, the Hire flow falls back to today's
   credentials block. Capabilities arrive by opt-in package, never by
   default — the story we sell stays true.
2. **The operator owns the brain's credential** — but per Kundan's
   requirement it is LIVE CONFIG, not a deployment bake:

## On-the-fly LLM config (the requirement)

The worker's brain rides the seam every other per-tenant capability rides —
**the tenant registry**, live-refreshed, no restart of the BSS:

```yaml
# tenants.yml (union pattern, ${ENV:default}, live-refreshed)
worker-ai-provider: ${WORKER_AI_PROVIDER:}      # anthropic | openai-compatible | …
worker-ai-base-url: ${WORKER_AI_BASE_URL:}      # e.g. http://ollama:11434/v1
worker-ai-api-key:  ${WORKER_AI_API_KEY:}
worker-ai-model:    ${WORKER_AI_MODEL:}
```

The `worker-controller` watches these (same TenantFileRefresher pattern):
on change it performs a **rolling restart** of that tenant's workers with
the new env — each worker finishes or abandons its lease (self-expiring),
comes back with the new brain, and the crew list never lies about who is
working. Key rotation, provider switch, local-model fallback: one config
edit, fleet follows within a refresh interval. (Fallback when unset: the
existing `ai-*` fields, then refuse-to-start with an honest log line —
a worker without a brain never boots half-alive.)

## Design

**`integrations/hermes-worker/Dockerfile`** — headless Hermes: installer,
config template rendered from env at entrypoint (badge creds, gateway URL,
LLM provider/key/model, tool whitelist), skills baked in, cron enabled
("work the care queue every 15m" + "AR daily"), `--yolo`, guardrails
`hard_stop_enabled: true`.

**`worker-controller`** (new small service in the package, NOT a core
component): REST + the scoped container rights (docker.sock in compose /
a Deployment-scaler ServiceAccount in k8s):
- `POST /workers {name, job}` — mints the badge itself (its own
  provisioning credential with roles:admin scoped to user-mint+grant),
  starts a container with badge + current LLM env injected. Credentials
  never pass through a human or the browser.
- `DELETE /workers/{id}` — revoke badge, stop container (fire = both).
- `GET /workers` — containers ↔ crew list join.
- Surge: subscribes to `WorkforceSurgeEvent` (or polls the gauge) and
  scales bench workers up/down — **always under governance
  `maxWorkers`**; the claim-side 429 remains the hard backstop.
- LLM-config watcher → rolling restart (above).

**Dashboard**: Hire gains "…and start it" when the controller answers
(probe once per render); crew rows gain Stop/Restart for
controller-managed workers; fallback unchanged.

**Helm/compose**: `--profile workforce` = controller + (optionally) N
initial workers; k8s values mirror it. KEDA example updated to scale the
controller's Deployment instead of raw replicas.

## The proof (suite #66, workforce_runtime_test.js)

1. Controller up (compose profile) → dashboard Hire "…and start it" → a
   NEW worker appears on the crew list WORKING within a minute — zero
   manual steps, credentials never displayed.
2. The shift is real: a seeded ticket gets resolved by that worker
   (verified at the source, as always).
3. **On-the-fly brain swap**: change `worker-ai-model` in tenants.yml →
   controller rolls the workers → ledger's next self-reported model is the
   new one; no BSS restart, no manual steps.
4. Fire from the dashboard → badge revoked AND container stopped; crew
   row goes idle-then-gone.
5. Surge: flood the queue → controller adds a bench worker (capped by
   maxWorkers — set it to current+1 and watch it stop there); relieve →
   scales back.
6. Without the controller (stopped): Hire falls back to the credentials
   block — v1 keeps working.

## Order of work

1. Dockerfile + entrypoint (env-rendered config) — prove one containerized
   worker completes a real shift.
2. worker-controller (hire/fire/list + provisioning credential) + compose
   profile; dashboard integration + fallback probe.
3. Registry `worker-ai-*` fields + watcher + rolling restart; surge
   subscription under the ceiling.
4. Suite #66; regressions (#65, console, ai_control_plane); manual §16 +
   package README + landing page ("hire from the dashboard, running in
   seconds"); memoir chapter when the arc lands.

## Open questions to settle during build

- Anthropic key inside a container env vs mounted secret file — start env
  (matches Hermes .env precedence), note the k8s Secret path.
- One controller for both compose and k8s, or two thin drivers behind one
  API — likely one API, two drivers.
- Whether the controller's provisioning credential should be a dedicated
  confidential client (`bss-workforce-controller`) rather than a staff
  password — yes; scoped to mint+grant only.

## Shipped

*2026-07-24. All three milestones, suite #66 green first run.* Landed:
the headless Hermes image (installer-built, env-configured, refuses to
boot half-alive; the container IS the cron); the worker-controller
(hire = badge + container, fire = revoke + stop, auth by the BSS's own
verdict, port 8129 — 8127/8128 were taken); worker-ai-* in the tenant
registry with the rolling-restart watcher (the LIVE BRAIN SWAP is proven:
one yml edit, the running container re-emerged on the new model, no BSS
restart); surge polling under the governance ceiling; and the dashboard's
Hire now goes through the controller when deployed ("Hired AND started",
zero credentials on screen) with the v1 credentials flow as fallback.
Field receipts: the first controller-hired worker resolved a voicemail
ticket, resolved an assurance auto-ticket, and honestly ESCALATED a
storm-damaged router to field diagnosis.
