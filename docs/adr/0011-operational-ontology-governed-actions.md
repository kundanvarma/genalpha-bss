# 0011 — Operational ontology as the registry of what the BSS can do

**Status:** accepted, 2026-09-11 (two arcs the same day; reviewed and approved with additions)

## Context

Copilots, digital workers and external MCP clients need to act on the BSS.
Handing them the raw TMF APIs means every agent re-learns which nine
conditions make a subscription upgrade lawful, and every mistake is a PATCH
with no reason on record. The business needs one place that says what
exists, what can be done to it, under which conditions, by whom, and what
follows.

## Decision

- The **ontology** component (`:8160`, `/ontology/v1`) serves a YAML registry
  in the repo (`ontology/`): concepts with SID/TMF/ODA lineage, governed
  actions, typed capabilities, component descriptors, per-tenant overlays.
  Every file is schema-validated and reference-checked at startup; a
  dangling reference fails the build.
- **Agents act only through governed actions**, never generic writes. An
  action carries meaning, inputs, typed preconditions, permissions, policy
  domain, governance (autonomy, approval, audit), the capability that
  executes it, effects and events.
- `check` evaluates and names every verdict in words, changing nothing.
  `execute` runs the mapped TM Forum capability **with the caller's own
  token and channel** and writes a **decision receipt**
  (`DecisionRecordedEvent`) into the decision log. The intent is that the
  registry lends its own machine identity for policy evaluation and nothing
  else. **Today it lends more:** an action whose `executes.as` is `registry`
  (`issueCredit` under its threshold) runs with the registry's machine
  client, and the same client files approval rows on the workforce desk;
  that service account (`bss-ontology`) holds `policy:evaluate`,
  `insight:read`, `inventory:read`, `billing:admin`, `workforce:use` and
  `assurance:read` in the realm. The receipt names the human caller either
  way. Follow-up: narrow the service account to exactly what `as: registry`
  actions and approval filing need, per action.
- A tenant overlay may add and may tighten; it may never remove a core
  precondition or change `executes`, `inputs`, `permissions`, `emits`.
  **Today** the loader (`Registry.OVERLAY_MAY_REPLACE`) refuses those four
  keys and appends preconditions as intended, but lets an overlay replace
  `governance` and `policy` wholesale — so an overlay can also loosen
  autonomy, approval or a threshold. Follow-up: merge those two keys
  tighten-only (autonomy may only fall, approval may only be added,
  thresholds may only drop).
- The same definitions generate the MCP tools, the TypeScript SDK, the
  console's "What the BSS can do", the ? drawer's explanations and each
  component's `/.well-known/genalpha-component.json`.
- The rule in one line: **AI reasons and proposes. Policies govern.
  Deterministic components execute.**

## Consequences

- Costs: every new journey must be modelled before an agent may run it;
  ten actions are live today, the rest of the BSS is reachable only through
  its TMF doors; generated surfaces are contracts, so the suite fails when
  the committed SDK drifts.
- Buys: an agent cannot do what a person with the same token could not;
  every act has a receipt with the verdicts as evidence; a refusal reads
  the same in the console, the SDK and the MCP error.

## Enforced by

`ontology_test.js` (#125: registry loads, references resolve, gateway
routes, realm roles, self-description on six components, SDK diff, ten
actions end to end with receipts); `RegistryLoadTest` in CI.

## Related

`docs/ontology-seam.md`, `docs/ontology-research.md`, ADR 0013 (receipts
land in the decision log), ADR 0012.

Corrected 2026-09-22 after the threat model (docs/threat-model/README.md).
