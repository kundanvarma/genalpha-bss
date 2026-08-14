# Growth & Journeys — operator guide

Two ways to reach your customers automatically: **build it yourself** in the console, or
**describe it to the copilot** and let it draft. This guide walks both, with worked examples you
can copy. Everything lives in the console under the **Growth** workspace.

---

## 1. The mental model

| Thing | What it is |
|---|---|
| **Campaign** | One trigger → *one* message (± a promo code). The blast or the single reaction. |
| **Journey** | One trigger → a *sequence* of steps (message, wait, decision, exit) that plays out over days. |
| **Trigger** | The moment it fires — a customer registers, an order activates, a cart is abandoned, churn risk is detected. Or a **segment** blast you run on demand. |
| **Audience / segment** | Who it reaches — a browsing interest or an imported analytics audience. Always consent-gated. |
| **Holdout** | A % who get *nothing*, on purpose — the control group that lets you measure real lift instead of guessing. |

Governed by default: a per-tenant **frequency cap** and **quiet hours** (set under **Growth → Settings**)
bind every send, and consent is required to target anyone.

**Two paths:**

- **Path A — build it yourself.** Full control: pick the trigger, build the steps on a list or a
  drag-connect canvas, set the holdout, activate.
- **Path B — describe it.** Chat in plain language; the copilot asks what it needs, proposes a
  journey or campaign, and you press Create.

---

## 2. Path A — build a journey by hand

**Example: a new-customer onboarding series** — welcome them on sign-up, nudge them to activate
after 3 days, check in after a week.

1. Console → **Growth → Journeys** → the create form at the top.
2. **Name**: `New-customer onboarding`.
3. **Status**: leave `Active` to go live now, or pick `Draft` to build quietly and flip it on
   later. *Only Active runs.*
4. **Trigger event**: type `IndividualCreateEvent` — fires the moment a customer registers.
   (Leave blank if you instead want to enrol a saved segment on demand.)
5. **Steps** — build them stage by stage. Click **＋ Add step** for each; pick its type and fill
   the fields:
   - `message` — channel (in-app / email / sms / push), subject, body. Add a *stage* label like "Welcome".
   - `wait` — 3 days.
   - another `message` — "Ready to activate?".
   - `wait` — 4 days.
   - a final `message` — "How's it going?".
   - `exit` — ends the journey cleanly.
6. **Conversion = exit rule**: the event that means "goal met, stop messaging". Blank defaults to
   a completed order.
7. **Holdout %**: `10` — 10% get no messages so you can read the lift.
8. **Priority**: leave `0` unless you want this journey to compete in next-best-action arbitration
   (higher wins when two journeys would message the same person at once).
9. Press **Create**. New customers now flow through it automatically.

> **Prefer a canvas?** On the Steps builder, flip the **▦ Canvas** toggle: every step becomes a
> draggable node on a surface. Drag to lay them out, use the palette to add nodes, drag from a
> node's bottom port to another node to re-wire the order, click a node to edit it. Same journey,
> visual.

> **Under the hood:** the builder writes a `steps` array; inspect or hand-edit it under
> **Advanced: edit as JSON**. That's also where advanced touches live — like pointing a message at
> a reusable `templateRef`.

### After it's live — reading results

On the journey's row: **View** shows the funnel — how many entered, where they sit, conversions
per variant, and the **lift** (treated vs the honest holdout). **Canvas** shows the graph with live
per-node counts (reached · active) so you can see exactly where people fall out.

---

## 3. Path A — a single-message campaign

**Example: a win-back when churn risk is detected.**

1. Console → **Growth → Campaigns**.
2. **Recipe** (optional): pick *"Churn save"* to pre-fill a proven play, then edit freely.
3. **Name**: `Churn save — your points are waiting`.
4. **Trigger**: choose *"Churn risk detected (AI scorer)"* from the dropdown.
5. **Promo code** (optional): attach one — `{code}` in the body inserts it.
6. **Message subject / body**: write the copy, or click **✨ Draft** and give a one-line brief for
   the AI to draft it.
7. **A/B arms** (optional): supply 2–4 variants; treated customers split evenly and results read
   per arm.
8. **Holdout %**: `10`. Press **Create**.

For a **blast** instead of an event trigger: leave the trigger blank, put a segment name in
**Segment** (a browsing interest, or an audience from the Audiences tab), Create, then use the
row's **Execute** to reach everyone in it — once. Re-running only catches newcomers.

---

## 4. Path B — with the copilot

Console → **Growth → Growth Copilot**. Describe what you want; it asks what it needs, then proposes
a journey or campaign as a review card. You press **Create** — the copilot proposes, you decide, it
never writes on its own.

```
you  ▸  I want to welcome new customers when they sign up
bot  ▸  Onboarding fires the moment a customer registers (IndividualCreateEvent).
        Do you want a single welcome, or a short series — a welcome now, an
        activation nudge in 3 days, and a check-in after a week?
you  ▸  yes, a short series with an activation nudge and a check-in
bot  ▸  Here's a 3-stage onboarding journey. A 10% holdout measures the lift.
        Review and Create.

        ┌─ Journey — New-customer onboarding ──────────────────────────┐
        │ Trigger: IndividualCreateEvent · holdout 10% · 6 steps       │
        │ [Welcome] email · wait 3d · [Activate] email · wait 4d ·     │
        │ [Check-in] in-app · exit                          [ Create ] │
        └──────────────────────────────────────────────────────────────┘
```

Two more the copilot understands out of the box:

- "*build a win-back campaign for customers at risk of churn*" → a **campaign** proposal on
  `ChurnRiskDetectedEvent`.
- Answer its questions and it fills the rest — trigger, holdout, channels, copy.

> **Best of both:** let the copilot propose and Create, then open the journey's **Canvas** to
> fine-tune the steps visually. Draft with AI, finish by hand.

> **Honest note:** on the default demo tenant the copilot runs on a built-in deterministic model,
> so it follows scripted scenarios; point it at a real model (per-tenant config) for open-ended
> conversation. Either way, **every turn is metered and logged in the AI audit ledger** — the AI
> carries a receipt.

---

## 5. Worked examples — going further

### Branch on who they are (true multi-path)

Send VIPs down one path and everyone else down another. In the Steps JSON (or by wiring a decision
node on the canvas), a `decision` routes each side to a different node id:

```json
[
  { "id":"w",   "type":"message", "stage":"Welcome", "subject":"Welcome!", "content":"..." },
  { "id":"d",   "type":"decision", "inSegment":"VIP", "thenNext":"vip", "elseNext":"std" },
  { "id":"vip", "type":"message", "subject":"A premium welcome", "content":"..." },
  { "id":"vx",  "type":"exit" },
  { "id":"std", "type":"message", "subject":"A standard welcome", "content":"..." },
  { "id":"sx",  "type":"exit" }
]
```

VIPs get the VIP message and exit; everyone else gets the standard one — no cross-over.

### Wait for something to happen

A `waitForEvent` node parks the customer until an event arrives, with a timeout nudge if it never does:

```json
{ "type":"waitForEvent", "event":"ProductOrderStateChangeEvent", "state":"completed",
  "days":7, "onTimeout":{ "subject":"Need a hand activating?", "content":"..." } }
```

If they activate within 7 days it advances; if not, the nudge fires and the journey moves on.

### Reach them by SMS

Set a message node's **channel** to `sms` — it leaves over the SMS gateway to the number on file,
while the in-app inbox stays the record. Same for `push` and `email`.

### Let the best message win (next-best-action)

Give competing journeys a **Priority** above 0. When two would message the same customer in the
same moment, the higher priority wins and the other is held — and every decision is logged with its
reason. A priority-0 journey is always-on and never held.

---

## 6. Quick reference

### Triggers

| Event | Fires when |
|---|---|
| `IndividualCreateEvent` | a customer registers (onboarding) |
| `ProductOrderStateChangeEvent` | an order changes state (e.g. `:completed` = activated) |
| `ShoppingCartAbandonedEvent` | a cart is abandoned |
| `CustomerBillCreateEvent` | a bill is issued |
| `ChurnRiskDetectedEvent` | the AI scorer flags churn risk |
| `LoyaltyTierChangedEvent` | a loyalty tier changes |
| `TroubleTicketStateChangeEvent` | a support ticket changes state |
| `AgreementCreateEvent` | an agreement starts |

### Step (node) types

| Type | Does | Key fields |
|---|---|---|
| `message` | Sends a message | channel, subject, content, stage?, templateRef? |
| `wait` | Pauses | days / hours |
| `waitForEvent` | Parks until an event or timeout | event, state?, days, onTimeout |
| `decision` | Reads the customer, routes / picks a message | inSegment, then/else, thenNext/elseNext |
| `exit` | Ends the journey | — |

---

**Golden rules.** Keep a 10% holdout so lift is real, not a story. Set quiet hours + a frequency
cap once under **Settings** and forget them — they protect every send. And whether you build by
hand or by copilot, nothing reaches a customer until **you** press Create.

*Built on TMF Open APIs (TMF688 events · TMF681 messaging · TMF671 promotions · TMF658 loyalty).
Every capability here is verified by an end-to-end browser suite.*
