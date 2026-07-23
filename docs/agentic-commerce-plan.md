# Agentic commerce — make the catalog discoverable and buyable by AI agents — plan

*2026-07-23. Today our MCP server (`integrations/mcp-server/`) exposes only
the B2B 5G-slice loop (intent → quote → provision). It exposes **none** of
the retail commerce spine — no catalog, no cart, no ordering. So a customer
shopping through Perplexity, ChatGPT or any agent cannot discover or buy a
single retail offering from any tenant. This plan closes that gap — the
right way, governed, and with a per-tenant switch so an operator that does
NOT want to be shopped by agents can stay dark.*

## The landscape (researched, not assumed)

Three protocols, three different layers — they compose, they don't compete:

| Layer | Protocol | Who / status | What it is |
|---|---|---|---|
| **Transport** | **MCP** | Anthropic, open | How an agent calls tools. What our server already speaks. Reaches Claude + MCP-native agents. |
| **Checkout choreography** | **ACP** (Agentic Commerce Protocol) | OpenAI + Stripe + Meta · Apache-2.0 · Sept 2025 · spec `2026-04-17` | The merchant HTTP surface that ChatGPT **Instant Checkout** and Perplexity drive: a product feed + a `checkout_sessions` lifecycle + a delegated payment token. Merchant of record stays the merchant. |
| **Payment mandate / trust** | **AP2** (Agent Payments Protocol) | Google + 100+ orgs · Apache-2.0 · v0.2.0 Apr 2026 | Cryptographically signed **Mandates** as W3C Verifiable Credentials — an *Intent Mandate* (the user's instruction) and a *Cart Mandate* (final approval of a specific cart). Payment-method-agnostic (cards, ACH, RTP, stablecoins). |

**The honest consequence:** exposing commerce over MCP alone gets us Claude
and MCP-native agents, but it does **not** put us in ChatGPT's checkout —
that requires the ACP surface. To be genuinely "buyable by the agents people
actually use," we ship both. MCP is the near-term, low-risk win; ACP is the
distribution play.

### ACP merchant surface (what we'd implement), from the spec

- **Product feed** — structured catalog (a *Products* spec + a *Promotions*
  spec), delivered by file upload or API, so the agent can understand and
  surface inventory. We already have TMF620 product offerings; this is a
  projection, not new data.
- **Checkout endpoints** (headers `API-Version: YYYY-MM-DD`, `Idempotency-Key` on every POST):
  - `POST /checkout_sessions` — open a session from line items (+ optional buyer/fulfillment)
  - `GET /checkout_sessions/{id}` — authoritative session state
  - `POST /checkout_sessions/{id}` — mutate items / address / fulfillment choice
  - `POST /checkout_sessions/{id}/complete` — apply payment, create the order
  - `POST /checkout_sessions/{id}/cancel` — cancel if not completed
  - `CheckoutSession` carries `line_items`, `buyer`, `fulfillment_details`,
    `fulfillment_options`, `totals`, `currency`, `status`, `capabilities`.
- **Delegated payment** — the agent hands a **narrowly scoped** payment
  token (ACP *SharedPaymentToken*; AP2 *Cart Mandate* as the cryptographic
  user-approval artifact) that authorizes exactly this cart, this amount.
  The tenant charges it through its own PSP and remains merchant of record.

## Design

### Two exposed channels, one governed core

Both channels are thin adapters over endpoints that **already exist** behind
the gateway (TMF620 catalog, TMF663 cart, TMF622 ordering, `/affinity`).
Nothing bypasses tenancy or the AI control plane — the MCP server already
proxies through the gateway, and the ACP surface will live behind it too.

**Channel A — MCP commerce tools** (extend `integrations/mcp-server`):

| Tool | Backing endpoint | Auth |
|---|---|---|
| `search_offerings` | TMF620 productOffering | public |
| `get_offering` | TMF620 + `/affinity` (also-bought) | public |
| `add_to_cart` / `view_cart` | TMF663 shopping cart | delegated |
| `place_order` | TMF622 product order | delegated |

**Channel B — ACP surface** (a new adapter, likely in the gateway or a small
`agentic-commerce` service): the product feed projected from TMF620, and the
`checkout_sessions` lifecycle mapped onto TMF663 cart + TMF622 order, with
delegated-payment completion.

### The one real design decision: checkout authority

Discovery is trivially safe (public, aggregate). **Checkout is where the
honesty lives**, and it inherits the stance we already took for GDPR
fan-out: *an agent may never act with more authority than the customer
granted it.*

- Discovery/browse tools: **public** — no token, aggregate catalog data.
- Cart + order: a **delegated, short-lived, commerce-scoped** token, minted
  by OAuth 2.0 token exchange (RFC 8693) at the tenant's own issuer — not the
  machine client-credentials token the current MCP tools use (that acts *as
  the BSS*, which is exactly wrong for buying on someone's behalf).
- Payment: a **single-cart, single-amount** delegated token (ACP
  SharedPaymentToken / AP2 Cart Mandate). Merchant of record stays the tenant.

### Governance — free, because it rides what we built

Every agent commerce call already passes the gateway and the **AI control
plane**: metered, budgeted, kill-switchable, tenant-walled by RLS. Agent
orders land on the **same audit ledger** as copilot actions, so the trail
answers *which agent bought what, for whom, under whose authority* — the
`agent.placeOrder → <order-id>` receipt, next to the words.

## The per-tenant switch (the explicit ask)

**Not every operator wants to be shopped by agents.** Reasons are real:
brand and price control, channel conflict with retail partners, margin
protection, or simply legal caution while agentic commerce settles. So this
is a first-class, per-tenant control — modeled exactly like every other
tenant capability: a field in `infra/tenants/tenants.yml`, live-refreshed by
`TenantFileRefresher` (**no restart**), enforced at the **gateway** (the one
choke point, consistent with hostname→tenant routing and RLS).

**New registry field** (union-of-fields pattern, `${ENV:default}`):

```yaml
agent-commerce: ${AGENT_COMMERCE:off}   # off | discovery | full
```

Three states, because "be findable but keep humans in the funnel" is a
distinct, common want:

| Value | MCP browse + ACP feed | MCP cart/order + ACP checkout |
|---|---|---|
| `off` | 404 — the tenant is dark to agents | 404 |
| `discovery` | ✅ found & compared, aggregate only | 403 — no agentic checkout |
| `full` | ✅ | ✅ delegated checkout |

**Default `off` (opt-in), deliberately.** Agent commerce exposes catalog and
checkout to third-party agents; the conservative, defensible default is that
a tenant is dark until it chooses otherwise — the same caution we apply to
outbound integrations, and the same operator-control mental model as the AI
control-plane **kill-switch**. Enforcement is gateway-authoritative; the MCP
server and ACP adapter also check the flag (defense in depth), but a tenant
set to `off` cannot be reached even if a downstream is misconfigured.

The host-admin **operator-as-a-form** console gains one selector
(off/discovery/full), so switching a tenant's agent exposure is a click that
live-refreshes the fleet — no deploy, reversible in seconds.

## The proof (suite #64, agentic_commerce_test.js)

1. **Discovery is public & real:** an MCP client `search_offerings` on
   genalpha returns real offerings; `get_offering` includes also-bought;
   no token needed.
2. **ACP feed validates:** the product feed endpoint returns a well-formed
   ACP *Products* payload for a `full`/`discovery` tenant.
3. **Delegated checkout, honestly scoped:** create session → update items →
   `complete` with a delegated commerce token + a test payment token → a
   TMF622 order exists; a machine/over-scoped token is **refused**; a
   replayed `Idempotency-Key` returns the same order, not a second one.
4. **The switch holds — all three states:**
   - a tenant at `off`: feed + checkout both 404;
   - at `discovery`: feed 200, `complete` 403;
   - at `full`: end-to-end works.
5. **Tenant wall:** nova's agent surface never reveals or transacts
   genalpha's catalog/carts; reached by nova's hostname, not a header.
6. **Governed:** the agent order appears on the AI-control-plane audit
   ledger, tenant-scoped; the kill-switch stops it.

Regressions: `storefront`, `csr`, `affinity` (#63), `ai_control_plane` (#59),
`copilot_experience` (#61), plus the MCP PoC loop.

## Order of work (phased — discovery first, it's free and safe)

**Phase 1 — Discovery (public, no auth question):**
1. MCP tools `search_offerings`, `get_offering` (with also-bought) over the
   existing public endpoints.
2. ACP product-feed projection from TMF620.
3. `agent-commerce` registry field + gateway enforcement for the `off` vs
   `discovery`/`full` split; console selector.
4. Suite #64 legs 1–2, 4 (`off`/`discovery`), 5; regressions; docs; commit.
   *After this, the answer to "can an agent see our offerings?" is yes —
   for any tenant that opts in.*

**Phase 2 — Delegated checkout (the auth + payment work):**
5. Token-exchange (RFC 8693) commerce-scoped delegated token at the tenant issuer.
6. MCP `add_to_cart`/`view_cart`/`place_order`; ACP `checkout_sessions`
   lifecycle + delegated-payment `complete`, idempotency, order creation.
7. Suite #64 legs 3, 4 (`full`), 6; AP2 Cart-Mandate verification as the
   user-approval artifact; regressions; docs; commit.

**Phase 3 — Distribution (out of code, into the ecosystem):**
8. Register the ACP feed with ChatGPT Instant Checkout / partners; document
   the merchant-onboarding steps in the manual.

## Open questions to settle before Phase 2

- **PSP for SharedPaymentToken:** we hold `pspToken/lastFour`, never a PAN
  (PCI scope verified). Which PSP charges the delegated token — reuse the
  payment component's provider seam, or an ACP-native path? Likely the seam.
- **AP2 vs ACP payment:** ship ACP SharedPaymentToken first (that's what
  ChatGPT uses today); treat AP2 Cart Mandate as the verification artifact
  and a second funding rail, not a blocker.
- **Feed freshness:** push on catalog change (we have the outbox) vs pull on
  a schedule. Outbox-driven is the honest, low-latency answer.

## Shipped

*(pending — this document is the plan; build begins on approval, Phase 1 first.)*
