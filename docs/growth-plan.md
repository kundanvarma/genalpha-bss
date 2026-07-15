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


---

## 4. Phase 3 — A/B arms, the ESP seam, TMF699 leads (researched 2026-07-15)

### What the field does
- **A/B arms**: platforms split the audience across up to ~3 message
  variants, pick winners by a chosen metric, and warn about sample size —
  Braze runs chi-squared at p<0.05 and refuses to crown winners on small
  samples; Mailchimp's guidance is that under ~30/arm nothing means
  anything. The mechanics: deterministic assignment, per-arm ledger,
  per-arm conversion rates, and an honest significance note.
- **ESP (Email Service Provider) delivery**: SendGrid v3 (`POST
  /v3/mail/send`, JSON: personalizations/from/subject/content), Mailgun,
  SES — and Twilio for SMS — all share the shape: an API send + an async
  status webhook. Operators already pay for one; a BSS should USE theirs,
  not become one.
- **TMF699 Sales Management**: TM Forum's own answer to the sales funnel —
  `salesLead` (the first stage of SPANCO: an interaction with a PROSPECT)
  qualifies into `salesOpportunity` (revenue potential, linked onward to
  quote and order). This is the standards story for "why can a BSS not be
  a sales tool".

### Design
1. **A/B arms (campaign component)**: optional `messageVariants`
   [{name, subject, content}] on a campaign; after the holdout carve-out,
   treated customers split deterministically across arms; executions
   record the arm; `/stats` grows per-arm sent/conversions/rate, a leader,
   and a two-proportion significance note ("too small to mean anything"
   below threshold). Journeys inherit arms later.
2. **ESP seam (communication component)** — per-tenant, like GA4/OCS/PIM:
   `DELIVERY_PROVIDER=internal|esp` + esp-url/api-key/from. In `esp` mode
   every customer message ALSO goes out via the provider (SendGrid v3
   shape), to the party''s email resolved live from party-account. In-app
   inbox stays the source of truth; the ESP send is async and fail-open.
   `mock-esp` container plays SendGrid in dev; nova rides it, genalpha
   stays internal — same binary, config apart.
3. **TMF699 leads (quote component becomes the SALES component)**:
   `/tmf-api/salesManagement/v4/salesLead` (CRUD; states acknowledged →
   qualified | unqualified) and `salesOpportunity` (created by qualifying
   a lead; developed → won | lost, optional quote ref). Capture at the
   edge: a "Talk to sales" mini-form on the storefront (anonymous, tenant
   by hostname — the marketing→sales loop closes: campaigns create
   interest, the form catches it, the console works it). Console gains a
   Sales tab (leads + qualify action + opportunities).

### E2E
- growth_test grows the A/B block: two arms over a run-unique segment,
  both ledgered, inbox subjects split, conversion attributes to the right
  arm, stats name a leader with the honesty note.
- ESP: a nova message lands in mock-esp with the right recipient;
  genalpha messages never appear there.
- sales_test.js (#36): guest submits the storefront lead → console shows
  it → qualify → opportunity → won with a quote reference; state guards.
