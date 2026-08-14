# Growth & Journey Orchestration — a marketer-friendly model

*A plan to evolve the existing `campaign` service from an engineer's trigger-and-message
record into a campaign/journey object a marketing or CX owner can actually work in.*

---

## TL;DR — the finding

We do **not** have a capability gap. `services/campaign` already runs event-triggered
campaigns, multi-step journeys (message / wait / branch), A/B arms, deterministic
holdouts with lift + z-test measurement, frequency caps, quiet hours, GA4-audience
targeting and Meta custom-audience sync. That is more rigor than most telcos ship.

What we have is an **object-shape gap**. The campaign/journey JSON was modelled the way
an engineer models a TMF resource — flat, single-channel, positional steps, copy inline
as strings — not the way a marketer or journey owner thinks. TM Forum doesn't help here:
it standardizes the *ingredients* (TMF671 Promotion, TMF658 Loyalty, TMF681
Communication, TMF688 Events) but has **no Open API for campaign or journey
orchestration** — every vendor owns that model themselves. So this plan is about
adopting the *vendor-converged* orchestration model on top of the engine we already have.

---

## 0. North Star — best-of-breed, open, vendor-neutral

genalpha-bss is open-source and built for *any* operator — not one brand. So the bar for the
journey layer isn't "good enough for us"; it's **best-of-breed for the open market**. We take
the proven ideas from the leaders — the **journey model and Journey Insights analytics from
SAS CI360**, the **over-the-top, real-time, governed orchestration posture from CSG Xponent** —
and then win on the three things a proprietary, per-message-priced, lock-in platform
structurally cannot offer:

| # | Differentiator | Why SAS/Xponent can't match it for the open market |
|---|----------------|----------------------------------------------------|
| 1 | **Open-source & vendor-neutral** (Apache-2.0, runs on your stack, no per-message pricing) | They are proprietary, seat/volume-priced, and lock-in. Out of reach for challengers, MVNEs, mid-market. |
| 2 | **AI-native by construction** — natural-language journey authoring, AI-drafted copy, AI next-best-action — **with a receipt on every AI act, audited and revocable** | They bolt AI on; we build it in *and* keep the approval keys human (the project's honest-machine posture). |
| 3 | **Governed by default** — consent-gated targeting, frequency caps, quiet hours, and holdout+lift measurement already built in | "Honest martech": you can't accidentally spam, and every send is measured against a control. Rarely default-on elsewhere. |
| 4 | **TMF-native & composable** — sits over-the-top on any BSS/OSS via Open APIs (TMF688/671/681/658) | Same over-the-top shape as Xponent, but standards-native and swappable, not a walled garden. |

The five-noun model (§2) is the table stakes we match; differentiators 1–3 are how we beat
them. This is a marketer-owned, AI-native, governed journey layer that any operator can run
for free — a thing that does not currently exist.

## 1. Current state (grounded)

Home: `services/campaign` (port 8108, `/tmf-api/campaignManagement/v4`). Collaborators:
`communication` (TMF681 delivery), `insight` (segments/consent), `promotion` (TMF671
offers), `event-hub` (TMF688 bus). Admin UI: `apps/admin-console/site/app.js`
(Campaigns / Journeys tabs). Customer sees a read-only inbox
(`apps/storefront/src/pages/Notifications.jsx`).

### What a Journey looks like today (`JourneyService.toMap`)

```json
{
  "name": "Welcome series",
  "status": "active",
  "triggerEventType": "ProductOrderStateChangeEvent",
  "triggerState": "completed",
  "segmentName": "Devices",
  "conversionEvent": "ProductOrderStateChangeEvent:completed",
  "holdoutPercent": 10,
  "steps": [
    { "type": "message", "subject": "Welcome!", "content": "...", "promotionCode": "WELCOME10" },
    { "type": "wait", "days": 3 },
    { "type": "branch", "inSegment": "Devices",
      "then": { "subject": "...", "content": "..." },
      "else": { "subject": "...", "content": "..." } }
  ]
}
```

### Why a journey owner rejects this shape

| # | Problem | What a marketer expects |
|---|---------|-------------------------|
| 1 | **Single channel.** `message` is a flat `{subject, content}` that becomes an in-app note (email only if the tenant runs an ESP). | Pick a channel per step — email / SMS / push / in-app — with fallback. |
| 2 | **Copy is inline strings** with only `{code}` substitution. Transactional copy is hard-coded Java (`EventNotificationMapper`). | Reusable **templates**, localized, with personalization tokens (`{{firstName}}`, `{{offer.price}}`). |
| 3 | **Stages are array indices** (`step0`, `step1`). | **Named stages** — "Welcome → Activate → Day-7 → Upsell" — that copy and reporting hang off. |
| 4 | **No onboarding as a concept.** Registration (`IndividualCreateEvent`) isn't even a trigger; "welcome" is a hand-built journey. | Onboarding is a first-class, templated journey you turn on. |
| 5 | **Audience is a bare string** (`segmentName`), resolved ephemerally in Insight. No saved segment, no rule builder, no attribute targeting. | Named, **saved audiences** with an editable rule tree (attributes + behavior + membership). |
| 6 | **Branch = one segment-membership check.** | Decision on any attribute/behavior predicate; wait-for-event; A/B split as a node. |
| 7 | **Thin lifecycle** (active/paused only; no draft/scheduled/archived; no delete). | Draft → scheduled → active → paused → archived, with governance. |
| 8 | **Promotion coupling is a bare `promotionCode` string.** | A typed reference to the TMF671 promotion, with its terms visible in-journey. |

The engine underneath (enrollment scheduler, holdout/lift, frequency guard, quiet hours)
is good and **stays** — we are re-shaping the object and adding channels/templates/audiences
around it, not rewriting it.

---

## 2. Research: where the industry actually landed

**TM Forum** — no campaign/journey orchestration Open API exists. TMF671 Promotion and
TMF658 Loyalty cover offers/points; TMF681 Communication is the send; SID has a *Marketing
Campaign ABE* and eTOM has *Marketing Campaign Management* processes, but those are
information/process models, not APIs. Orchestration is deliberately left to vendors — so
our own `campaignManagement` API being non-standard is normal and correct.

**Vendors converge on five nouns** (SAS CI360, Adobe Journey Optimizer, Salesforce Journey
Builder, Braze Canvas, Pega CDH):

| Noun | What it is | Vendor terms |
|------|-----------|--------------|
| **Campaign** | the initiative: goal, window, budget, KPIs — the container | all |
| **Audience** | a targetable set by rule or membership | Segment / Audience |
| **Journey** | an orchestration **graph**: entry → nodes (message · wait · wait-for-event · decision · A/B · goal/exit) | SAS Journey, SFMC Journey Builder, Braze Canvas Flow |
| **Message/Template** | per-channel, localized, tokenized content | Message / Content Block |
| **Trigger/Signal** | scheduled *or* real-time event that enters/advances a journey | Entry source / Signal |

Two maturity tiers on top: **scheduled vs real-time journeys** (SAS), and **next-best-action
arbitration** that picks the single best message across competing journeys (Pega CDH, Adobe
decisioning) rather than running each journey blind. We adopt the five nouns + real-time
now; NBA arbitration is a deliberately deferred tier (§6).

### The incumbent to benchmark against: CSG Xponent

For a CSG shop this is the most relevant reference of all. **CSG Xponent** is fundamentally a
**real-time journey orchestrator + next-best-action engine** with **omnichannel comms** (SMS,
email, RCS, push, IVR, contact centre, print, in-person), sub-second decisioning and 150+
out-of-the-box integrations, designed to **sit *above* the existing stack** — the productized
version of the over-the-top layer this programme advocates. ([CSG Xponent](https://www.csgi.com/products/xponent))

> **On the "CDP" claim:** CSG markets Xponent as including a CDP. Treat that as category
> inflation. A real CDP (Segment, Tealium, Adobe Real-Time CDP, Treasure Data) is a standalone
> identity-resolution + profile-unification + activation product. What Xponent actually has —
> and what any orchestrator needs — is a **profile of convenience for decisioning**: the
> working customer state the journey engine reads to make a call. That is exactly what our
> `insight` service is, and neither should be sold as a CDP. If an operator wants a true CDP,
> it's a separate, pluggable category.

Three things follow:

1. **Xponent is the incumbent "buy" option** for an operator's growth/journey layer, and
   architecturally it's the right shape (over-the-top, uses the systems you already have) —
   not a bolt-on. Any build case must be weighed against it honestly.
2. **CSG-internal overlap.** CSG Ascendon *also* now markets "AI-powered journey
   orchestration," so two CSG products claim journeys. Before buying, the boundary question
   must be forced: which CSG product owns journeys, and is the operator paying twice?
3. **It recalibrates our deferred tier (§6).** Xponent's real differentiation is **sub-second
   real-time next-best-action arbitration** across competing journeys — not a "CDP." So the
   honest framing of GJ1–GJ6 is: it delivers the marketer-friendly journey *model* (named
   stages, multichannel, templates, saved audiences, the orchestration graph); what it defers
   versus Xponent is the **real-time NBA/decisioning** tier, which we take on as an AI-native,
   governed capstone (§6), not as a bought black box.

genalpha-bss's role is **both** reference/benchmark *and*, uniquely, a runnable open-source
alternative: it demonstrates the marketer-friendly journey object so a buyer can hold
Xponent's pitch to a bar ("does the journey owner get this, at what cost, and how does it
resolve the Ascendon overlap?"), and for operators who can't or won't buy Xponent, it is the
free, vendor-neutral layer they run instead.

---

## 3. Target domain model (marketer-friendly, mapped to what we have)

Five objects, each owned by an existing service — no new microservice:

- **Campaign** *(campaign svc)* — container: objective, audience ref, journey ref (or a
  single blast), schedule window, budget, KPI/goal, lifecycle state. The thing a marketer
  names and reports on.
- **Audience** *(insight svc, referenced by campaign)* — saved, named, a criteria tree
  (`all`/`any` of predicates over party attributes, behavior/interest, loyalty tier, GA4
  audience). Materialized by Insight; consent-gated.
- **Journey** *(campaign svc)* — a **node graph** with **named stages**. Entry = a typed
  trigger. Nodes: `message`, `wait`, `waitForEvent`, `decision`, `split` (A/B), `goal`/`exit`.
- **MessageTemplate** *(communication svc)* — reusable, per-channel (`inApp`/`email`/`sms`/
  `push`), per-locale, with a token model and an optional promotion reference. Journeys and
  transactional notifications both draw from it (kills the hard-coded `EventNotificationMapper`
  copy).
- **Trigger** *(event-hub / campaign listener)* — the catalog of entry/advance signals,
  extended to include registration (`IndividualCreateEvent`) and usage thresholds
  (`BucketBalanceChangeEvent`), which are on the bus but not yet consumed.

---

## 4. The corrected JSON (what a journey owner should see)

```jsonc
{
  "name": "New-customer onboarding",
  "objective": "activation",
  "lifecycleState": "active",              // draft|scheduled|active|paused|archived
  "entry": {
    "type": "event",                       // event | segment | schedule
    "event": "IndividualCreateEvent",      // registration — now a first-class trigger
    "reentry": "once"
  },
  "audienceRef": "aud_new_consumers",      // typed, saved audience (not a bare string)
  "goal": { "event": "ProductOrderStateChangeEvent", "state": "completed", "windowDays": 14 },
  "holdoutPercent": 10,
  "stages": [
    {
      "key": "welcome", "name": "Welcome",
      "nodes": [
        { "type": "message", "channel": "email",
          "templateRef": "tpl_welcome",    // reusable, localized template
          "fallbackChannel": "inApp" }
      ]
    },
    {
      "key": "activate", "name": "Nudge to activate",
      "nodes": [
        { "type": "wait", "duration": "P3D" },
        { "type": "decision",             // predicate, not just segment membership
          "if": { "all": [ { "attr": "orders.count", "op": "eq", "value": 0 } ] },
          "then": [ { "type": "message", "channel": "sms", "templateRef": "tpl_activate_push" } ],
          "else": [ { "type": "exit", "reason": "already-activated" } ] }
      ]
    },
    {
      "key": "day7", "name": "Day-7 value",
      "nodes": [
        { "type": "waitForEvent", "event": "ProductOrderStateChangeEvent",
          "state": "completed", "timeout": "P7D",
          "onTimeout": [ { "type": "message", "channel": "email", "templateRef": "tpl_help" } ] }
      ]
    },
    {
      "key": "upsell", "name": "First upsell",
      "nodes": [
        { "type": "split", "arms": [                 // A/B as a node
          { "name": "A", "weight": 50, "nodes": [ { "type": "message", "channel": "email", "templateRef": "tpl_upsell_a", "promotionRef": "promo_welcome10" } ] },
          { "name": "B", "weight": 50, "nodes": [ { "type": "message", "channel": "push",  "templateRef": "tpl_upsell_b", "promotionRef": "promo_welcome10" } ] }
        ] }
      ]
    }
  ]
}
```

And a template it points at:

```jsonc
{
  "id": "tpl_welcome", "name": "Welcome — new customer",
  "channel": "email",
  "promotionRef": "promo_welcome10",
  "locales": {
    "en": { "subject": "Welcome to {{brand.name}}, {{party.firstName}}!",
            "body": "You're in. Here's {{promotion.percentage}}% off your first bundle: {{promotion.code}}." },
    "nb": { "subject": "Velkommen til {{brand.name}}, {{party.firstName}}!",
            "body": "Du er i gang. Her er {{promotion.percentage}}% på din første pakke: {{promotion.code}}." }
  }
}
```

The difference: **named stages**, **channel per message**, **template references**
(reusable + localized + tokenized), **typed entry/goal/audience/promotion refs**, and
richer node types (`decision` on predicates, `waitForEvent`, `split`). Same engine
underneath; a shape a marketer can read.

---

## 5. Phased plan (evolve the campaign service; suite per phase)

| Phase | Deliverable | Why it's first / value | Suite |
|-------|-------------|------------------------|-------|
| **GJ1 — Named stages + onboarding trigger** | Journey steps gain optional `stage {key,name}`; wire `IndividualCreateEvent` (registration) into the campaign topic list + listener; ship a pre-baked **Welcome/Onboarding** recipe in the console. Backward-compatible with today's positional steps. | Directly answers "different message at different onboarding stage" with the least code; makes onboarding real. | `journey_stages_test.js` |
| **GJ2 — Message templates + channel** | New `MessageTemplate` in `communication` (channel, locale, subject, body, tokens, `promotionRef`). Campaign/journey `message` nodes reference a template + carry `channel` (+ `fallbackChannel`). Token renderer. Inline copy still allowed as fallback. | Biggest marketer ergonomic win; reusable, localized copy; also cleans up hard-coded `EventNotificationMapper`. | `message_template_test.js` |
| **GJ3 — Multi-channel delivery** | `communication` grows a channel abstraction: in-app (have), email/ESP (have), **SMS** (Twilio wire shape, mock at `integrations/mock-sms`), **push** (stub). Per-step channel honored; quiet-hours + frequency-cap apply per channel. | Turns the template `channel` field into real sends; SMS is table-stakes for telco onboarding. | `channels_test.js` |
| **GJ4 — Saved audiences + rule tree** | New `Audience` entity (name + criteria tree: `all`/`any` of attribute/behavior/tier/GA4 predicates) evaluated by `insight`; campaigns/journeys reference `audienceRef`. Keep the old `segmentName` as a one-predicate audience. | Replaces the bare-string segment with a real, editable, saved target; unlocks attribute targeting. | `audience_builder_test.js` |
| **GJ5 — Journey graph + node types** | Upgrade `steps` → `stages[].nodes[]` with `decision` (predicate), `waitForEvent`, `split` (A/B node), `goal`/`exit`. Console renders a stage/canvas view. Migration keeps existing journeys working. | The full orchestration graph; decision-on-attributes and wait-for-event are the nodes marketers ask for. | `journey_graph_test.js` |
| **GJ6 — Lifecycle & governance** | `lifecycleState` (draft→scheduled→active→paused→archived), archive/delete API, optional approval gate; console UX for the marketer. | Makes it operable by a marketing team, not just a demo. | `campaign_lifecycle_test.js` |

Each phase is independently shippable and leaves the suite green — same arc discipline as
the wholesale and a11y work.

**GJ1–GJ6 = the model.** These bring us level with the SAS/Xponent journey *model* and make
it marketer-owned. The next block is where we become best-of-breed.

### Best-of-breed layer (what makes it lead, not just match)

| Phase | Deliverable | Inspiration → our twist | Suite |
|-------|-------------|-------------------------|-------|
| **BB1 — Journey canvas** | A visual drag-connect editor for the stage/node graph in the console (not a JSON list): entry, message, wait, decision, split, goal — with live enrollment counts per node. | SAS/Braze/SFMC canvas → ours is open and renders the *same* object the API serves, so no drift between what the marketer draws and what runs. | `journey_canvas_test.js` |
| **BB2 — Journey Insights** | Per-journey analytics beyond lift: **path/funnel, per-node drop-off, time-in-stage, channel performance**, holdout lift already there. | SAS *Journey Insights* → ours ships default-on with the honest holdout baseline, so every number is measured against a control, not vanity. | `journey_insights_test.js` |
| **BB3 — AI-native authoring** | "Describe the journey" → the copilot drafts the stage graph + channel plan + localized template copy for review; AI-suggested audience predicates. Every draft is a proposal a human approves; every AI act carries a receipt (the project's existing advisor/audit posture). | Everyone is bolting genAI on → ours is authoring *and* governed/auditable from day one, not a side panel. | `journey_copilot_test.js` |
| **BB4 — Real-time next-best-action (capstone)** | An arbitration seam: when several journeys would message the same customer, an AI scorer proposes the single best action; a human sets the arbitration policy; frequency/quiet-hours/consent still bind; every decision is logged with its reason. | Pega CDH / Adobe / Xponent decisioning → ours is open, governed, and explainable — the NBA with its receipt. | `nba_arbitration_test.js` |

BB1–BB4 are sequenced after GJ1–GJ6 (you need the model before the canvas, the canvas before
the analytics are worth reading, and all of it before arbitration means anything). BB4 is the
capstone that turns §6 from "deferred" into our one genuine leap past the incumbents.

---

## 6. The capstone tier: real-time next-best-action arbitration

Pega CDH / Adobe-style **NBA arbitration** — where a decision engine picks the single best
message for a customer across *all* competing journeys/offers in real time, instead of each
journey firing independently — is a real tier above the journey model, and a real build (a
decisioning service, propensity models, an arbitration policy). It follows GJ1–GJ6 and is
delivered as **BB4** — in scope for best-of-breed, sequenced last because it's meaningless
without the model, canvas and analytics beneath it.

This is the tier **CSG Xponent** differentiates on (sub-second cross-journey arbitration) —
see §2. For a best-of-breed *open* layer we don't defer it forever; we build it **the
AI-native, governed way** as the capstone (BB4 below): an arbitration seam where an AI scorer
proposes the next-best-action across competing journeys, every decision carries a receipt, and
a human sets the policy. That is the one place we go *beyond* matching the incumbents.

---

## 7. TMF & standards mapping

- **Orchestration (Campaign/Journey/Audience):** our own `campaignManagement` API — no TMF
  standard exists; industry-normal.
- **Offers:** TMF671 Promotion Management (`services/promotion`) — referenced, not
  re-modelled.
- **Delivery:** TMF681 Communication Management (`services/communication`) — templates live
  here.
- **Triggers:** TMF688 Event Management (`event-hub` + `bss.*.events`).
- **Loyalty signals:** TMF658 Loyalty (`LoyaltyTierChangedEvent`) as a trigger source.

The result: a marketer-owned object model, delivered on the standards-conformant plumbing
we already run.
