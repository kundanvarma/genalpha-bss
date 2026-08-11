# genalpha-bss — internal demo runbook

A rehearsable ~15–20 minute walkthrough, plus two ways to create a product
(by talking to the copilot, and by hand in the console). Every step is a real
flow that passes the E2E suite — nothing here can surprise you live.

Demo on your Mac over screen-share. Everything below assumes the local fleet
is up (`docker compose up -d`) and seeded.

---

## 0. Pre-demo reset checklist (2 minutes, do it before they join)

```bash
export PATH=/opt/homebrew/bin:$PATH
cd ~/Documents/projects/bssproject/bss-java
docker compose ps | grep -c healthy         # expect ~30 healthy
# smoke: gateway + identity answer
curl -s -o /dev/null -w 'gw %{http_code}\n' 'http://localhost:8080/tmf-api/productCatalogManagement/v4/productOffering?limit=1'
```

- Open these tabs in your browser BEFORE the call, logged in, so there's no
  fumbling: storefront, console (as `pat`), CSR (as `agent-anna`), the guided
  demo page.
- Have the demo card handy: **`4242 4242 4242 4242`** pays, anything ending
  **`0002`** declines. Promo code **`WELCOME10`**. Serviceable fibre postcodes
  start **111 / 222 / 333** (use `11122`); an unserviceable one is `99999`.

**Your safety net:** if anything wobbles live, go to
`http://localhost:8080/flow/demo.html`, sign in as `demo`/`demo`, press ▶.
Five narrated acts drive the live system on rails. Nothing is mocked.

---

## 1. The demo script — "catalog to cash, then the AI"

The spine is the customer lifecycle everyone in telecom understands, then the
AI-native differentiators. Say the **bold** line, do the rest.

### Scene 1 — The customer buys, and fulfilment is REAL (storefront) · ~5 min
URL: `http://localhost:8080/shop/`

This scene now carries the fulfilment story — each component fulfils on its own
clock, physical goods ship through a carrier, and the customer watches it happen.

**1a — eSIM: instant, no human.** Register a throwaway account. Open a **mobile
plan** → in the cart pick **⚡ eSIM**. Pay with `4242 4242 4242 4242`.
- **"Watch it activate on its own."** Open **My orders / My page**: the line is
  active with a number and meters, in seconds. **"No human touched that — an
  eSIM provisions instantly."**

**1b — Physical SIM: two clocks, one plan.** Order the same plan again, this
time pick **📦 Physical SIM** (a shipping address is required).
- On **My orders** the line shows **"📦 On its way · HJ… (Helthjem)"** with a
  real tracking number. **"Same plan, different fulfilment: the SIM is booked
  with a carrier and its number is bound before dispatch — no dead SIMs."**
- ~15 seconds later refresh: the parcel is **Delivered**, the line completes.
  **"The carrier reports delivery and the order finishes itself."**

**1c — The bundle no longer sticks (per-component fulfilment).** Order
**"GenAlpha One Home & Mobile"** → configure it (pick the phone, colour,
storage). Postcode `11122` (serviceable), pick an install slot.
(`99999` first if you like: **"unserviceable is refused at the door."**)
Pay. Then on **My orders**:
- **"This is the point."** The order shows **In progress**, and each component
  reads its own status: TV/Sports **active now**, the phone **📦 on its way**,
  fiber **🔧 install booked**. **"A bundle isn't all-or-nothing anymore — the
  digital parts light up instantly while the phone ships and the fiber waits for
  the engineer. Every line on its own clock."**

**1d — Catalog → cash.** Show the bill appear, pay it. **"Catalog → order →
per-component fulfilment → activation → bill → cash — and product modelling is
data, not code: the plan, the SIM choice, the bundle's choice groups, all TMF620
the operator edits, never a deploy."**

> If a line is stuck at *acknowledged* on your fleet, it's an OLD pre-Track-C
> order — new orders roll `acknowledged → partiallyCompleted → completed`.

### Scene 2 — The product owner creates an offer by TALKING (console) · ~3 min
URL: `http://localhost:8080/console/` as `pat@bss.local` / `pat`

1. Open the **Copilot** tab. **"A product manager describes what they want to
   sell — in words."**
2. Type (or press the 🎤 and speak): *"I want to sell a streaming service."*
3. It asks the price. Answer *"9.99 a month."*
4. A proposal card appears — spec, price, offering, the category it chose.
   **"The model proposed TMF620 payloads. It did NOT write anything — I
   decide."** Click **Yes — create it**.
5. Switch to the storefront, refresh: the new offering is live and sellable.
   **"Conversation to storefront, no deploy, no JSON. And it proposes; a human
   always confirms."**

> Do the by-hand version here too if asked — see §3 below. The contrast
> (same result, form vs conversation) is the strongest single moment you have.

### Scene 3 — AI that keeps humans in control · ~4 min
URL: `http://localhost:8080/console/` — stay as `pat`, or switch to `demo`/`demo`
for the full operator view (AI Workforce + Runbooks tabs need `demo`).

- **Product advisor** tab: **"Recommendations with receipts. Every finding is
  a number you can re-run — top-up attach counted from inventory, a market
  price gap from a feed. The AI narrates; it never invents a number. Adopt
  makes a *draft*, never a live product."**
- **AI Workforce** tab (as `demo`): **"Digital workers — containerized agents
  with revocable badges — work the same ticket queues humans do. They can't
  mark done what isn't done, they escalate honestly, and a human holds every
  approval key. The dashboard's numbers label their estimates as estimates."**
- **Runbooks** tab (as `demo`): **"This is AI learning you can read. Three
  human-confirmed diagnoses of a failure promote to a versioned runbook a
  human approves — then the next occurrence is handled with zero model calls.
  Revoke it and it goes back to asking the model. Learning as a reviewable
  artifact, not a black box."**

### Scene 4 — Marketing runs the machine, no curl (journeys / martech) · ~4 min
URL: `http://localhost:8080/console/` as `pat@bss.local` / `pat`
(or `demo`/`demo` for the full view)

The same event stream that drives fulfilment drives marketing — a marketer sets
up automation against real business moments, and it fires itself.

1. **Campaigns tab** → *New* (or pick a **recipe** — "Churn save", "Tier
   congrats" — to prefill). Set trigger **"Order placed"**, subject + message.
   **"Marketing reacts to real events — an order, a bill, an AI churn signal —
   not a nightly CSV."**
2. **Let the AI draft it.** Use the draft button; the `{code}` placeholder for a
   promo survives. Attach a promo code. **"The AI writes the copy; the marketer
   approves it. Same human-in-control pattern as everywhere else."**
3. **Save → then place a new customer's first order** (Scene 1 flow, or a quick
   guest order). The campaign **fires exactly once** via TMF681 — the reached
   counter ticks to **1**. A **second** order stays silent. **"Fires once per
   customer, idempotent, delivered through the standard communication API."**
4. **Journeys tab** (the sophisticated version): a journey is an ordered
   **sequence** (message → wait → branch) with a **holdout group** for
   **measurable lift**. Open one → the stats show *entered / held-out /
   converted / **lift in points** / revenue per customer*. **"This isn't
   send-and-hope — a control group proves the lift in money, per customer, per
   month."**
5. Pause a campaign from the GUI (button flips to **Resume**). **"On/off is a
   click, not a deploy — and Nova's tenant sees none of GenAlpha's campaigns."**

> Why it lands: it's the *same* choreography engine as Live Flow and fulfilment,
> pointed at growth. "Data, not code" holds for marketing too — a journey is
> ordered steps as JSON the marketer edits.

### Scene 5 — One build, any operator (multi-tenant punchline) · ~2 min
URL: `http://shop.nova.localhost:8080/shop/`

- **"Same binary, same deployment. This is a second operator — Norwegian,
  prices in NOK, its own catalog and customers, walled off from the first by
  row-level security."** Log in as `nils@nova.local` / `nils` to show Min side.
- Optionally the B2B side: `http://biz.nova.localhost:8080/biz/` as
  `birgit@fjellheim.no` / `birgit` — a company admin, consolidated invoice in NOK.
- **"Onboarding a new operator is a form, not a project."** (If time: the
  Operators tab in the console mints one live.)

### Optional closer — the CSR's day (if the audience is ops/care)
URL: `http://localhost:8080/csr/` as `agent-anna` / `agent`

- Search a customer → the 360 (orders, usage, agreements, bills, suggestions)
  → the copilot summarizes it → work a ticket. **"The agent sees everything,
  the AI drafts, the human sends."**

---

## 2. Persona & URL cheat-sheet

| Surface | URL | Login |
|---|---|---|
| Storefront (B2C) | `localhost:8080/shop/` | self-register, or `kai@bss.local` / `kai` (live line) |
| Back-office console | `localhost:8080/console/` | `pat@bss.local` / `pat` (product desk); `demo`/`demo` (everything) |
| Finance / Growth / Ops desks | same console | `finn` / `gro` / `omar` `@bss.local` (pw = name) |
| CSR console | `localhost:8080/csr/` | `agent-anna` / `agent` (full); `jo@bss.local` / `jo` (junior) |
| Business console (B2B) | `localhost:8080/biz/` | `bianca@acme.example` / `bianca` |
| Mobile app | `localhost:8080/app/` | `emil@acme.example` / `emil` |
| Guided demo (safety net) | `localhost:8080/flow/demo.html` | `demo` / `demo` |
| Nova storefront (NO/NOK) | `shop.nova.localhost:8080/shop/` | `nils@nova.local` / `nils` |
| Nova business console | `biz.nova.localhost:8080/biz/` | `birgit@fjellheim.no` / `birgit` |

Demo card: `4242 4242 4242 4242` pays, `…0002` declines. Promo: `WELCOME10`.
Fibre postcodes: 111 / 222 / 333.

---

## 3. Creating a product — the two ways

Both produce a genuinely sellable offering that appears in the shop
immediately, for THIS tenant only, with no deploy. Show them side by side —
it's the demo's strongest contrast.

### Way A — by talking (the copilot)
Console → **Copilot** tab (as `pat`):
1. Type or 🎤-speak: *"I want to sell a streaming service."*
2. It asks what's missing (the price). Answer in words.
3. Read the proposal card (spec + price + offering + category).
4. **Yes — create it.** Done — the executor applies the TMF620 payloads with
   *your* token; the model never writes.

### Way B — by hand (the console, no AI)
The copilot is convenience. Everything it does, a product owner can do
directly. A product is three linked TMF620 objects — **make the price first,
then the offering that references it** (a spec is optional).

Console as `pat@bss.local` (holds `catalog:write`):

**Step 1 — create the price.** Tab: **Product Offering Prices** → *New*
- Name: `StreamPlus Monthly`
- Price type: `recurring`
- Price: `9.99` `EUR`
- Charge period: `month` · Period length: `1`
- Lifecycle status: `Active`
- **Create.**

**Step 2 (optional) — create a specification** if the product has
configurable characteristics (colour, storage). Tab: **Product Specifications**
→ *New*
- Name: `StreamPlus spec`, Brand: (optional), Lifecycle: `Active`
- Characteristics (JSON): e.g.
  `[{"name":"tier","productSpecCharacteristicValue":[{"value":"HD"},{"value":"4K"}]}]`
- **Create.** (Skip this for a simple plan with no variants.)

**Step 3 — create the offering.** Tab: **Product Offerings** → *New*
- Name: `StreamPlus`
- Description: `Streaming service add-on`
- Lifecycle status: `Active`
- Specification: link the one from Step 2 (or leave empty)
- **Categories** (this drives where it shows and how it fulfils): pick one —
  - `Partner services` → activates an entitlement code, no phone number
  - `Mobile plans` → a like-for-like changeable plan
  - `TV & Add-ons`, `Devices`, `Top-ups`, `Bundles` as appropriate
- **Prices**: add the price row from Step 1
- (Devices only) also add a **Product Stock** row on the Stock tab, and
  **Serviceable Areas** for broadband, so availability is real
- (Optional) Commitment: set a 12-month term to mint an agreement at purchase
- **Create.**

**Step 4 — see it live.** Open `localhost:8080/shop/`, refresh — `StreamPlus`
is in the shop under its category, buyable now. No deploy happened.

**The one thing to say:** *"The catalog is data. A price row, an offering row,
a category — and it's selling. The copilot just writes these same rows from a
sentence; the console writes them from a form. Either way, nothing is
compiled or redeployed."*

> Why price-before-offering: the offering references price rows by id, so the
> price must exist first. Same reason a spec (if used) comes before the
> offering. The copilot handles this ordering for you; by hand, you do it.

---

## 4. The three questions you WILL get — short answers

**"Why not just use $VENDOR (Amdocs/Netcracker/etc.)?"**
*"This isn't trying to displace a tier-1 stack on feature count. It's
vendor-neutral and composable — you adopt the slices you want behind standard
TMF APIs, and it's AI-native from day one rather than retrofitted. It runs as
a full BSS for a smaller operator/MVNO, or as a layer on top of what you have."*

**"Is it production-ready?"**
*"The core is proven — 87 end-to-end suites, 25 conformance kits at zero,
crash-resumable billing, GDPR endpoints, runs on three clouds off one Helm
chart. And it's honest about the gaps: the charging system is a seam (bring
your own OCS), no tax engine, no ERP integration, no third-party pen test yet.
The capability map lists every gap as an integration plan, not a surprise."*

**"How much did this cost to build?"**
*"One person, ~2 months, working with an AI pair. ~10.6B tokens processed —
but only ~24M generated, the rest was the AI re-reading the code. It ran on a
flat consumer subscription, no metered API bills."*

---

## 5. If someone wants to click around themselves later

Local is right for a screen-share demo. When the ask becomes "send me a link I
can play with," that's the always-on hosted demo — a separate, deliberate
piece of work (the Helm chart already runs on EKS/AKS/k3s; it needs real DNS,
TLS, and the white-label hostnames wired). Don't do it under demo-day pressure.
