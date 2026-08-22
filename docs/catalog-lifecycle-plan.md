# Catalog lifecycle — launch is a decision, not a side effect

**Status:** SHIPPED (L1+L2+L3, 2026-08-22) · **Depends on:** TMF620 lifecycleStatus (already served and already filtered by every channel), the advisor's In-study draft + promote flow, the operator-as-a-form config seam, the P1 simulator

> **Shipped:** L1 teeth server-side (`LifecyclePolicy`): non-staff callers see
> only Launched/Active offerings inside their `validFor` window — list filter
> forced, by-id 404, and ordering a non-sellable offering (incl. bundle
> children) is refused at validation. Per-tenant `catalog-governance` switch
> (operator-as-a-form + console): `governed` mode lands every create at
> "In design" and enforces the TMF620 ladder one rung at a time; `direct`
> stays live-immediately. L3 `validFor` on the offering (V16) is enforced at
> query time — no tick decides visibility — while `LaunchEmitter` announces
> window-opens once (`ProductOfferingLaunchedEvent`) for launch-day journeys.
> L2 staff preview (`?preview=1`) walks the unlaunched shelf in the real shop
> with a PREVIEW badge. Suite `lifecycle_test` proves it all on a throwaway
> operator minted live.

## The finding (what the code says today)

Creating an offering makes it live immediately — but NOT because the
architecture lacks lifecycle. The machinery half-exists and is half-ignored:

- The storefront already lists `?lifecycleStatus=Active` only; the
  recommendation rail already reads activeOfferings. Draft states are
  INVISIBLE to customers today — the filter works.
- The product advisor already births "In study" DRAFTS a human must promote.
- But every OTHER creation path — console panes, the copilot executor, the
  seeds — writes `lifecycleStatus: 'Active'` directly. Live-immediately is a
  DEFAULT, not a design.
- `validFor` (the TMF620 validity period) is not on the offering entity at
  all: no scheduled launch, no automatic sunset.

So: is live-immediately a disadvantage? Both answers are true. For a demo
operator ("a product in the shop in 90 seconds") it is a feature. For a
governed operator it is a genuine gap — no test-before-launch, no
four-eyes moment, no campaign-window products. The industry expectation
(TMF620's In study → In design → In test → Launched → Retired → Obsolete
ladder plus validFor; Shopify-class scheduled publishing) is exactly what
the owner asked for. The answer is the house doctrine again: **capability
with a per-tenant switch — the platform enforces honesty, the tenant
chooses the policy.**

## Deep-pass findings (probed live, 2026-08-22 — these reshape the scope)

The owner asked whether the analysis was deep enough. It wasn't, yet. Two
live probes changed the plan's center of gravity:

1. **Draft invisibility is a client-side courtesy, not a server rule.** An
   ANONYMOUS caller who omits `lifecycleStatus=Active` reads drafts today —
   the storefront politely filters, the catalog does not enforce. Unlaunched
   pricing is competitively sensitive; this is an information-exposure gap,
   not a workflow nicety.
2. **Ordering a draft returns 201.** Neither the cart nor product-ordering
   validates lifecycle — a customer who knows an id can ORDER an In-study
   offering right now. Lifecycle without order-time teeth is cosmetic.

Consequence: L1's core is not the mode switch — it is SERVER-SIDE
ENFORCEMENT. Non-staff callers get only Launched/Active (and, with L3,
in-window) offerings REGARDLESS of query parameters; draft visibility
becomes a staff authority (which is also exactly what makes L2's preview
meaningful rather than redundant); and ordering/cart validate the
offering's state at add and submit time — including bundle children, and
including the mid-cart case where a validFor window closes before checkout
(clear refusal, never a silent price surprise). CTK note: the kits run
with staff credentials, so conformance is unaffected by guest-side
enforcement — verified against how the suites authenticate before build.

## The phases

### L1 — Governed creation (the mode switch)

Per-tenant `catalog-governance`: **`direct`** (today's behavior — create is
live; the demo default) | **`governed`** (create lands as `In design`;
LAUNCH is an explicit action). In governed mode the console offering pane
gains a Launch row action (In design → In test → Launched), the copilot
executor births drafts (it already knows how — the advisor path is the
precedent), and the state ladder is enforced server-side: no state skipping,
Retired is terminal, and every transition is an event
(`ProductOfferingStateChangeEvent`) journeys and the CDP can hear.

### L2 — Test in context (preview mode)

A draft nobody can SEE is a draft nobody can approve. Staff preview: the
shop with `?preview=1` — honored ONLY for a staff token (catalog:read) —
renders In-design/In-test offerings with a PREVIEW badge, real prices, real
configurator, real cart math; checkout stays blocked with a clear "this is
a preview" message. The product owner walks the actual shelf before the
customer ever does. (The P1 simulator complements this: a draft's forecast
uses assumed subscribers — the prospect-simulator path — until launch.)

### L3 — Time-bounded availability (validFor)

`validFor.startDateTime/endDateTime` lands on the offering; the catalog's
Active filtering ANDs the window AT QUERY TIME — a Launched offering before
its start or after its end simply is not served, no tick, no state flip, no
race. A summer campaign product configures its own disappearance. A small
scheduled emitter raises `ProductOfferingLaunchedEvent` when a window
opens, so launch-day journeys and campaigns fire themselves.

### L4 — noted, not scheduled

Effective-dated PRICE changes (a price with a future start — the shadow
billing loop would then flag upcoming drift as "scheduled, expected" vs
"unexplained") and full offering versioning. Build when a tenant asks.

## Honesty rules

- The mode is attested like price parity: a governed tenant's catalog says
  so; a direct tenant's says that too.
- Preview is never a back door: draft offerings stay invisible to guests
  and customers in every mode; only the lifecycle filter decides.
- Retirement is honest: a retired offering disappears from the shelf but
  never from history — existing subscribers, bills and reports keep their
  reference.
