# 0018 — Screens speak operator language

**Status:** accepted, 2026-09-10 (console hierarchy and human-language pages; recorded as an ADR 2026-09-22)

## Context

The consoles were built API-first, so early pages showed what the API
returned: decision-point keys, policy ids, UUIDs, JSON-logic, `webapp`
typed into a channel field. A product owner, a CSR or a finance approver
does not think in those terms, and a co-founder's review said so with
screenshots. A page that shows a key is a page that leaks the
implementation and hides the goal.

## Decision

- **Names, never keys.** A page shows the offering's name, the customer's
  name, the journey's title, the rule in words. Ids and UUIDs do not appear
  as text; where an id is needed for support it sits behind a copy action.
- **Pickers, not free text**, for anything with a registered vocabulary
  (channels, categories, price bands, roles). Nobody types `"webapp"`.
- **Healthy states stay quiet.** A green system shows nothing to fix; empty
  states say what would appear and how to make it happen; "never opened"
  needs real use before it is said.
- **Every page has a goal line** under its title saying what the page is
  for, and a **? drawer** with the page's help article, "Explain this
  page" (drawn from the ontology first, then the manual), and page-aware
  Ask. A 403 from the AI kill-switch or a 429 from the budget is said in
  words.
- **Hierarchy:** department rail on the left, pages under it, content in
  the centre; list first, the form behind a New button, in a drawer.
- **Screenshot and look before handing over.** No console change is done
  until someone has opened the page as the persona who will use it.
- Money is shown in the tenant's currency; no hard-coded symbols.

## Consequences

- Costs: every list needs a name resolver (and a resolver for deleted
  things); every page needs a goal line and a help article; copy changes
  break suites that assert on words.
- Buys: a demo needs no translator; a receipt reads as a sentence; an
  operator's question ("why did this customer get this?") is answered on
  the page, not in a log.

## Enforced by

Screenshot-and-look before handover (the standing rule); the contextual-help
suite (#120) and self-explaining-help checks; console suites that assert
on human text; review under `docs/engineering-conventions.md` §4. The
conventions ask console suites to assert no UUID text — that assertion is
not yet in every console suite and is a follow-up.

## Related

`docs/contextual-help.md`, `docs/csr-workspace.md`,
`docs/console-workspaces-plan.md`, `docs/decision-log.md` "Console".
