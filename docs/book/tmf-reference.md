# Appendix C — The TM Forum vocabulary, plain-English

*A companion to the memoir. Every component in genalpha-bss implements a TM Forum **Open API** — a
published, versioned REST standard for one slice of a telecom operator's business. This appendix
says, for each one, what the standard **is**, what it's **for**, and how genalpha-bss uses it — so
a reader who has never touched TM Forum can follow the whole book.*

**First, three words the book leans on:**

- **TM Forum** — the telecom industry's standards body. Among other things it publishes the
  **Open APIs**: ~60 REST specifications (each numbered `TMFxxx`) that let any vendor's software
  speak the same language for catalog, ordering, billing, assurance, and so on.
- **ODA (Open Digital Architecture)** — TM Forum's blueprint for building a BSS/OSS as a set of
  loosely-coupled **components**, each exposing Open APIs, rather than one monolith. genalpha-bss is
  a literal ODA implementation: one component per domain, one Open API each.
- **CTK (Conformance Test Kit)** — TM Forum's official test suite that proves a component actually
  conforms to a standard. When the book says a component "passes the CTK with zero failures," it
  means an independent, published test agrees it's a real TMFxxx implementation, not a lookalike.

**BSS vs OSS:** the **BSS** (Business Support System) is the commercial side — what the customer
buys, is billed for, and is served through. The **OSS** (Operations Support System) is the network
side — provisioning, activation, assurance. genalpha-bss is a BSS with a deliberately *thin* OSS
layer, and the most interesting parts of the story live at the boundary between them.

---

## Core commerce — what a customer buys

**TMF620 · Product Catalog Management** — *product-catalog.* The shop's shelf. Defines
**offerings** (what's for sale), **specifications** (what a thing is), **prices**, and **bundles**.
Everything downstream — an order, a bill, a recommendation — points back to a TMF620 offering. In
genalpha-bss it also carries the flags that drive behaviour elsewhere (is this a bundle? does it
require a verified identity?).

**TMF622 · Product Ordering Management** — *product-ordering.* The checkout and its aftermath.
Captures an **order** for catalog offerings, validates it (stock, serviceability, payment,
promotions), and orchestrates it to completion. The order is the event that sets the whole
choreography in motion — its `ProductOrderCreateEvent` is where most of Live Flow begins.

**TMF637 · Product Inventory Management** — *product-inventory.* What each customer actually
**has** right now — the running products provisioned from their completed orders. The storefront's
"My services," the CSR 360's product list, and cross-sell all read from here.

**TMF632 · Party Management** — *party-account.* **Parties**: individuals and organizations — the
people and companies the operator does business with. A customer is a party; a B2B account is a
party. Identity (who they are) is kept deliberately separate from the login credential.

**TMF666 · Account Management** — *party-account.* **Billing accounts** — the financial container a
party is billed through. One party can have several; a bill belongs to an account.

**TMF669 · Party Role Management** — *party-account.* The **roles** a party plays — customer,
partner, agent's org. How genalpha-bss scopes a CSR to their own organization's tickets and
interactions, and nothing else.

---

## Selling, serving, and supporting

**TMF687 · Stock Management** — *product-stock.* The device shelf. Physical inventory (phones)
**reserved** at order time and **consumed** at completion, so two customers can't buy the last
handset. The reason a checkout can say "out of stock" truthfully.

**TMF676 · Payment Management** — *payment.* Taking money. **Authorize** (hold funds), **capture**
(move them), **void/refund** (give them back) — behind a vendor-neutral PSP seam (a mock in dev, a
real Stripe adapter one flag away). The place idempotency lives, so a double-submit never
double-charges.

**TMF678 · Customer Bill Management** — *billing.* Producing the bill. A **billing run** rates a
customer's active inventory against catalog prices, adds metered usage and overage, applies promo
discounts, and puts it all on one bill that settles by capturing a payment.

**TMF679 · Product Offering Qualification** — *qualification.* "Can I even get this here?"
**Serviceability** — whether a fibre-class offering can be delivered at an address, checked
(anonymously) before a customer wastes time configuring something they can't have.

**TMF646 · Appointment Management** — *appointment.* Installer time slots — booked at checkout for
anything that needs a truck roll, with real capacity so a slot can sell out.

**TMF621 · Trouble Ticket Management** — *trouble-ticket.* Support cases. The customer's problem,
worked by an agent through a lifecycle (acknowledged → in progress → resolved → closed),
org-scoped so partner agents see only their own.

**TMF683 · Party Interaction Management** — *party-interaction.* The customer **timeline** — every
touchpoint (a call, a chat, a note) on one thread, so the next agent has context.

**TMF681 · Communication Management** — *communication.* Outbound notifications. Turns business
events into messages in the customer's inbox ("order received," "bill ready") — the visible end of
the invisible event chain, and the door martech and porting deliver through.

**TMF663 · Shopping Cart Management** — *shopping-cart.* Server-side carts. A guest cart identified
by a secret id, **claimed** on login, made immutable at checkout, with an abandonment sweep that
becomes a "still thinking it over?" nudge.

**TMF635 · Usage Management** / **TMF677 · Usage Consumption** — *usage.* Metering. TMF635 is the
intake and rating of usage records (data, minutes, **and AI tokens**); TMF677 is the customer-facing
"how much of my allowance have I used" view, with overage flowing onto the bill.

**TMF651 · Agreement Management** — *agreement.* **Commitments** — the 12-month term a customer
signs up to, minted automatically when an order with a term completes. The "your plan is ending
soon" that the churn engine watches for.

**TMF671 · Promotion Management** — *promotion.* Promo **codes** — anonymous validation at the shop
window, redemption tied to an order, and the discount line that lands on the bill. What campaigns
hand out.

**TMF672 · User Roles and Permissions Management** — *user-roles.* Letting a tenant's admin manage
their own staff (grant/revoke roles) over a TMF API, against **their own** identity provider — the
business-role catalog, not the IdP's plumbing.

**TMF673 · Geographic Address Management** — *geographic-address.* Address validation and
standardization at checkout, so an install or shipment goes to a real, canonical place.

**TMF680 · Recommendation Management** — *recommendation.* Cross-sell: what this customer lacks,
bundles first. In genalpha-bss the *selection* is a transparent rule and the *ranking* is a
pluggable seam (a popularity model today, a trained model tomorrow — same API).

**TMF670 · Payment Method Management** — *payment-method.* The tokenized card **vault** — save a
card at checkout (never the raw number), then pay a bill one-click later.

**TMF667 · Document Management** — *document.* The content store — tenant logos and offering artwork
the channels wear, so the shop isn't grey. The seam an operator's real CMS (Sanity, etc.) can plug
into.

---

## The thin OSS — the network side

**TMF641 · Service Ordering Management** — *service-orchestration (SOM).* The bridge from
*commercial* order to *network* work. Decomposes a product order into **service orders** and drives
them.

**TMF638 · Service Inventory Management** — *service-orchestration.* The running **services** on the
network — the thing that actually carries a phone number (drawn from a pool, or ported in).

**TMF640 · Service Activation and Configuration** — *service-orchestration.* Turning a service
**on**. In genalpha-bss this is a mock activator (a stand-in for a real network controller); the
honesty of the book is that it says so, and says exactly what a real deployment plugs in here.

**TMF685 · Resource Pool Management** — *service-orchestration.* Pools of scarce network resources —
**MSISDNs** (phone numbers) and, in the AI-slice PoC, **edge GPU** capacity — drawn from at
activation, per tenant.

**TMF642 · Alarm Management** / **TMF656 · Service Problem Management** — *assurance.* Watching the
network. TMF642 is alarm intake; a critical alarm auto-mints one TMF656 **service problem** per
affected object — the outage the CSR console shows as a banner, and the trigger for the AI-slice
self-heal.

**TMF628 · Performance Management** — *referenced, not yet built.* Network KPIs / quality-of-
experience. Flagged in the book as the missing leg that would make churn prediction truly telco-
grade (QoE is the #1 churn driver) — an honest "here's what's next."

**TMF688 · Event Management** — *referenced.* The formal Open API for the event backbone; the
transactional-outbox-to-Kafka machinery in genalpha-bss is its pragmatic implementation.

---

## The B2B and autonomy layer

**TMF648 · Quote Management** — *quote.* A priced **quote** — the commercial half of the intent
loop. In the AI-slice PoC, a quote is born from an intent, prices the OSS's proposal (including AI
metered in tokens), and, on acceptance, becomes a product order.

**TMF921 · Intent Management** — *service-orchestration.* Business **intent** — the *what*, not the
*how*. "A sub-10ms slice for the stadium with AI capacity." The OSS runs autonomous feasibility
against it and proposes services (including an edge-AI upsell) — the front door of the Catalyst
story.

---

## Beyond the standards — where genalpha-bss goes past the catalog

These components don't map to a single Open API; they're where the product play extends ODA. They
still speak the event envelope and honour the tenant, so they compose like everything else.

**campaign** (martech) — event-triggered customer journeys: a business event (or an AI churn
signal) fires a once-per-customer message carrying a promo code. Marketers run it from the console.

**intelligence** (AI) — the any-LLM seam: campaign copy assistant, CSR copilot, and a churn engine
that starts as transparent rules and *learns in production*. Per-tenant model routing, always-on
PII redaction, an audit ledger.

**porting** (MNP) — number portability: keep-your-number (port-in) and leave-with-your-number
(port-out) through a country-aware clearinghouse seam (NRDB for Norway). Where "vendor-neutral"
becomes "regulator-neutral."

**flow** (observability) — Live Flow: consumes every business event and streams the choreography to
a browser as a narrated, BPM-style process view. The component that makes the invisible visible.

**gateway** (ODA exposure) — the single front door. Routes TMF paths to components, maps hostname →
tenant for anonymous traffic, and is the one place the outside world touches.

---

## How to read a "TMFxxx" in this book

When the memoir says a component "is TMF622," it means three things at once: it exposes the TMF622
REST API (so any TM Forum-aware tool can drive it), it models the domain the way the standard says
(orders, order items, states), and — for the original five — an independent conformance kit agrees.
That's the payoff of building on the standards instead of inventing: the vocabulary is shared, the
integrations are pre-agreed, and "vendor-neutral" has teeth.
