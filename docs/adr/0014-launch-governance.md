# 0014 — Launch governance: tenant modes, pre-approved envelopes, an Approvals desk

**Status:** accepted, 2026-09-09

## Context

Most launches are routine and nobody wants to see each one; some touch the
P&L or the network and commercial, finance or legal want to look first;
approval is not readiness, so an approved offer must be able to wait and
still launch itself on the day. Until this arc a write to TMF620 was a
launch, and the alternative on the table was governance attributes on the
`ProductOffering` resource.

## Decision

- Three **tenant modes** in `tenants.yml`: `none` (default — a write is a
  launch, nothing changes for tenants that do not opt in), `envelope`
  (offers inside a pre-approved envelope launch by themselves and are
  ledgered to it; everything outside asks an approver), `always` (every
  launch asks).
- **Envelopes are TMF723 policy rules** (`domain: launch`, `effect: allow`)
  authored in the console with pickers only: category, price band,
  allowance, validity, channels.
- **The TMF620 resource stays standard.** Governance state, ledger,
  readiness, hold and expiry are internal columns behind
  `/productOffering/{id}/governance/...` doors; the CTK sees the same shape.
- Approve and reject need `catalog:approve`, a realm role never granted to
  the product persona. A non-approver's write lands as a draft. A substance
  edit voids a pending approval. Hold is the safe direction and any writer
  may pull it. Readiness ticks are owner-scoped. Approvals expire.
- Every step mirrors into a TMF701 process flow for the SLA-timed audit
  trail; the catalog owns no workflow engine (ADR 0004). Only the actual
  launch fires `ProductOfferingLaunchedEvent`, so marketing triggers are
  unchanged.
- `ai-proposals: trust | approve` decides whether a copilot draft is judged
  like a human's.

## Consequences

- Costs: a second state machine beside the TMF lifecycle; approvers must
  exist as realm roles per tenant; an envelope is only as good as its
  pickers; governance events are one more topic consumers must ignore on
  purpose.
- Buys: routine launches never wait; a new price band cannot go live from
  a product owner's keyboard; the ledger names the envelope or the person.

## Enforced by

`launch_governance_test.js` (#119), `channel_availability_test.js` (#118);
the TMF620 CTK still at zero failures.

## Related

`docs/launch-governance.md`, `docs/catalog-lifecycle-plan.md`, ADR 0001,
ADR 0005 (`X-Channel`).
