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
  token and channel** — the registry never lends a machine identity, except
  one client that holds `policy:evaluate` and nothing else — and writes a
  **decision receipt** (`DecisionRecordedEvent`) into the decision log.
- A tenant overlay may add and may tighten; it may never remove a core
  precondition or change `executes`, `inputs`, `permissions`, `emits`.
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
