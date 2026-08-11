# genalpha-bss — demo cheat sheet (keep beside you)

**Card:** `4242 4242 4242 4242` pays · `…0002` declines **Promo:** `WELCOME10`
**Fibre postcode:** `11122` ok · `99999` unserviceable
**Panic button:** `localhost:8080/flow/demo.html` → `demo`/`demo` → press ▶ (5 acts, live, on rails)

---

### ① CUSTOMER BUYS — `localhost:8080/shop/` (register / browse as guest)
> "This is the operator's storefront. I'll shop as a new customer."
> *[configure bundle: phone, colour, storage]* "The bundle, its choices, the colour premium — all data the operator edits, never a deploy."
> *[postcode 11122, pick install slot]* "Serviceability is checked at the door."
> *[cart → pay 4242…]* "Now watch the order complete on its own."
> *[refresh My services — line active, number, SIM]* "No human touched that."
> *[bill appears → pay]* "Catalog → order → activation → bill → cash. Four minutes."

### ② OFFER BY TALKING — `localhost:8080/console/` → `pat`/`pat` → **Copilot** tab
> "A product manager describes what they want to sell — in words."
> *[type or 🎤: "I want to sell a streaming service"]*
> *[it asks price → "9.99 a month"]*
> *[proposal card]* "It proposed the TMF620 payloads. It did NOT write anything — I decide." *[Yes — create it]*
> *[shop → refresh → it's there]* "Conversation to storefront. No deploy, no JSON."

### ③ AI, HUMANS IN CONTROL — console as `pat` / `demo`
> **Product advisor:** "Recommendations with receipts — every number re-runnable. The AI narrates; it never invents a number. Adopt makes a DRAFT."
> **AI Workforce** *(as demo)*: "Digital workers with revocable badges work the same queues humans do. They can't fake 'done', they escalate honestly, a human holds every approval key."
> **Runbooks** *(as demo)*: "AI learning you can READ. Three confirmed diagnoses → a runbook a human approves → next time, zero model calls. Revoke it, it asks the model again."

### ④ ONE BUILD, ANY OPERATOR — `shop.nova.localhost:8080/shop/` → `nils`/`nils`
> "Same binary, same deployment. A second operator — Norwegian, prices in NOK, own catalog, walled off by row-level security."
> *[optional biz.nova.localhost → birgit/birgit]* "B2B too — consolidated company invoice in NOK."
> "Onboarding a new operator is a form, not a project."

### ⑤ (optional) CSR'S DAY — `localhost:8080/csr/` → `agent-anna`/`agent`
> *[search customer → 360 → copilot summary → work ticket]* "The agent sees everything, the AI drafts, the human sends."

---

### PRODUCT BY HAND (if asked — console as `pat`, no AI)
1. **Product Offering Prices** → New → `recurring`, `9.99 EUR`, `month` → Create
2. **Product Offerings** → New → name, **pick a Category**, attach the price → Create
3. Shop → refresh → it's selling.
> "The copilot writes these same rows from a sentence; the console writes them from a form. Nothing is compiled or redeployed — the catalog is data."

### THE THREE QUESTIONS
- **vs Amdocs/Netcracker?** "Not competing on feature count — vendor-neutral, composable, AI-native from day one. Full BSS for a smaller operator, or a layer on what you have."
- **Production-ready?** "Core is proven — 87 E2E suites, 25 CTKs at zero, crash-resumable billing, three clouds. Honest gaps: OCS is a seam, no tax engine, no ERP, no pen test yet — all in the capability map."
- **Cost to build?** "One person, ~2 months, with an AI pair. ~10.6B tokens processed, ~24M generated — flat subscription, no API bills."
