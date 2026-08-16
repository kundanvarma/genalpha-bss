# Making Opportunity + CPQ solid → deep

A staged roadmap to take the sales funnel from *MVP* (every stage has a home)
to *deep* (a sales team could actually run on it). Grounded in how the leaders
model it — Salesforce CPQ, Salesforce Industries/Vlocity CPQ (the telco
standard), Pipedrive/HubSpot pipeline management — and mapped onto the TM Forum
APIs we already conform to.

> **Honesty rule (same as the module reference):** every phase below states its
> dependency floor and what it *proves*. We don't claim "CPQ" until guided
> selling, configuration rules, and discount approvals actually run.

---

## Where we are today (2026-08)

- **Opportunity** — MVP, just shipped: pipeline stages, amount/currency, close
  date, probability (rides with the stage), owner, line items off the TMF620
  catalog, activities that mirror to the TMF683 360, a weighted-forecast
  pipeline endpoint, a kanban board, and won-by-source attribution.
- **Quote (TMF648)** — MVP: flat line items, a manually-typed price, a simple
  accept path. No catalog pricing, no configuration, no approvals, no document.

The spine is unbroken (lead → opportunity → quote → order → contract → bill),
but the opportunity is *thin-but-usable* and the quote is a *stub*.

## The reference model

**Opportunity management** (Salesforce, Pipedrive, HubSpot) is: staged pipeline,
weighted forecast, **forecast categories** (Pipeline / Best Case / Commit /
Omitted), **activities as tasks** (due dates, reminders, next step), **lead
scoring + routing**, and **funnel analytics** (stage-conversion, win rate, cycle
time, aging).

**CPQ** — Configure, Price, Quote ([Salesforce](https://www.salesforce.com/sales/cpq/what-is-cpq/)):
- **Configure** — product bundles, features/options, **configuration rules**
  (selection / validation / exclusion), and **guided selling** (a rules-based
  questionnaire that narrows to the right products).
- **Price** — catalog pricing, volume/tier pricing, segment/contract pricing,
  discounts.
- **Quote** — the quote line editor, **discount-approval workflows**, quote
  **document generation**, e-signature, and quote → order.

Telco CPQ (Salesforce Industries / Vlocity) runs the same shape off an
Enterprise Product Catalog with "lead → opportunity → quote → order → contract
→ asset-based ordering." That maps cleanly onto our TMF620 catalog + TMF622
order + TMF651 agreement.

---

## Roadmap — Opportunity

### O1 — solid  *(floor: the opportunity we have)*
- **Activities as tasks** — an activity gains `dueDate` + `status` (open/done) +
  `assignee`; a "my open tasks" queue and an overdue flag. (We log activities
  already; this makes them *actionable*, not just a log.)
- **Stage-entry timestamps + aging** — stamp `stageChangedAt`; surface "N days
  in stage" on the card and an aging report. Deals stuck in a stage are the #1
  thing a pipeline review looks for.
- **Forecast categories** — layer Pipeline / Best Case / Commit / Omitted over
  the raw probability, so a manager commits a number distinct from the weighted
  math. One enum field + a roll-up.
- *Proves:* a manager can run a weekly pipeline review off the board — what's
  committed, what's stuck, whose task is overdue.

### O2 — deep  *(floor: O1)*
- **Lead scoring + routing** — score a lead from its traits (source, company
  size, engagement from the CDP) and auto-assign to an owner by rule. Reuses the
  insight/CDP signals we already compute.
- **Funnel analytics** — stage-to-stage conversion, win rate, average cycle
  time, and a weekly **pipeline snapshot** (so forecast-over-time and slippage
  are visible). A read-model over opportunities + stage history.
- **Team / territory / quota** — owners roll up to a team; a quota per
  owner/period; quota-attainment vs the weighted forecast.
- *Proves:* the numbers a VP of Sales asks for — coverage, conversion, quota
  attainment — come out of the BSS, not a spreadsheet.

---

## Roadmap — CPQ

### C1 — solid  *(floor: TMF620 catalog + productOfferingPrice, the opportunity)*
- **Opportunity → quote hand-off** — build a quote *from* the opportunity's line
  items in one click (the deal composition becomes the quote).
- **Catalog pricing** — quote lines pull `productOfferingPrice` from TMF620
  instead of a typed number; the quote computes **recurring vs one-time** totals
  (MRR + one-off, and TCV over the term).
- **TMF648 quote lifecycle** — draft → inProgress → **presented** →
  accepted/rejected, with the state machine enforced (today it's a stub).
- **Quote document** — render a branded quote (HTML → PDF, reuse the doc/PDF
  seam the books use) the rep can send.
- *Proves:* a real, catalog-priced, sendable quote that carries the deal — not a
  hand-typed number.

### C2 — deep  *(floor: C1)*
- **Guided selling** — a short rules-based questionnaire ("how many sites? need
  static IP?") that narrows the catalog to the right offerings. A rules table
  over TMF620.
- **Configuration rules** — bundle requires/excludes, min/max quantities, and
  validation ("Static IP requires a business line"), enforced at quote build.
  Extends the bundle model the catalog already has.
- **Pricing & discount rules** — volume/tier pricing, segment/contract price
  lists, and line/deal discounts.
- **Discount-approval workflow** — a discount over a threshold routes to a
  manager (reuse the process-flow / approval engine already in the platform);
  the quote can't be presented until approved.
- **Quote → order → contract** — on acceptance, the quote drives a TMF622 order
  and a TMF651 agreement automatically (asset-based ordering); e-signature seam
  on the quote document.
- *Proves:* a complex multi-product B2B deal configured under rules, priced with
  approved discounts, and converted to an order + contract with no re-keying —
  the telco-CPQ bar.

---

## TMF mapping (what each piece rides on)

| Capability | TMF API |
|---|---|
| Sales lead + opportunity | TMF699 Sales Management |
| Quote + quote lifecycle | TMF648 Quote |
| Catalog offerings + prices, bundles, config | TMF620 Product Catalog |
| Order on acceptance | TMF622 Product Ordering |
| Contract on acceptance | TMF651 Agreement |
| Provisioned assets (asset-based ordering) | TMF637 Product Inventory |
| Activities on the customer 360 | TMF683 Party Interaction |

## Suggested sequencing

**O1 → C1 → O2 → C2.** Get the opportunity genuinely usable for a review (O1),
then make the quote real and catalog-priced (C1) — that's the highest-value pair
and unlocks a credible B2B demo. O2 (analytics/quota) and C2 (guided
selling/rules/approvals) are the "deep" tier, each a larger arc on its own.

Sources: [Salesforce — What is CPQ](https://www.salesforce.com/sales/cpq/what-is-cpq/) ·
[Salesforce Industries CPQ guide](https://www.salesforceben.com/guide-to-salesforce-industries-cpq/) ·
[Salesforce Opportunity stages](https://www.salesforceben.com/complete-guide-tutorial-to-salesforce-opportunity-stages/)
