# The CSR workspace

*What an agent sees while a customer is on the line — and why it is laid out the way it is.*

The CSR console started as a proof that every door of the BSS could be worked from
one browser page. It grew a card per capability, and by the time it had forty of
them the page read like a filing cabinet: the incident banner took the top of every
screen, the customer page listed every empty section, and the three things an agent
needs first — what is wrong, what to do, what to say — were nowhere in particular.

A UX review in September 2026 said so plainly. This is what was built from it.

## The four rules

1. **What is happening now comes first.** Open tickets, orders in flight, the last
   contact — three small cards at the top of every customer, before any list.
2. **Progressive disclosure never hides a warning.** Empty sections fold into one
   line of chips ("Nothing on file: spend policies · household pool · auto top-up
   off"). A section with something in it — a barred line, an open dispute, a
   paused service — is always open. The tertiary line actions (transfer, change
   number, reset PIN) fold behind *More…*; the dangerous one (Cease) never does,
   because a control you cannot see is a control you cannot judge.
3. **The chrome is one bar.** Header under 60 px, the two navigation groups (work ·
   operations) on one line, and *one* incident line for the whole network: count,
   the newest names, services affected, since when. Details on click, dismiss for
   the session, and a new incident brings the bar back. Whether an incident touches
   *this* customer is said on the customer's page, not in the banner.
4. **Assist is grounded or it is nothing.** The panel beside the customer does not
   guess. Its situation and its recommendation come from the operational ontology;
   its summary from the copilot over the same data the agent sees; its knowledge
   from the shelf. Every recommended action was dry-run through the registry
   before it was shown, and its conditions are printed under *Why*.

## GenAlpha Assist

The right-hand panel on every customer. Five blocks, top to bottom:

| Block | Source | What it does |
|---|---|---|
| **Situation** | `GET /ontology/v1/context/customer/{id}/recommendations` | Open TMF656 problems whose `affectedObject` is one of this customer's services; paused lines; an open bill with a positive amount due. Says "nothing open" when nothing is. |
| **Recommended action** | same call | The top recommendation. A *governed action* (resume a paused line, upgrade a plan) shows its precondition verdicts, the permission and the policy answer; **Do it** executes through the ontology with the agent's own token and logs an interaction. An *explain* recommendation (explain the incident before troubleshooting, walk through the bill) opens the matching knowledge. **Not relevant** records a `suggestion.dismiss` for desk learning. |
| **Summary** | `POST /ai/...customerSummary` (`ai:use`) | The copilot's summary of the 360, on request — never auto-run, never billed silently. |
| **Offer to consider** | `aiNextBestOffer` (`ai:use`) | Weighs the shelf against the customer. Renamed from "next best offer": it is *an* offer to consider, never the first thing to do. |
| **Knowledge for this call** | knowledge shelf `csr:customers` + a search by situation ("outage", "bill") | Articles the situation asks for. *Preview* inline; *Send* posts a TMF681 communication message to the customer and logs it. |

The panel refetches when the customer changes or after any action on the page —
never on a keystroke. It folds with one click and remembers the choice for the
session.

The recommendations are ranked by the ontology (incident 1 → paused line 2 →
open bill 3 → upgrade 4), de-duplicated, capped at six. The plan upgrade only ever
proposes the next plan in the *same family* (a smartwatch line never "becomes" an
iPhone); that is the `plan-family` precondition on `upgradeSubscription`.

## The customer workspace — five areas, one context

Ivan's second review (12 September) said the cockpit was still one long page:
high-frequency context, low-frequency settings and history in a single scroll.
The page is now five stable areas under the identity bar, with GenAlpha Assist
beside every one of them:

| Area | Answers | Holds |
|---|---|---|
| **Overview** | who is this, what do they have, is anything wrong, what is in progress, what happened, what next | four summary cards (Work · Account · Service health · Last contact), the services with two direct actions each, open work, recent activity, the note line |
| **Services** | what runs and what it can do | every product with its service beneath it, all actions, upgrade options, the diagnosis, the danger zone |
| **Activity** | the customer's story | one chronological timeline across interactions, orders, tickets and ports, with filters |
| **Billing & account** | configuration and commercial state | bills and delivery, usage, cards and promotions, spend and roaming caps, agreements, pool, auto top-up, credit decisions |
| **More** | specialist, low-frequency | directory listing (with a confirmation), registry link and sync, appointments, identifiers |

```
identity ─ name · email · numbers · address · registry stamp
+ New   Ticket | Message | Order | Note          ← actions on the customer, prefilled
Overview ● | Services 3 | Activity | Billing & account | More
┌ Work ─────┐ ┌ Account ──┐ ┌ Service health ┐ ┌ Last contact ┐
│ open ticket│ │ Account OK│ │ all healthy    │ │ Line check   │
Services (≤6, two direct actions, More…)           ┃ GenAlpha Assist
Open work: tickets · orders in progress · ports · visits · cart
Recent activity (5) · View all activity →          ┃ Suggest next
[Log a contact…]
```

**Exception-first.** Normal is quiet: "No open work", "Account OK", "All 3 services
healthy". A problem is loud and carries its next step: "Paused: Mobile 20 GB —
resume under Services", "2 open bills — 799 NOK due", "Roaming: 45 of 50 EUR —
near the limit". Empty modules do not appear on the Overview at all; the Account
card says "no usage this month · 2 agreements · no cards or promos" in one line.

**Capability-driven service rows.** What a service *is* decides what it can do.
A mobile line offers Diagnose and Replace SIM directly, with Reveal PUK, Pause,
Change number, Reset PIN and Transfer under More…; a broadband line offers
Diagnose, with Pause and Transfer under More… and no SIM actions at all. A paused
line shows its actions disabled with the reason. Each row states its facts —
kind, number, data left, address — so services can be told apart without opening
anything. Twenty identical top-ups read as one row ×20. Products are the
commercial rows; the running service sits beneath the product it realises.

**The danger zone.** Cease lives at the end of the Services area, folded, with
the consequence in words before the click: what stops, which number is released
to quarantine, which agreement ends. It never sits beside Diagnose.

**Errors where they happen.** Every action carries a scope; a refusal lands next
to the block that raised it and in a toast at the top-right that cannot scroll
out of view. Cancelling an order asks the ontology first: a completed order is
refused in words on the order row, and the list refreshes. Orders, tickets,
services, ports and the cart refresh every 20 seconds while the page is open, so
a Cancel is never offered on an order that completed a minute ago.

**+ New.** Ticket, Message, Order and Note are actions on the customer, raised
from the identity bar with the customer already filled in. The note keeps its
always-visible line under Recent activity because it is the one thing agents do
on every call.

Everything a test or a colleague's bookmark pointed at is still there under the
same name; the four always-there cards (`usage-card`, `agreements-card`,
`promo-vault-card`, `suggest-card`) live in the Account card and the aside, the
line actions keep their ids, and each area is deep-linkable by hash
(`/csr/customer/{id}#services`).

## Search and the queue

**Universal search.** One box. A name or email searches parties; a phone number
finds the owner of the line; any id the caller reads out — a customer id, an order,
a ticket, a product — resolves to the customer behind it, and the page says how it
got there ("Found by ticket 3f2a…"). Rows show email, phones and city so the right
Paula can be picked without opening three of them. The six customers this agent
opened last are chips above the results.

**The queue as a queue.** A dense list — severity, issue, customer *name*, age,
time in state, status — sorted critical first then oldest, with the selected
ticket worked in a panel beside it (note, drafted reply, state moves) and one link
to the customer. What a ticket does not carry (an owner, an SLA clock) is not
invented.

## The loop — a recommendation is a decision, and its outcome is learned from

Every recommendation Assist shows is recorded as a decision of the BSS
(`decisionPoint: ontology.recommend`, source `ontology`, autonomy `assist`) in the
insight decision log, the same log the campaign and intelligence deciders write
to. What the agent does with it goes back as the decision's outcome:

| The agent… | Outcome | How |
|---|---|---|
| clicks **Do it** / **What to say** | `accepted` | `POST /ontology/v1/context/recommendations/{decisionId}/outcome` |
| clicks **Not relevant** | `dismissed` | same door |
| answers "Was this the right call?" | `helpful` / `unhelpful` | same door |

Next time the ontology ranks, it reads its own history for the desk (the tenant's
last 500 recommendation decisions, with the caller's rights) and moves a
recommendation up or down: fewer than five showings changes nothing; a
recommendation this desk keeps rejecting is ranked down (one or two steps), one
it keeps taking is ranked up. Assist says so in words under the recommendation:
*"Ranked here because: shown 43 times on this desk; taken or found helpful 1,
dismissed or found unhelpful 21 — ranked down."* The vocabulary is closed; any
other outcome is refused with a 400.

## Live intent on chat

On the Chats page, every new customer message sends the transcript through the AI
seam (`POST /ai/v1/chatIntent`, FAST tier, metered like every other call) and the
desk shows a chip — *Connection trouble · 95%* — a one-line summary, and a reply
to consider. **Use reply** puts it in the box; the agent sends it, rewrites it, or
ignores it. The intent vocabulary is closed (connectivity, billing, sim,
plan-change, cancellation, delivery, porting, other). On the stub provider a
keyword router answers deterministically, so the suite runs offline.

## Wrap-up

After-call work drafts itself: **Draft the after-call note** sends what the record
shows since the page opened — the situation Assist saw, the interactions logged
during the call, the open tickets, the recommendation taken — to
`POST /ai/v1/wrapUp` and gets back a note, a disposition (resolved, follow-up,
escalated, informational) and a follow-up. The agent edits the text and **Log the
note** writes it to the timeline as an interaction. The model only ever sees the
record; it writes nothing itself.

## Keyboard

Single keys when the caret is not in a field: `/` search customers, `t` ticket
queue, `c` chats, `k` knowledge, `n` note on this customer or ticket, `r` resolve
the ticket in front of you, `?` the sheet. Anywhere: `⌘K` (`Ctrl+K`) opens the
command palette — pages, the customers opened last, and every action visible on
the current page, typed instead of clicked — and `⌘↵` submits the field you are
in. Buttons and inputs are at least 40 px tall.

## Scenario KPIs

`ops/e2e/csr_scenarios_test.js` (#127) plays calls the way they arrive and
measures what the review asked for: time from the page opening to the first
*correct* action being on screen, and how many pages the agent had to visit.

| Scenario | First correct action | Navigations | The correct action |
|---|---|---|---|
| fibre outage on the line | ~0.8 s | 0 | explain the incident before troubleshooting |
| paused line | ~0.2 s | 0 | resume the line, then the after-call note |
| chat: "my internet is so slow" | ~1.4 s | 0 | intent = connectivity, a reply to consider |
| bill question | ~0.3 s | 0 | walk through the open bill |

The suite also proves the loop (seven rejections of one recommendation → it is
ranked down and says so) and that an outcome outside the vocabulary is refused.

## What was deliberately left for later

- **Tuning the loop's thresholds** (five showings, the ±0.2/±0.5 rates) against
  real agents. The mechanism is built and proven on seeded history; the numbers
  are a first guess.
- **Intent on voice** — the chat classifier reads a transcript; a call needs
  speech-to-text first.
- **Grouping duplicate products** on a debris-heavy demo customer. Real customers
  have three lines, not twenty-seven.

## Proof

`ops/e2e/csr_workspace_test.js` (#126): registers a customer through the shop,
orders a line, and then in a browser as `agent-anna` proves the header height,
search by order id and by ticket id, the cockpit zones and folded actions, a quiet
Assist, a critical alarm on *that* customer's service → one compact bar and an
incident situation with an explain recommendation, dismiss-for-the-session, a
paused line → *Resume* with its conditions → **Do it** resumes it and lands on the
timeline, a dismissal, an article sent to the customer's inbox, the master-detail
queue with the customer's name, and the recent-customers chips.

Regression: `csr_test`, `a11y_test` (zero axe violations), `console_sso_guard_test`,
`knowledge_test`, `porting_test`, `ontology_test`, `care_chat_test`, `copilot_test`,
`decision_log_test`.
