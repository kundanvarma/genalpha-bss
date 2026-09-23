# Privacy & compliance — the subscriber's rights as features

*Suite #58 (`ops/e2e/privacy_test.js`) proves everything in the first
half of this document live. The second half is the honest ledger of what
only a real engagement can attest.*

## The data passport (GDPR Art. 15/20)

`GET /privacy/v1/export` — one JSON document: the profile, plus a
category per service (interactions, messages, marketing, carts, tickets,
appointments, behavioral). The privilege design is the point: **the
fan-out rides the caller's own token.** A person exporting themselves
presents their own credentials to every service, and each returns only
what those credentials could already read — the right of access
implemented as *no new authority at all*. The DPO (`roles:admin`, the
user-administrator standing in as data-protection officer) may export any
party. The passport also NAMES the categories held under legal basis
(bills, payments, orders, usage, agreements), so the answer is complete
even about what it cannot hand over for deletion.

## Erasure (Art. 17) — honest in both directions

`POST /privacy/v1/erase {partyId}` (DPO only) refuses and retains
exactly where the law does:

- **Active subscribers refuse erasure (409)** — terminate services
  first; erasure never breaks a running contract (Art. 17(3)(b),
  performance of contract). The check walks the live inventory and
  FAILS CLOSED: if inventory cannot be read, erasure refuses rather
  than guesses.
- **Bookkeeping categories are retained and say why**: bills, payments,
  orders, usage, agreements each appear in the report with their legal
  basis (5-year bookkeeping class, limitation periods).

What goes: interactions, messages, campaign enrollments and touches,
carts, tickets, appointments, behavioral profiles+events — deleted at
their services; the party profile — **anonymized in place** (name
"Erased", contact media emptied, birth date nulled; the id row survives
as the pseudonymous reference every retained record points at); the
login — **disabled and scrubbed at the IdP** (name/email leave the
realm; the account id remains).

Every erasure writes an **immutable audit row** (`erasure_record`): who
executed, when, and the full per-category report — proof the right was
honoured, holding no personal data beyond the party reference.
`GET /privacy/v1/erasure` lists the trail (DPO only). A partial fan-out
(a service down mid-erasure) is reported as `partial — re-run required`,
never silently absorbed.

## Retention is a clock (data minimization, Art. 5(1)(e))

Interaction records and settled tickets are deleted on schedule by
TickGuard-guarded sweeps — `BSS_RETENTION_INTERACTIONS_SECONDS` /
`BSS_RETENTION_TICKETS_SECONDS` (0 = keep forever, the dev default so no
suite loses its timeline; production sets day-scale values). Open
tickets are never touched. Per-tenant retention dials via the tenant
registry, and richer policy-authored retention rules, are the recorded
follow-up — the sweep machinery is the hard part and it exists.

## PCI scope — verified, then claimed

Card numbers never enter this BSS. The storefront exchanges card details
with the PSP directly; the payment-method vault stores
`pspToken / brand / lastFour / expiry` — no PAN, no CVV (verify:
`services/payment-method/.../entity`). The mock PSP and the Stripe
adapter both follow the tokenize-at-the-edge shape. That is an
**SAQ-A-shaped posture**, and the claim stops there honestly: a QSA
attests PCI compliance, not a README.

## Lawful intercept — the boundary stated

Network interception (ETSI X2/X3) lives in the network, not the BSS.
The BSS half is **warrant-gated subscriber disclosure** (X1-shaped: who
holds this MSISDN, since when, which services) — designed as a
role-gated, warrant-referenced, audit-logged endpoint on the party/SOM
data, **not built**: it ships when an operator with a legal obligation
names its national format. Shaped, not connected — the same honesty as
the NRDB porting gateway.

## DR — measured here, multiplied by topology there

`ops/backup.sh` + `ops/restore-drill.sh` measure this stack's RPO
(backup age) and RTO (drill duration) every time they run. Regional DR
is deployment topology: managed Postgres cross-region replicas, object
storage geo-replication (the S3/Azure content seam is already
region-agnostic), Kafka MirrorMaker or a managed equivalent — the
runbook lives in [hardening.md](hardening.md). Drill on a schedule; an
untested region is an untested backup at continental scale.

## What reaches an AI model today — stated plainly

Personal data is redacted before any model call (since 2026-09-22). The
intelligence component's `AiGovernor` is the one door, and every prompt
that leaves the process goes through its `Redactor` first: email
addresses, phone numbers, IBANs and bank accounts, SIM ICCIDs, IMEIs, card
numbers, national identity numbers (Norwegian fødselsnummer and Nordic
personnummer shapes, plus generic 9–12-digit ids) and labelled address
lines are replaced by typed placeholders (`<email#1>`, `<phone#1>` …) that
stay stable within the call, so the model can still say "reply to
<email#1>" and the answer is un-redacted for the caller before anyone sees
it. What is NOT recognised: personal names (signals are twinned by
insight's PII firewall before they reach a model; a copilot prompt that
names a customer sends the name) and free-text addresses without a label.
A tenant that sets `ai-raw-exposure: true` sends the raw prompt instead,
and the ledger row then says `rawExposure: true`. **No tenant that ships
in this repository does** — `ops/arch/claims.sh` refuses a literal `true`
in `tenants.yml`, so turning it on is an environment variable an operator
sets deliberately. This sentence used to read "every demo tenant does",
which was wrong in both directions: eleven of twelve were already
redacted, and the twelfth was `taranga` — the one tenant that is publicly
reachable on the hosted box *and* wired to a real model key. Corrected
2026-09-23, along with the tenant. The ledger (`ai_audit`) keeps the redacted prompt and
response either way and records `redactedFields`. No retention sweep
deletes AI ledger rows, so stored (redacted) prompts are kept until
someone removes them, and neither `ai_audit` nor the decision log is yet
a category in the passport or the erase fan-out.

## Still on the ledger

Third-party penetration test; GDPR records-of-processing (Art. 30
register, an operator document); DPIA templates; per-tenant retention in
the registry; consent-lifecycle UI surfaces beyond the existing DNC/
marketing-consent seams; name recognition in the redactor, and the AI
ledger in the passport and erasure (see above).
