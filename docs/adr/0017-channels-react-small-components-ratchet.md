# 0017 — Channels: React with small components; vanilla consoles migrate desk by desk

**Status:** accepted, 2026-09-22

## Context

The storefront, CSR console and mobile app are React (Vite / Expo). The
admin console and the business console are hand-built vanilla JavaScript;
the admin console is one 8 483-line `app.js`. It works and its suites are
green, but no agent can read it in one pass, repeated edits to a huge
single file with inline scripts are a documented failure mode, and there
are no typed props or compile step to catch a mistake before a suite does.
No study measures React versus vanilla; what is measured is file size and
context fill.

## Decision

- Channels are **React**, and a component renders one concern. **No new
  vanilla-JS files.**
- A front-end source file stays under **300 lines**. Files over the limit
  today are listed in `ops/arch/baseline.json` and their line counts may
  only fall.
- The vanilla consoles migrate **desk by desk**, not in one rewrite: split
  into ES modules first (most of the agent benefit arrives here), then
  mount React islands with `createRoot` into the existing element ids one
  desk at a time, deleting the old desk when the new one's suite is green.
  The Offering workspace goes first.
- No business logic in a front end: pricing from the configurator,
  eligibility from policy, actions from the ontology (ADR 0009, 0011).
- Every front end sends `X-Channel`; accessibility is keyboard operable,
  visible focus, colour never the only signal; axe-core runs the WCAG 2.2
  AA ruleset in CI at zero violations.

## Consequences

- Costs: two UI technologies live in the admin console during the
  migration; every console arc carries some migration work; a desk cannot
  grow until it has been split.
- Buys: an agent edits one desk without loading the rest; typed props and
  a build step catch mistakes before Playwright does; the storefront and
  CSR patterns can be reused in the consoles.

## Enforced by

`ops/arch/ratchet.sh` (front-end line counts may only fall from baseline;
`MAX_FE_LINES=300`); one suite per migrated desk; axe-core in CI; review
refuses a new `.js` file under `apps/*/site/`.

## Related

`docs/engineering-conventions.md` §2 and §4,
`docs/engineering-principles-agent-era.md` §3, `docs/console-workspaces-plan.md`.
