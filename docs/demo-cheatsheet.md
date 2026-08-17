# genalpha-bss — demo cheat sheet (keep beside you)

**Card:** `4242 4242 4242 4242` pays · `…0002` declines **Promo:** `WELCOME10`
**Fibre postcode:** `11122` ok · `99999` unserviceable
**Panic button:** `localhost:8080/flow/demo.html` → `demo`/`demo` → press ▶ (5 acts, live, on rails)

---

### ① CUSTOMER BUYS + REAL FULFILMENT — `localhost:8080/shop/`
> **eSIM:** *[mobile plan → cart → pick ⚡ eSIM → pay 4242…]* "Watch it activate on its own." *[My page: line active, number, meters — seconds]* "No human touched that."
> **Physical SIM + carrier pick:** *[same plan → pick 📦 Physical SIM → delivery menu shows Helthjem / Posten-Bring / PostNord → pick PostNord → pay]* *[My orders: "📦 On its way · PN… (PostNord)"]* "Operator's carrier MENU, customer's pick — the booking lands on that network." *[~15s → Delivered → completes]*
> **Pay methods:** cart offers **Card / Klarna / PayPal** (per-tenant PSP menu; Klarna books as a receivable until its payout — the honest BNPL books)
> **Family plan (per-component):** *[**Family** tab → GenAlpha Family Max → pick 1–2 lines + phone + extras → 11122 + install slot → pay]* *[My orders: In progress — TV active now · lines activating · phone shipping · fiber install booked]* "Whole family, one bill — every line on its own clock."
> *[also show: **Mobile** tab → **Compare** (data/network/roaming side-by-side) · **Devices** → filter by brand]*
> *[bill → pay]* "Catalog → per-component fulfilment → bill → cash."
> ⚠️ order stuck at *acknowledged* = an OLD pre-Track-C order; new ones roll partiallyCompleted → completed.

### ② OFFER BY TALKING — `localhost:8080/console/` → `pat`/`pat` → **Product copilot** tab
> "A product manager describes what they want to sell — in words."
> *[type or 🎤: "I want to sell a streaming service"]*
> *[it asks price → "9.99 a month"]*
> *[proposal card]* "It proposed the TMF620 payloads. It did NOT write anything — I decide." *[Yes — create it]*
> *[shop → refresh → it's there]* "Conversation to storefront. No deploy, no JSON."

### ③ AI, HUMANS IN CONTROL — `localhost:8080/console/` → `pat`/`pat` (or `demo`/`demo`)
> **Product advisor:** "Recommendations with receipts — every number re-runnable. The AI narrates; it never invents a number. Adopt makes a DRAFT."
> **AI Workforce** *(as `demo`)*: "Digital workers with revocable badges work the same queues humans do. They can't fake 'done', they escalate honestly, a human holds every approval key."
> **Runbooks** *(as `demo`)*: "AI learning you can READ. Three confirmed diagnoses → a runbook a human approves → next time, zero model calls. Revoke it, it asks the model again."

### ④ B2B SALES + CPQ — `localhost:8080/console/` → `sel`/`sel` → **Sales** / **Sales setup** desks
> **Leads → Opportunities** *[a sourced lead qualifies → opens Qualification 10% on an account; each deal has stage, value, win-prob, forecast category, owner]* "A real sales object — staged, valued, forecastable."
> **Pipeline board** *[4 stage columns, cards, live weighted forecast; DRAG Proposal → Negotiation → prob rides up, forecast re-totals]* "The forecast is arithmetic on the pipeline."
> **Mark won** *[on an opp]* "won-by-source credits the sourcing campaign; sales activity lands on the account's TMF683 360."
> **CPQ** "Quote = line items split MRR vs one-off, catalog-priced; volume tiers + SEGMENT prices off the CDP (trait beats tier); discount needs approval; e-signable. Accept → TMF622 order AND TMF651 contract in one act." ⚠️ pipeline/board/quota/rules are clickable; quote→order→contract + e-sign run via TMF648 API (console shows the result) — narrate, don't promise an Accept button.
> **Sales setup** "Lead scoring (incl. a CDP-engagement signal), routing, config/approval rules, guided selling, pricing rules — every lever is data."

### ⑤ MARKETING, NO CURL (journeys + CDP) — `localhost:8080/console/` → `mkt`/`mkt` → **Campaigns** / **Journeys** *(⚠️ `pat` can't see these — use `mkt` or `demo`)*
> **Campaigns** *[New → recipe or trigger "Order placed" → AI-draft the message (Marketing copilot), {code} survives → attach promo → Save]* "Marketing reacts to real events, not a nightly CSV."
> *[place a new customer's 1st order]* "Fires exactly once (reached=1) via TMF681; a 2nd order stays silent." *[pause from GUI → Resume]* "On/off is a click."
> **Journeys** *[open one]* "Ordered steps + a HOLDOUT group → measurable LIFT in points + revenue per customer. Not send-and-hope."
> **CDP** "Audiences from real traits off the event bus (spend/tenure/loyalty/churn-risk/region); prospects imported under CONSENT, reached only if consented; activation → hashed Custom Audience to Meta AND Google."
> **Social + attribution** "Social listening (mentions+sentiment); Social care → inbound DM becomes a TMF621 ticket, event-driven; attribution reports lift + incremental revenue — no holdout, no lift claimed."
> ⚠️ social + Meta/Google activation run against a local **mock-social** emulator (swappable seam) — connector speaks the real API shape, nothing leaves the box; a live Meta hookup is a post-demo integration. Panes empty? mock restarted → `python3 ops/seed/seed_social.py`.

### ⑥ ONE BUILD, ANY OPERATOR — `shop.nova.localhost:8080/shop/` → `nils`/`nils`
> "Same binary, same deployment. A second operator — Norwegian, prices in NOK, own catalog, walled off by row-level security."
> *[optional B2B: `biz.nova.localhost:8080/biz/` → `birgit@fjellheim.no`/`birgit`]* "B2B too — consolidated company invoice in NOK."
> "Onboarding a new operator is a form, not a project. Nova sees none of GenAlpha's campaigns."

### ⑦ (optional) CSR'S DAY — `localhost:8080/csr/` → `agent-anna`/`agent`
> *[search customer → 360 → copilot summary → work ticket]* "The agent sees everything, the AI drafts, the human sends."

### ⑧ (optional) WHOLESALE / OPEN-ACCESS — `localhost:8080/partner/` → `demo`/`demo`
> "Same platform, a retail ISP buying fibre it doesn't own — the Nordic open-access play."
> *[Check address → `5020` Bergen → owners at L2/L3 → open access catalogue → buy an L2/L3 SKU]* "Order rides MEF Sonata to the owner, comes back active on its own clock."
> *[What you owe → settlement + margin]* "Wholesale cost books to the ledger; the margin is visible."
> *[`7010` Trondheim = served by **Nova**]* "Nova (card ⑥) is the fibre OWNER — seeker + provider, one platform, cross-tenant."
> **Owner's desk:** *[`console/` → **Wholesale** desk]* onboard owner · publish L2/L3 product *realised by* a **TMF633 CFS/RFS** · paint coverage · settlement — "buyer has a portal, seller has a desk, no scripts."
> **Mobile (MVNE):** *[Wholesale desk → **Mobile wholesale**]* "light MVNO owes a host — 2nd rating pass over the same CDRs, reconciled." *[**Mobile — host (provider)**]* "we host other MVNOs at a PER-MVNO rate card (the SLA lever); COGS when we owe, revenue when we're owed. Both MVNO types."

---

### PRODUCT BY HAND (if asked — `localhost:8080/console/` → `pat`/`pat`, no AI)
1. **Product Offering Prices** → New → `recurring`, `9.99 EUR`, `month` → Create
2. **Product Offerings** → New → name, **pick a Category**, attach the price → Create
3. Shop → refresh → it's selling.
> "The copilot writes these same rows from a sentence; the console writes them from a form. Nothing is compiled or redeployed — the catalog is data."

### REAL DEVICE PHOTOS (already in — how to swap/extend; local only, never committed)
Drop photos in `ops/demo-assets/devices/` (gitignored), then
`python3 ops/seed/seed_device_content.py && python3 ops/seed/seed_demo_images.py`.
- **Whole-device (shop grid + default hero):** `samsung-galaxy-s26.png` · `iphone-17.jpeg` · `iphone-17-pro.jpeg`
- **Per-colour (configurator hero follows the pick):** `<slug>-<colour>.<ext>`, e.g.
  `samsung-galaxy-s26-icy-blue.webp` · `iphone-17-pro-deep-blue.jpeg` · `iphone-17-lavender.jpeg`
  (colours: Samsung = Phantom Black/Cream/Icy Blue · iPhone 17 = Lavender/Green · 17 Pro = Deep Blue/Silver)
> Any colour without a photo keeps a generated render. Repo-safe: folder is gitignored — copyrighted photos never leave your machine.

### THE THREE QUESTIONS
- **vs Amdocs/Netcracker?** "Not competing on feature count — vendor-neutral, composable, AI-native from day one. Full BSS for a smaller operator, or a layer on what you have."
- **Production-ready?** "Core is proven — 136 browser E2E suites (174 total), 25 CTKs at zero, crash-resumable billing, three clouds, a campaign-day browse cache for the Black-Friday surge. Honest gaps: OCS is a seam, no tax engine, no ERP, no pen test yet — all in the capability map."
- **Cost to build?** "One person, ~2 months, with an AI pair. ~10.6B tokens processed, ~24M generated — flat subscription, no API bills."
