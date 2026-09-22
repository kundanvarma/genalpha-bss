# 0012 — AI proposes, a human decides; every model call metered; stub provider in every suite

**Status:** accepted, 2026 (intelligence component 2026-07-11, AI control plane and workforce later; recorded as an ADR 2026-09-22)

## Context

Copilots write campaign copy, propose offerings, summarise a customer and
draft ticket replies; digital workers work backlogs. A model that can also
press Create is a model that can launch a price or refund a bill on a
hallucination. Operators also need to know what AI cost them and to switch
it off, and the 146 suites must run on a laptop with no API key.

## Decision

- **A copilot returns a card; a human presses Create.** The proposal is
  rendered in the operator's language with the numbers it used; the write
  happens only on the click, under the person's own token. Launch
  governance can additionally set `ai-proposals: approve` so a copilot
  draft always asks an approver.
- **Agents act only through governed ontology actions** (ADR 0011), with
  `check`/`execute` under the caller's token and a receipt. Refunds, cease
  and erasure never execute from a worker; they become approval rows a
  human executes.
- **Every model call is metered and logged** in the AI ledger of the
  intelligence component: kill-switch → budget check → call → ledger row.
  A budget ceiling fails closed; a kill-switch stops copilots and workers
  alike. A 403/429 is said in words on the screen.
- **A stub provider** answers deterministically when `AI_PROVIDER=stub`
  (the default). Every suite runs with it; a real model is opt-in per
  tenant and per deployment.
- Prompts and contracts live in code with the component, versioned; no
  prompt in a front end; no prompt-only guardrails.
- **Customer data before the model — redact before send** (since
  2026-09-22). `AiGovernor` runs every prompt through the `Redactor` before
  the provider sees it: email, phone, IBAN and bank account, ICCID, IMEI,
  card PAN (Luhn), national identity numbers, generic 9–12-digit ids and
  labelled address lines become typed, stable placeholders (`<email#1>`,
  `<iban#1>` …) so the model can still refer to them; the answer is
  un-redacted for the caller by reversing the map within the same call.
  A tenant with `ai-raw-exposure: true` in `tenants.yml` sends the raw
  prompt instead, and the ledger row says `rawExposure: true`; the ledger's
  prompt and response copies are redacted either way and carry
  `redactedFields`. Names are not recognised (insight's PII firewall twins
  signals before they reach this component). The earlier 422 refusal for
  raw use cases without the opt-in is retired: the prompt goes, without the
  person, and the receipt says `raw-redacted`.

## Consequences

- Costs: no fully autonomous flows for money, rights or statute; a second
  click on every copilot outcome; the stub must be kept plausible enough
  for the suites to mean something; real-model quality is proven separately.
- Buys: an AI mistake is a card nobody adopted; the audit page answers "who
  asked what, and what did it cost"; the fleet is provable offline.

## Enforced by

Suites run with `AI_PROVIDER=stub` (`ops/run-all-suites.sh`); the AI
control-plane suite (metered, budget fail-closed, kill-switch); #125 for
governed actions; the AI audit page; review for prompt placement.
`AiGovernorRedactionTest` (a recording provider) asserts what reaches the
provider with raw exposure off and on; `RedactorTest` pins the recognisers
and the reversal.

## Related

`docs/ai-control-plane-plan.md`, `docs/agentic-workforce-plan.md`,
`docs/contextual-help.md` (honest 403/429), `docs/launch-governance.md`
(`ai-proposals`), `docs/engineering-conventions.md` §5.

Corrected 2026-09-22 after the threat model (docs/threat-model/README.md).
