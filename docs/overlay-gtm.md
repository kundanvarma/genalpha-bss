# The overlay strategy — genalpha-bss on top of the BSS you already have

*2026-07-24. Most operators run several BSS stacks already, and nobody
rips one out: replacement cycles are 5–10 years and existentially risky.
This document describes the **overlay deployment mode** of a BSS that
is complete in its own right — sold to the digital budget in quarters,
not to core IT in decades — with the architecture, the honest hard
parts, and the migration path that makes itself.*

## The positioning, precisely

genalpha-bss is NOT an overlay product. It is a **full-fledged,
next-generation BSS** — catalog to cash, 33 components, 66 suites, 11
CTKs at zero — sufficient to run as an operator's ONLY BSS, and doing so
for every tenant in this repo. The overlay is a **deployment mode and a
go-to-market wedge** for the same product, not its identity. Lead with
the whole BSS; sell the entry point.

## The pitch, in one sentence

> **A complete next-generation BSS that can be your only one — or start
> as the agentic layer on top of the ones you have**: channels, governed
> AI, agents that buy from you and a workforce that works for you, one
> seam per legacy system, retiring nothing until you choose to.

## Why the overlay sells where replacement doesn't

- **A different buyer, a different clock.** Core BSS replacement is a
  CIO/CTO decision measured in years. The overlay is a CDO/digital
  decision measured in quarters — and it never asks the buyer to admit
  their BSS is bad, because it adds a layer *no* BSS has yet.
- **The wedge is the agentic layer itself**: ACP merchant surface (be
  buyable inside ChatGPT-class agents — suite #64), MCP tool surfaces,
  the AI control plane (metered, budgeted, kill-switchable — #59), the
  digital workforce with verified completion, approvals and the
  scoreboard (#65/#66), consent-first personalization, and martech with
  holdout-measured lift. Incumbent stacks do catalog-order-bill fine;
  none of them do this.
- **Composability is the sales unit.** 33 components means the operator
  adopts slices — the agentic storefront this quarter, the workforce
  next — not a platform ultimatum.

## Why THIS architecture can credibly be the top layer

The per-tenant seam pattern is already how genalpha-bss treats every
external system: the PIM (`pim-base-url`), the ESP (`esp-url`), the PSP
(`bss.payment.psp`), the bank (`bank-token`), the porting clearinghouse,
the worker's LLM (`worker-ai-*`) — all live registry config, all
fail-soft, all per tenant. **A legacy BSS is just another seam**:

| Overlay concern | The seam (same pattern as today) | v1 stance |
|---|---|---|
| Catalog | `legacy-catalog-base-url` — read-through federation; genalpha is the merchandising face, legacy stays the master | read-only |
| Customer 360 | `legacy-party-base-url` — read-mirror for copilots, For-you, the workforce | read-only |
| Orders | captured agentically here (ACP/MCP/channels), handed to legacy fulfilment by an adapter; genalpha keeps the engagement record | write-through |
| Billing | stays legacy in phase 1 — bills read-mirrored for the 360 and dunning context | read-only |
| Identity | the operator's existing IdP IS the tenant issuer (the multi-issuer resolver already supports any OIDC) | native |
| Tickets / AR worklists | read + write-through adapters so the digital workforce can work LEGACY backlogs — verified completion checks the legacy system | per-deployment |

**The one rule that keeps overlays alive: never two writers.** Legacy
masters party and billing; genalpha masters engagement, consent, AI
state and the agentic surfaces; adapters read through. Sync projects die
of dual mastery — this design refuses it.

## The honest hard parts (priced, not glossed)

1. **Adapter latency/reliability** — a 360 over a slow legacy SOAP API
   needs the caching + fail-soft discipline the channels already have;
   budget it per legacy system.
2. **Workforce verification against legacy** — "a worker cannot mark
   done what is not done" requires the legacy ticket/AR state to be
   readable; that is adapter work, honestly scoped per deployment.
3. **Reconciliation** — read-mirrors drift; the overlay must surface
   staleness (age-stamped reads), never hide it.
4. **Billing stays theirs** for a long time. genalpha's billing run
   becomes relevant only when new brands launch natively (phase 2).

## The strangler-fig roadmap (the decision that makes itself)

- **Phase 1 — the agentic skin**: channels, martech, personalization,
  copilots, agentic commerce, the workforce — legacy is the system of
  record behind seams. Live in a quarter.
- **Phase 2 — new brands born native**: the next MVNO/sub-brand launches
  entirely on genalpha (tenant onboarding is a 2-minute form — suite
  #49); legacy never touches it. The operator now runs two speeds on
  one pane of glass.
- **Phase 3 — migration at the operator's pace**: workloads move
  segment by segment; the legacy estate shrinks until retiring it is an
  accounting decision, not a project.

## The proof to build (future suite: the wrapped-legacy demo)

In keeping with the house rule — this page's claim must become a
receipt: a `mock-legacy-bss` container (a deliberately old-shaped
API), a genalpha tenant configured with `legacy-*` seams, and a suite
that proves: the agentic storefront sells an offering federated from
the legacy catalog; the order lands in the legacy fulfilment queue; the
digital workforce claims and resolves a LEGACY ticket with verified
completion; and the 360 shows age-stamped legacy data. Until that suite
exists, this document says **strategy**, not proof — the one word this
repo is careful about.

## Shipped

**2026-07-24 — the receipt landed the same day.** Suite #67
(`ops/e2e/wrapped_legacy_test.js`) green first run, all four legs:
`mock-legacy-bss` (envelope-wrapped, PROD_CD-shaped — deliberately old)
wrapped by genalpha through three per-tenant seams
(`legacy-catalog-base-url` / `-fulfilment-` / `-ticket-`, empty = native
mode). FEDERATION: the legacy catalog surfaces in the ACP feed as priced,
legacy-prefixed TMF620 (30s cache, fail-soft — a dead legacy never breaks
the native catalog; catalog findById resolves legacy- ids so ordering's
reference validation covers wrapped offerings). THE SALE: an agent bought
a legacy-federated offering through the standard ACP checkout. THE
HAND-OFF: the order landed in the legacy work-order queue (WO-1000),
fail-soft with the miss logged for reconciliation. THE WORKFORCE ACROSS
THE WRAP: the legacy incident joined the queue AGE-STAMPED; completing it
while the legacy system said OPEN was refused with 409; it completed only
after the incident closed IN the legacy system. Never two writers held
everywhere. The overlay is no longer strategy — it is proven.
