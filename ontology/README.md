# The Operational Semantic Registry

*The machine-readable representation of the GenAlpha Operational Ontology: what
exists in the business, what state it can be in, what can be done to it, under
which conditions, by whom, through which component, and what follows. The
ontology is the model; this directory is the registry. Everything here is
tested against the running platform by suite #125 — a declaration that does
not match code, routes, events or roles fails conformance.*

## Layout

```
ontology/
  capabilities.yml        typed capabilities: the verbs a component can execute (TMF API, seam, function)
  concepts/*.yml          nouns: business concepts, their states, properties, links, lineage
  actions/*.yml           verbs: governed business actions, the only writes offered to agents
  components/*.yml        the ODA components: what each manages, executes, emits, and how it describes itself
  tenants/<tenant>/…      operator extensions, merged over the core at read time (same file shapes)
  schema/*.schema.json    the shapes above, enforced on load and in CI
```

Three layers, as agreed with the review of 2026-09-11:

| Layer | Source | Who edits |
|---|---|---|
| TM Forum semantics | SID entities, ODA components, Open API resource models — cited as `lineage` on every concept and capability | nobody; we point at it |
| GenAlpha core | `concepts/`, `actions/`, `components/`, `capabilities.yml` | the product, one PR per change, versioned |
| Operator / tenant | `tenants/<tenant>/…` | the operator; may add concepts and actions, may tighten governance and policy on core actions, may never remove a core precondition |

## Rules

1. **Derived, not hand-modelled.** A concept's `backedBy` names the executed TM Forum resource; a canonical concept that spans several APIs (Subscription) is allowed only with explicit lineage to each. There are few of them.
2. **Actions are the only writes exposed to agents.** An action names its inputs, typed preconditions, permissions, policy domain, governance (autonomy, approval, audit), the capability that executes it, its typed effects and the events it emits. Prose belongs in `meaning`; everything else is a reference the conformance test resolves.
3. **Policy before execution.** The registry evaluates preconditions, permissions and the policy domain before it lets an action run, and a refusal names the failed condition in words.
4. **Versioned contracts.** Every concept, action and capability carries `version`, `introduced`, and when retired `deprecated` + `supersededBy`. Generated surfaces (MCP tools, the SDK) are regenerated in CI and diffed; a breaking change bumps the major version and keeps the old action callable until its `deprecated` date.
5. **Tested against the running system.** Suite #125 asserts: every route in `capabilities.yml` is served by the gateway; every event in `emits` exists in the publishing service's code; every role in `permissions` exists in the realm; every effect resolves to a capability; every component's `/.well-known/genalpha-component.json` agrees with its registry entry.
6. **Ontology, policy, learning stay apart.** The registry says what things are and what actions mean. The policy service says what is permitted now. Learning contracts choose within the allowed space and never redefine either.

## Reading it

`GET /ontology/v1/concepts`, `/actions`, `/capabilities`, `/components` serve the merged (core + tenant) registry for the caller's tenant. `POST /ontology/v1/actions/{name}/check` answers "may this happen, and if not why" without doing it; `/execute` does it through the mapped capability and writes a decision receipt. `GET /ontology/v1/explain?…` returns the same definitions in words — the source of "the BSS explains itself". `POST /ontology/v1/mcp` is the Model Context Protocol face generated from the actions; `ops/ontology/gen-sdk.mjs` generates the TypeScript SDK.
