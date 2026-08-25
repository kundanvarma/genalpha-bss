# The commercial simulator — simulate the money before you move it

**Status:** COMPLETE (2026-08-25) — all plays shipped: P1-P4, migration rehearsal, shadow-operator clone + Tvilling base + walls + time compression T1-T3, prospect simulator, chaos twin, regulatory rehearsal, elasticity flywheel v1, cross-tenant priors · **Depends on:** the deterministic rating/billing/pricing engines, the event log, tenant onboarding, the Tvilling twin corpus, holdout/lift measurement

> **P1 shipped:** `POST /ai/v1/simulate/priceChange` — the real base (paginated inventory), real catalog, wholesale cost ceiling, churn-risk overlay; assumptions on the face, dated basis, persisted receipts (`price_sim_report`), and the suite proves zero production mutation. Console: a **Simulator** pane under Catalog & Pricing (catalog:write). Suite `price_sim_test`.
>
> **P2 shipped:** a product-copilot proposal that reprices an EXISTING offering arrives pre-scored — the proposal card shows subscribers, revenue delta, churn overlay and the first assumption beside the create button. Fail-soft: an uncomputable forecast attaches nothing and blocks nothing. Suite `forecast_receipt_test` (model-in-the-loop).
>
> **P3 shipped:** continuous shadow billing — a per-tenant scheduled loop re-prices a ROTATING window of the base against the current catalog and compares with the last real bill's applied-rate receipts, RATE vs RATE (proration undone from the bill period + product start date — the suite forced that lesson). Drift rows (V27+RLS) + `BillDriftDetectedEvent` + a **Shadow billing** console pane; targeted `?partyId` sweep for drill-downs. Suite `shadow_billing_test`.
>
> **P4 shipped:** the negotiation twin (`POST /usageManagement/v4/simulateWholesale` — real CDRs vs a hypothetical rate card, read-only by construction) and the prospect simulator (`POST /ai/v1/simulate/prospect` — stated assumptions only, and its first assumption admits no real data was read). Suite `negotiation_twin_test`. Console surfaces shipped 2026-08-22: the twin runs inside the **Mobile wholesale** desk (pick a rate, see the period replayed), and **Prospect sim** is a Sales pane whose scenarios persist as receipts on the shared simulator shelf. The calibration-receipts pass (linking saved reports to later measured lift) is the next arc.

> **Migration rehearsal shipped:** the parallel bill run pointed at a LEGACY
> export, before anyone migrates anything. POST `/migrationRehearsal` (billing,
> `billing:admin`) takes rows `{externalRef, offeringName, expectedMonthly}`,
> maps each against THIS catalog and prices it with the SAME engine that cuts
> real bills: matched-within-tolerance / price-differs-by-exactly-how-much /
> offering-missing — exceptions BY NAME, an honest `readyToCutOver` flag, a
> read-only promise in the assumptions, and the report persisted (V29/V30).
> Suite `migration_rehearsal_test`.

## The thesis

The telecom digital-twin market simulates **networks** — RF planning, topology,
what-if on radio changes. Nobody ships a turnkey twin of the **money**: what
happens to revenue, margin and churn if this price changes, this bundle
launches, this wholesale rate card is renegotiated, this base migrates. The
science exists (churn-aware elasticity models, Monte Carlo scenario runs) but
lives in one-off consulting projects, disconnected from the system that
executes the decision. Parallel/shadow bill runs — the industry's standard
migration de-risking ritual — are manual and one-off.

This platform is unusually placed to close that gap, because the engines are
**deterministic and event-sourced**: a simulation is not a model *of* the
system, it is the system run again on different inputs.

## The plays (in rough build order)

1. **Price-change simulator (P1).** Replay the subscriber base and recent
   usage against a PROPOSED catalog into a scratch ledger: projected revenue
   delta, margin per plan (retail minus the wholesale rate card), and which
   customers cross a pain threshold — overlaid with churn-risk scores. A
   console pane; the answer in seconds, before the change is real.

2. **Forecast receipts for governed AI (P2).** Every copilot proposal (a
   repricing, a new offering, a campaign) is auto-scored by the simulator
   BEFORE the human approval dialog — the approver sees the projected
   revenue/margin/churn delta next to the Approve button. Simulation becomes
   the governance layer's other half: an AI proposal without a forecast is
   just an opinion.

3. **Continuous shadow billing (P3).** The industry's one-off parallel bill
   run, productized as an always-on feature: every cycle the twin re-bills a
   slice of the base against tomorrow's catalog; drift raises an alert before
   an invoice is wrong. Migration mode is the same engine pointed at a legacy
   import — every subscriber lands on a plan, bills within tolerance, the
   exceptions listed by name.

4. **Wholesale negotiation twin + prospect simulator (P4).** Replay real CDRs
   against hypothetical host rate cards → margin curves per plan: the MVNO's
   renegotiation weapon. The same engine, pointed at a prospect's public
   price list and an assumed base mix, produces "your business on this BSS"
   in a first meeting — the sales tool is the demo.

## The unfair advantages

- **Shadow-operator clones, not math models.** Tenant onboarding is a form;
  a sandbox tenant with copied data runs the REAL engines. Simulation by
  cloning has zero model drift — the twin cannot disagree with production
  because it *is* production code.
- **Tvilling as the simulation substrate.** The privacy twin already
  generates structurally-true-but-fictional data — simulations (and sales
  demos of simulations) run on a realistic base with no PII processing.
- **The lift-calibrated flywheel.** Elasticity assumptions rot everywhere
  else. Here the holdout machinery MEASURES real causal lift; the gap between
  the simulator's prediction and the measured outcome retrains the priors.
  The simulator provably improves with every campaign.
- **Cross-tenant priors.** Multi-tenancy gives small operators anonymized,
  aggregate-only elasticity priors their own data cannot support.
- Smaller: a **chaos twin** (revenue impact of host-network or PSP outages,
  using the failover machinery that already exists) and **regulatory
  rehearsal** (price-notification cohorts, port-out exposure per price point).

## Honesty rules (non-negotiable, house style)

- A simulation is not a prophecy: every report names its assumptions
  (elasticity source, data window, base snapshot date) on the face of it.
- Calibration receipts: when a simulated decision later has a measured
  outcome (holdout lift, actual churn), the report is linked to the reality
  and the delta is shown — the simulator's own track record is public to its
  users.
- The scratch ledger never touches the real one; a simulation cannot mutate
  production state, only describe it.
