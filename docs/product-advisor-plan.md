# Product Advisor — enhancement recommendations + market analysis (plan)

Product owners get an advisor that reads the operator's OWN data and the
MARKET, and turns both into proposals — never actions.

1. **Deterministic findings first** (receipts, not guesses):
   - TOP-UP ATTACH: parties on plan P who also bought top-ups (billing
     rates matched against the catalog's Top-ups category) — a high
     share means P's allowance is too small: propose a bigger tier.
   - CHURN-PRICE: churn outcomes whose reason cites price, per offering
     (intelligence already keeps the goodbyes it learns from).
   - MARKET PRICE GAP: own offering vs the market feed's comparable
     offer — a rival meaningfully cheaper is a finding with the numbers.
2. **The market feed is a SEAM** (`market-feed-url`/`token` per tenant;
   mock-market as the provider) — production plugs a real market-
   intelligence subscription into the same hole.
3. **AI narrates, never decides**: the tenant's LLM seam (LlmRouter,
   fail-open to the deterministic text) writes the summary; the numbers
   are computed, not generated.
4. **Suggestions are PROPOSALS**: adopt creates a draft TMF620 offering
   (+ price) with lifecycleStatus "In study" — a human's click, the
   Copilot governance pattern. Nothing auto-applies.
5. Lives in INTELLIGENCE (the AI back-office); surfaced as a Product
   advisor tab in the admin console (catalog:write gates it — this is
   the product owner's tool). bss-intelligence gains catalog:read/write
   and billing:read.

Proof (suite #52): a run-unique cohort on a plan buys top-ups; the mock
market carries a cheaper rival; findings name both WITH their numbers;
a customer token is refused; adopting the market finding births a real
"In study" draft in the catalog; the console tab shows the findings and
adopts from a row.
