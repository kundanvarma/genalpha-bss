# Agentic engagement — stop gamifying attention, start publishing proof

**Status:** PLAN v2 (researched + revised 2026-08-22; build on trigger) · **Depends on:** the LIVE ACP surface (product feed, checkout_sessions, delegated payment tokens, RFC 8693 shopper exchange — shopping-cart `AcpCheckoutController`, MCP-wrapped in integrations/mcp-server), the agent-commerce gate (off | discovery | full, born off per tenant), the dealer/partner channel (commission accrual, hardening, clawback), the referral engine (G1), TMF696 risk, the receipts culture

## Where the industry actually is (researched)

The transaction layer is standardizing fast: **ACP** (OpenAI + Stripe,
Apache-2.0, spec on GitHub; Stripe's Agentic Commerce Suite shipped, PayPal
and Shopify aboard) standardizes the agent→merchant checkout — **and this
platform already implements it**, worn both as the raw ACP surface and as MCP
tools. **AP2** (Google + 60 partners, being donated to the FIDO Alliance)
defines the trust layer: cryptographically signed **mandates** — verifiable
statements of what an agent may do on a human's behalf — with an Agentic
Authentication working group (Google, OpenAI, CVS, Mastercard's Verifiable
Intent) forming around the same idea. The strategic read: checkout is
becoming a commodity rail; **the open ground is trust, attribution economics
and negotiation** — exactly where this plan plays. One cautionary datum:
OpenAI's first Instant Checkout was retired after thin merchant adoption —
the rail matters less than what a merchant is WORTH connecting to, which is
the proof surface's whole argument.

## The thesis

An AI shopping agent does not feel dopamine. Wheels, streaks and scarcity
theater are worthless against a buyer that optimizes terms. What an agent
"engages" with is machine-legible trustworthiness: verifiable prices,
published odds, provable reward terms, exit without hostage-taking. Two
consequences, both structural:

1. **An agent that brings a subscriber is a DEALER, not a customer.** The
   right economic contract for agent platforms already exists in this
   codebase: commission that accrues pending, hardens after the withdrawal
   window, and claws back honestly. An agent is a dealer with an API instead
   of a counter.
2. **The honest game is the only gamification that survives agents.** The
   receipts this platform already keeps — holdout-measured lift, transparent
   odds, one-number pricing — stop being ethics and start being RANKING
   SIGNALS for the agents that assemble shortlists. "The operator whose
   claims an agent can verify" is a category of one.

## The phases

### A1 — Agents are dealers (the channel contract)

Agent-attributed conversions flow into the commission machinery: the
agent-commerce gate already knows which agent platform is calling; a
registered agent platform becomes an org party with a partner agreement
(the dealer shape), and a completed order attributed to it accrues
commission — pending, hardened, clawed back on early churn, exactly like a
store. The wholesale desk gains an "Agent channels" readout: acquisition,
commission, and clawback per platform. Referral velocity guards get an
agent-specific class: machine-scale abuse is the same farm signal at a
different clock speed.

### A2 — The referral artifact (human relationships, machine carriers)

A member-get-member code's real payload is proof of a human relationship —
which survives agents if it becomes a portable, verifiable artifact.
**Standards alignment: this is an AP2-shaped mandate** — a signed statement
("Rita authorizes carrying her referral to one friend") an agent presents at
the ACP checkout session; the platform verifies the signature, not the
story. Self-scoping, one-per-joiner and pay-on-first-completed-order are
unchanged. The human said "use Rita's code"; the agent carried it provably.

### A3 — The proof surface (the trust API)

A crawlable, signed `/proof` face that answers what an agent actually ranks
on: the published odds of every chance mechanic; the holdout-measured lift
an engagement program claims (or the honest absence of a claim); the
one-number pricing attestation (what the subscriber pays, with no
agent-invisible fees); the Right-Plan Guarantee's running count. Nothing new
is computed — the receipts exist; this phase only makes them legible to
machines. The bet: agents will rank verifiable operators above cheaper
unverifiable ones for risk-averse users, and this platform can be first to
find out.

### A4 — Values metadata + governance parity

Klubbdugnad and community goals published as structured cause metadata, so
an agent instructed "prefer the operator that funds my club" can honor it —
community belonging is the one loyalty input a rival cannot undercut on
price. And the guardrails get governance parity with the AI governor:
per-agent-platform budget caps, a kill switch per platform, TMF696 risk
class for agent-attributed conversions, and every agent decision leaving
the same receipts a human channel would.

### A5 — Bounded negotiation, v3: configuration flexes, price integrity holds

The v2 version of this phase — per-transaction price haggling inside
simulator-computed walls — was WRONG, on two counts the plan's own owner
caught. Strategically: when an aggregator holds the users (a ChatGPT- or
Perplexity-scale platform), per-deal negotiation becomes systematic floor
discovery — millions of negotiations reverse-engineer the concession
function, list price becomes fiction, and the merchant relives the
OTA-vs-hotel squeeze with extra steps. And internally: secret agent
concessions violate this plan's own first honesty rule. Both problems have
the same fix — **never negotiate the price; negotiate the FIT**:

- **Configuration negotiation (per transaction).** The agent may flex WHAT
  is bought — allowance mix, bundle composition, contract length, activation
  timing — against published prices. The simulator still guards the walls
  (no configuration may fall below its cost floor), but there is no secret
  number to extract: everything the agent can reach, a human can reach on
  the same shelf.
- **Channel economics (per platform, not per deal).** The commercial
  negotiation happens ONCE, in the A1 dealer agreement — commission rates,
  volume kickers, clawback windows — where the operator negotiates as a
  channel owner, not a cornered merchant. Per-deal margin never moves.
- **The published ladder.** Volume and community discounts exist as PUBLIC
  price ladders (the Community Grid unlock IS one) — visible to every agent
  and every human at once. A discount that cannot be secret cannot be
  extracted.

### The aggregator squeeze (asked and answered)

Will this backfire when the platforms have the users? The squeeze is real —
demand aggregators historically extract margin from suppliers (OTAs vs
hotels, marketplaces vs sellers). The defenses, in order of load-bearing:

1. **Price integrity is the shield, not the sacrifice.** A merchant with one
   public price everywhere cannot be arbitraged into secret concessions; the
   negotiation surface simply is not there. The honesty rule is the defense.
2. **Multi-home by construction.** The ACP surface + MCP tools mean every
   agent platform connects the same way; no single aggregator owns the
   demand pipe. Certification (A6) is granted per platform — and revocable.
3. **The direct relationship keeps the moat.** The honest game — streaks,
   referrals, community, right-plan advocacy — lives in the OPERATOR
   relationship, not the purchase transaction. An aggregator can carry the
   checkout; it cannot carry the customer's club, meter or trust.
4. **Arm the customer's side.** The proof surface serves the CUSTOMER'S
   agent as much as the aggregator's — an operator whose claims a personal
   agent can verify gets chosen by the user's own machinery, which no
   platform can disintermediate.
5. **Negotiating leverage compounds while the market is early.** Aggregator
   power in telecom subscriptions is not yet established fact; the operator
   that sets certification terms and channel economics NOW negotiates the
   A1 contract from the seller's side of history, not the supplicant's.

### A6 — The agent conformance kit (certification, inverted)

This platform's brand is passing 25 TM Forum CTKs at zero. Flip the
direction: before an agent platform is granted FULL mode, IT passes a
conformance battery — honors consent and DNC, displays the total price it
was quoted, respects idempotency, presents mandates correctly. Certified
agents appear on the proof surface; uncertified ones stay in discovery.
Nobody certifies the BUYER side yet — the operator that does defines the
bar, and gives risk-averse tenants a reason to turn the gate on at all.

### Launch economics (asked and answered)

Should agents get special discounts early, to buy traffic? No — and yes,
in the right place. Agent-EXCLUSIVE retail discounts fail four ways: they
make the one-price claim unverifiable (torching the proof-surface
position), they train the aggregator's reference price downward forever
(the OTA lesson), they buy the most churn-prone cohort there is (an agent
that came for a delta leaves on a delta — the anti-referral), and "my AI
got a better price than the shop gave me" is a brand-destroying sentence
for an honesty-positioned operator. The doctrine: **discount the channel,
never the customer-by-channel.** Buy early traffic with (1) rich,
time-boxed acquisition commissions in the A1 dealer agreement — CAC, not
retail price, with hardening and churn clawback; (2) PUBLIC launch
pricing every channel sees at once; (3) machine-UX excellence — the
easiest merchant to successfully complete outranks the marginally
cheaper one; (4) referral rewards carried by agents as mandates — the
"discount" that arrives through trust and selects the loyal cohort.

## Pricing policy is tenant CONFIG — the platform enforces honesty, not one policy

> **SHIPPED 2026-08-22:** `price-parity-mode` per tenant (uniform default |
> per-channel), the policy-service gate with teeth (channel-conditioned
> pricing rules refused in uniform mode, create AND patch), channel as a
> price-evaluation context variable (storefront sends `shop`), and the
> manifest attestation in both modes. Host-desk config (Operators pane).
> Suite `price_parity_test` — proven on a throwaway operator minted live.

The doctrine above ("discount the channel, never the customer-by-channel")
is the ADVICE this plan gives an operator. It is not a platform constraint:
this BSS is vendor-neutral infrastructure, and different CSPs in different
geographies will lawfully and rationally choose differently. The capability:

- **Price-parity mode, per tenant: `uniform` (default) | `per-channel`.**
  In per-channel mode, prices and pricing rules gain a channel dimension
  (shop · agent · dealer · telesales) — the same config-not-code seam as
  audience-scoped pricing rules today.
- **Whatever the policy, the proof surface tells the truth about it.** A
  uniform-mode tenant gets the one-price attestation; a per-channel tenant's
  proof face states "prices vary by channel" — verifiable either way. The
  honesty rule is about TRANSPARENCY OF POLICY, never enforcement of one.
- The market decides the rest: an operator whose agents-get-cheaper policy
  costs them shortlist trust will read it in their own attribution report.

## Honesty rules (non-negotiable, house style)

- **The proof surface never lies about the pricing policy.** Uniform mode
  attests one price everywhere; per-channel mode says so openly. What is
  forbidden is not differentiation — it is differentiation that hides.
- **Attribution is provable, not claimed.** An agent platform's commission
  is backed by the same order-completion evidence as a dealer's — no
  last-click mythology.
- **Born off.** Every phase is per-tenant opt-in behind the existing
  agent-commerce gate; a tenant that never turns it on never changes.
- **Humans keep the keys.** Agent-channel registration, commission terms
  and the kill switch are staff decisions with receipts — the same approval
  posture as every other automation in this platform.
