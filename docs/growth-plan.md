# Growth engine — measurement & journeys: research and plan

Status: **M1 + M2 delivered** (holdouts/windows/lift on campaigns; journeys with exit-on-conversion, funnel, holdout lift — suite #35 `growth_test.js`) · follow-ups: branches, A/B arms, frequency caps, ESP seam, TMF699 leads · 2026-07-15

The thesis (from the operator): a BSS holds the richest customer data in
the building — why is it only a product-and-billing system? The CVM layer
operators buy separately should be native. Campaigns and segments exist;
what's missing to be *credible* marketing is **proof** (measurement) and
**patience** (journeys). This plan covers both.

---

## 1. What the field does

### Journeys (Braze Canvas, Iterable)
Both market leaders converge on the same small vocabulary:
- A journey is a **start trigger** (event or segment entry) plus an ordered
  graph of steps: **message**, **wait/delay**, **filter/branch** (attribute
  or behavior decides the path), and **user-update**.
- **Exit rules** are always-on: the moment a customer matches them (bought
  the thing, left the segment), they leave the journey from ANY step — the
  canonical example is exiting a promo sequence on purchase, so nobody gets
  "10% off!" the day after they paid full price.
- A journey-level **conversion event** is tracked against every step, so
  the funnel (entered → step 2 → converted) reads at a glance.
- Braze's own retrospective on Canvas: teams forgot guardrails (frequency
  caps, conversion events) when they were optional — build them into the
  model, not the etiquette.

### Measurement (uplift, not vanity)
The industry's honest yardstick is **incrementality**: a randomized
**holdout** (control) group is deliberately NOT messaged; conversions are
tracked for both groups over a **conversion window** (days — lag is real);
**lift = treated conversion rate − holdout conversion rate**. Everything
else (opens, clicks, "reached") is activity, not impact. Telecom CVM lives
and dies by this because offers are expensive: a discount campaign that
"converts 8%" while the holdout converts 7.5% barely earned its margin.

## 2. Design for genalpha-bss

Everything lands in the existing **campaign** component (the martech home)
plus one listener; no new component. All of it is data in the console.

### M1 — Measurement: conversions, windows, holdouts, lift
- **Campaign fields** (data): `conversionEvent` (default
  `ProductOrderStateChangeEvent/completed`), `conversionWindowDays`
  (default 7), `holdoutPercent` (default 0).
- **Holdout at reach time**: deterministic bucket
  (`hash(campaignId+partyId) % 100 < holdoutPercent`) → the execution row
  records `variant = holdout` and NO message is sent. Same segment, same
  moment, same ledger — minus the message. That is the control group.
- **Conversion tracking**: the campaign engine already consumes the order
  event stream; a converter step marks any open execution (either variant,
  inside its window) `converted_at` + the order id.
- **The readout** (console campaign detail): reached / held out /
  conversions per variant / conversion rates / **lift**, with the honest
  footnote when the holdout is too small to mean anything.

### M2 — Journeys: sequences as data, guardrails in the model
- **Journey** = trigger (event or insight segment) + ordered **steps**
  (`message` with subject/content/promo, `wait` days/hours) + journey-level
  **conversion event** which is also the **exit rule** — a converter exits
  immediately from any step, counted as converted. Guardrails built in:
  once-per-customer enrollment (the campaign dedup pattern), and the
  journey pauses when the journey is paused.
- **Enrollment engine**: `journey_enrollment` (party, current step,
  `next_action_at`, status active/converted/exited/completed). A scheduler
  tick processes due enrollments: send the step's message, advance, park
  until the next step's time. The order-event listener marks conversions
  exactly as campaigns do.
- **Holdout applies at enrollment** — journey lift reads the same way as
  campaign lift.
- **The funnel** (console journey detail): entered / at each step /
  converted / completed-unconverted, plus lift when a holdout is set.
- v1 deliberately has no visual canvas and no branch tile: a straight
  message→wait→message sequence with exit-on-conversion covers the
  welcome series, the churn save, and the family-plan upsell — the three
  journeys that matter. Branches, A/B arms, frequency caps and quiet hours
  are the named follow-ups.

### What this buys, concretely
"Blast the Devices segment" (yesterday's feature) becomes: *enroll the
Devices segment in a two-step journey with a 10% holdout; a week later the
console says 40 entered, 4 held out, treated converted at 22%, holdout at
8% — the campaign earned 14 points of lift and the follow-up message drove
half the conversions.* That sentence is the difference between a marketing
tool and a message cannon.

## 3. Phases
- **M1 (one session)**: conversion + window + holdout + lift on campaigns;
  E2E: segment campaign with 50% holdout — holdout gets no message, both
  variants ledgered, a treated conversion counts inside the window, one
  outside doesn't, lift renders in the console.
- **M2 (one session)**: journey entity + enrollment engine + scheduler +
  exit-on-conversion + funnel; E2E: welcome journey where the converter
  exits before the follow-up and the non-converter receives it.
- **Later, named**: branch steps, A/B arms, frequency caps + quiet hours,
  revenue attribution (order value into lift), ESP delivery seam,
  TMF699 leads/opportunities for the sales funnel.
