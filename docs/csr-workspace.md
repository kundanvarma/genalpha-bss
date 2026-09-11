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

## The cockpit

```
identity ─ name · email · numbers (6, +N more) · address · registry stamp
┌ Right now ───────────────┐
│ open tickets │ orders in flight │ last contact │
└──────────────────────────┘
Lines & health   n lines · active · paused · over allowance
  Services ...  [Upgrade options] [Reveal PUK] [Diagnose] [Pause] [More…] [Cease]
Products & money   usage · agreements · bills · promotions & payment
  (policies / pool / auto top-up / credit — open when they hold something, chips when not)
Orders & visits    orders (newest 6, "Show all") · number porting · appointments · cart
Timeline           tickets · interactions (5 at a time)
Registry tools     (party:write)
                                                      ┃ GenAlpha Assist
                                                      ┃ Suggest next
```

Everything a test or a colleague's bookmark pointed at is still there under the
same name: the always-rendered cards (`usage-card`, `agreements-card`,
`promo-vault-card`, `suggest-card`, `porting-card`), the copilot and offer blocks
(now inside Assist), and every line action. Only the arrangement changed.

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

## What was deliberately left for later

- **Live intent** while the customer is typing in chat, and **explainable
  feedback** on why an offer ranked where it did. Both need the Assist panel to
  exist first; they are P2 in the review.
- **AI wrap-up** (draft the interaction note from the call). The copilot can
  already draft a ticket reply; wrap-up waits for the timeline to carry chat
  transcripts.
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
`knowledge_test`, `porting_test`, `ontology_test`.
