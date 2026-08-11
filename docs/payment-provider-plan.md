# Bring-your-own PSP + redirect/BNPL methods (Klarna, PayPal)

**Status:** PSP-P1 shipped (2026-08-11) · P2–P4 design · **Depends on:** `services/payment` (PspAdapter seam), the checkout flow, the Integrations console tab

> **PSP-P1 shipped (the marketplace half):** per-tenant `payment_provider_config` (V6 + V7 RLS, `secret_ref` not the key) + `PspRegistry`/`PspRouter` — a tenant charges through its default configured PSP, else the deployment's global PSP (unchanged). **No change to the `authorize/capture/refund` signatures** — the adapters became always-on beans (Mock + Stripe) and only *which* adapter answers changed; capture/refund resolve the same provider that authorized. `PspConfigController` GET/PUT/DELETE `/paymentProvider`; the Integrations **Payment card is live**. Proven: config CRUD + RLS + secret-ref, and the money path intact **both bound and unbound** (global fallback); all 12 payment module tests green (fixed a stale docker-only migration-count assertion, now 7). Visible multi-PSP routing lands with the second successful adapter — **Klarna (PSP-P2)**.

Two asks in one, mirroring the carrier arc: let an operator **choose their payment
providers per tenant** (the marketplace half — identical to CMS/carriers), and let a
shopper **pick a payment method at checkout** including **redirect/BNPL** methods like
Klarna and PayPal (the real work — the equivalent of pickup points for delivery).

---

## 1. What we found (grounded in the code)

- **The PSP seam already exists** — `PspAdapter` (`authorize` / `capture` / `refund` /
  `provider`) with `MockPspAdapter` + `StripePspAdapter`. Chosen by a **global env**
  (`bss.payment.psp`), **not** per-tenant (no config table; payment migrations at V5).
- **The interface already anticipates a redirect.** `PspAdapter.Authorization` carries
  `requiresAction` + `actionUrl` (built for 3-D Secure / BankID), and
  `PaymentService` already acts on it: `if (auth.requiresAction()) throw new
  ScaRequiredException(auth.actionUrl())`. So the *shape* Klarna needs is present —
  but the **mock never triggers it**, so it's exercised by nothing.
- **The storefront is card-only and synchronous.** Checkout authorizes via
  `authorizePayment` → `paymentMethod:{@type:'bankCard'}` → `paymentRefs` on the order.
  `App.jsx` handles the **OIDC login** redirect return (and a `PAYMENT_REQUIRED` "back to
  cart" case) — but there is **no payment-provider redirect/return handler**. The
  `actionUrl` goes nowhere today.
- **No per-tenant payment config, no payment webhook** endpoint.

So: the seam + the redirect *hook* exist; per-tenant selection, a method picker, the
redirect round-trip, a webhook, and a Klarna adapter are what's missing.

## 2. Best practice (researched, cited)

- **Klarna's flow** ([Klarna docs][klarna]): the server **creates a session** → gets a
  `client_token` → the customer approves via the **inline widget (JS SDK)** or a **hosted
  redirect** → an `authorization_token` → the server **authorizes the order** with a
  confirmation URL → **capture on fulfilment**, refunds via order management. `intent`
  distinguishes one-time vs recurring. It is *not* a synchronous card auth.
- **Payment orchestration** ([Stripe][stripe-orch], [PayPal][pp-orch], [Paddle][paddle]):
  one merchant API → many PSPs, abstracting each provider's auth flow and data format;
  **routing rules** (region / currency / amount / method → processor); **payment-method
  selection** at checkout (reorderable per market); **failover/retry** to a backup PSP.
  Intelligent routing lifts auth rates 5–11pp and cuts cost. This is exactly the
  "bring-your-own PSP + method choice + routing" vision, industry-validated.

## 3. The design

### Part A — per-tenant PSP config (the marketplace half; ≈ carriers/CMS)
A `payment_provider_config` table (RLS, per-tenant): enabled providers, each with
`base_url`, `secret_ref` (env var name — never the key), enabled methods, default. A
`PspRegistry` resolves adapters by name at runtime; the **global env stays the fallback**
(single-tenant deploys unchanged). The **Integrations → Payment card goes live** (list /
enable / configure), exactly like the CMS and Logistics cards. *This part is a near-copy
of the carrier work.*

### Part B — payment-method selection + the redirect/BNPL round-trip (the real work)
1. **A redirect-capable seam.** Card PSPs stay on `authorize/capture/refund`. Redirect/BNPL
   providers need a session shape, so add a sibling capability (a `RedirectPspAdapter` or
   two methods on the registry entry):
   `createSession(amount, currency, method, returnUrl) → {sessionRef, redirectUrl|clientToken}`
   and `confirm(sessionRef, token) → Authorization`. The existing
   `requiresAction`/`actionUrl` is the seed — this formalises the session + confirm.
2. **A payment-method picker at checkout** — Card / Klarna / PayPal — the *same shape as
   the delivery-method picker*, built from the tenant's enabled methods (a
   `GET /payment/methods` face, anonymous for guest checkout). Card keeps the inline form;
   a redirect method shows "Continue to Klarna".
3. **The round-trip.** For a redirect method: create a session → **redirect** the customer
   (or render the widget) → they approve → **return** to a payment return URL → **confirm**.
   `App.jsx` grows a payment-return handler mirroring the OIDC one it already has. The cart
   survives the hop (it already survives the login redirect).
4. **A webhook** — `POST /payment/webhook/{provider}` (HMAC-verified) — the authoritative
   async confirmation/capture signal, the same pattern as the CMS and carrier webhooks
   already shipped.
5. **`KlarnaPspAdapter`** — create session, authorize order, capture, refund — proven
   against a **`mock-klarna`** (session → hosted approve page → webhook/return confirm),
   real credentials as config (same honesty as the real-Strapi proof).

### Part C — orchestration (reach; optional, later)
Routing **rules** (region / currency / amount / method → PSP) and **failover** to a backup
PSP on a retryable decline — the payment analogue of carrier postcode routing. Method
availability per tenant/market. Deferred behind Parts A/B.

## 4. Phasing

- **PSP-P1 — per-tenant PSP config + live Integrations Payment card.** The BYO half; mirrors
  carriers. Global env fallback. (Small.)
- **PSP-P2 — method picker + redirect round-trip + Klarna, mock-proven.** The core: the
  `GET /payment/methods` face, the checkout picker, `createSession`/`confirm`, the
  `App.jsx` return handler, the `/payment/webhook/{provider}`, `KlarnaPspAdapter`,
  `mock-klarna`. An E2E suite (card still works; Klarna redirect→confirm→order). (The
  substantial arc.)
- **PSP-P3 — BNPL order management + settlement.** Capture-on-ship (fulfilment's shipped
  event captures the Klarna order), refunds via credit notes, and the revenue subledger's
  settlement view (Klarna pays the merchant, collects from the customer later — a different
  cash-timing than cards).
- **PSP-P4 — orchestration + PayPal.** Routing rules + failover, a second redirect adapter
  (PayPal), method reordering per market.

## 5. Scope boundaries (honest)

- **No generic connector for PSPs.** Money is not fire-and-forget; every provider is a
  **named adapter**, per-tenant *selected* by config but *integrated* in code. (Same call
  the operator-console doc made — the one seam a generic connector is unsafe for.)
- **The redirect round-trip is genuinely new.** The interface has the hook and the service
  throws `ScaRequiredException(actionUrl)`, but the storefront wires only OIDC redirects
  today. The payment redirect + return + webhook confirm is real build, not a config flip.
- **Real Klarna needs a merchant account + sandbox credentials.** Proven against
  `mock-klarna`; real creds are config (base_url + secret_ref), no code change — the
  real-container proof is an opt-in follow-up, like real Strapi.
- **Recurring/subscription via Klarna** (for the monthly bill, not just the one-time order)
  is a bigger topic — billing does recurring **card** via saved methods today; Klarna
  tokenised/recurring is PSP-P3+ and scoped separately, not assumed.
- **Settlement timing differs** — Klarna pays the merchant and collects from the customer
  later; the subledger's cash vs receivable postings change for BNPL (PSP-P3), not ignored.
- **PCI posture improves, not worsens** — redirect/BNPL keeps the sensitive auth at the
  provider; cards are still never stored.

## 6. Decisions to lock

- **6a. Redirect vs inline widget for Klarna** — hosted redirect (simpler, robust) vs the
  inline JS-SDK widget (nicer UX, more client code). → *Proposed: hosted redirect first,
  widget as an enhancement.*
- **6b. Interface shape** — extend `PspAdapter` with `createSession`/`confirm` vs a sibling
  `RedirectPspAdapter` capability. → *Proposed: sibling — card PSPs shouldn't grow session
  methods they don't use.*
- **6c. Config home** — a `payment_provider_config` table vs the shared `integration_binding`
  (operator-console 7b). → *Proposed: follow `content_provider_config`/`carrier_config`
  shape now; converge later.*
- **6d. Confirm authority** — return-URL vs webhook. → *Proposed: both, **webhook is
  authoritative** (a customer who closes the tab before returning must still get confirmed).*
- **6e. Method availability** — enabled methods per tenant (Card/Klarna/PayPal) as config,
  like carrier `methods`. → *Proposed: yes.*

---

[klarna]: https://docs.klarna.com/payments/web-payments/integrate-with-klarna-payments/integrate-via-sdk/how-to-integrate-klarna-payments/
[stripe-orch]: https://docs.stripe.com/payments/orchestration
[pp-orch]: https://www.paypal.com/us/brc/article/what-is-payment-orchestration
[paddle]: https://www.paddle.com/blog/payment-orchestration
