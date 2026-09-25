# 0022 — React islands mount inside the back office's own panels

**Status:** accepted, 2026-09-25 (the Billing & Revenue desk and the catalog authoring controls)

## Context

ADR 0017 settled that channels are React and that the vanilla consoles migrate
**desk by desk**, never in one rewrite. Two arcs now need screens the vanilla
form engine cannot express: the Billing & Revenue desk (an overview that ranks
exceptions, a bill workspace, payments and collections) and the catalog
authoring controls that replace eleven raw JSON boxes. Rewriting the console
to get them would be the rewrite ADR 0017 refuses; writing them as more
vanilla files would add to the pile the conventions already forbid.

The console, meanwhile, has exactly the seam this needs: a resource may be a
**desk** and render its own panel instead of the generic table
(`core/list-dispatch.js`), and the shell owns sign-in, the navigation, the
command palette and the page frame.

## Decision

- A **React island** is a screen rendered by React **inside** a desk's panel.
  The shell keeps sign-in, navigation, the palette, the frame and the theme.
- One bundle for the whole console (`apps/admin-console/island`, Vite, IIFE)
  exposing `window.mountIsland(name, element, context)` and
  `window.unmountIsland(element)`. It loads as a classic script beside the
  desks, so the page needs no module loader and the load order stays honest.
- A resource opts in with one field: `island: '<name>'`. That is the whole
  coupling. An island imports nothing from the vanilla files; everything it
  needs — the authenticated fetch, the signed-in user, the tenant's words —
  arrives in `context`.
- The bundle is **built in the image** (a stage in the console's Dockerfile)
  and never committed; `site/island/` is ignored. React is pinned to the same
  version as the other channels, and the lockfile keeps `react` and
  `react-dom` a matched pair (a mismatch blanks the page — the claims gate
  checks it).
- Islands are built against React's **production** build. Vite's library mode
  leaves `NODE_ENV` unset, and the development build of React is 648 kB
  against 220 kB, with its slow paths and warnings.
- Desks convert one at a time. A vanilla desk that works stays vanilla until
  its arc needs more than the form engine gives.

## Consequences

- The console keeps one sign-in, one navigation and one command palette while
  gaining screens the form engine cannot express.
- The image gains a build stage, so a console change now needs `docker compose
  build console` rather than an nginx reload of static files.
- Two idioms live in one app. The rule that keeps it honest: the shell never
  reaches into an island, and an island never reaches into the shell.
- The 300-line front-end ratchet applies to island sources as to any other
  React file; the generated bundle is not in the tree, so it is not counted.

## Enforced by

- `ops/arch/ratchet.sh` — island sources are React files under the same
  300-line rule; the generated bundle is git-ignored and therefore uncounted.
- `ops/arch/claims.sh` — the `react`/`react-dom` pair check covers every
  lockfile under `apps/`, including the island's.
- `console_test.js` and the console's a11y scan run against the built image,
  so a broken bundle fails a pull request rather than a demo.

## Related

- ADR 0017 (channels are React; consoles migrate desk by desk) — this is its
  first island.
- ADR 0018 (screens speak operator language) — unchanged by the framework.
- `docs/backlog-2026-09-23.md` (the Offering workspace as the first island),
  `docs/catalog-authoring-research.md`.
