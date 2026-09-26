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

**Universal search, typed (2026-09-26).** One box — the prompt now says what it
searches and nothing else: *Search customers, orders, tickets, subscriptions…* —
and every result says **what it is** before it says anything else. Results are
grouped under **Customers**, **Subscriptions**, **Orders**, **Tickets**,
**Devices**, each row badged with its own type, because "open this" is only safe
when the agent knows what they are opening. A reference the caller reads out is
probed against every object type at once and each hit becomes its own row, plus a
row for the customer behind it: the agent chooses the object instead of being
routed to a guess. Opening one lands on that object's own place — an order or a
ticket on the customer's **Activity**, a subscription on **Services**, a device
on its own agreement on the device desk, never on a list to search again. A phone
number returns the line it runs on *and* who holds it. An empty result says why,
and how the other types are found.

**A row an agent can tell apart, and hit.** The row element *is* the anchor — the
full width of the list, at least 40 px tall, with a visible hover and a 2 px
focus ring — so pointer or keyboard, the thing you aim at is the thing you open.
`↓` from the box walks the results, `↑` walks back, `Enter` opens, and `Tab`
reaches them too. Each customer row carries what tells two similar people apart:
the customer reference, whether they are a person or a business and their
household role, the email, up to two phones, the town, and how many
subscriptions actually run — the demo tenant genuinely holds pairs who share an
email *and* a name, and that pair is now separable from the results alone. It
stops there on purpose: identification and fast selection, not a mini dashboard.
The six customers this agent opened last are still chips above the results.

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

## Typed search (2026-09-26) — CSR-UX-002 and CSR-UX-003

`ops/e2e/csr_typed_search_test.js` (#243) seeds **two customers with the same
given name, the same family name and the same email address**, one holding a
subscription and one holding none, and then proves in a browser as `agent-anna`
through the gateway: the simple prompt; both twins found from that one email and
separable by reference, phone and subscription count; the row is an `<a>` that
spans the list, hover repaints it, a click 8 px from its right edge opens that
customer; `↓` focuses the first result with a measured 2 px `:focus-visible`
ring, `↓ Enter` opens the second, `Tab` reaches rows as well; an order, a ticket
and a subscription reference each landing in the group that names them and on the
right area of the right customer; a phone number returning the line and its
holder; an empty result explaining itself; and, as `demo` (the device desk needs
`device:read`), a device-agreement reference opening **that** agreement on the
device desk with a way back to the list. The suite deletes its two customers and
their subscription and then asserts that nothing named after the run is left —
four suites used to leak customers into every agent's search results.

### Honest limits

- **Free text reaches customers only.** No component offers a text search over
  orders, tickets or catalogue offerings — `?q=` is a party-account feature, and
  `productOffering?name=` is an exact match. Those types are therefore found by
  the reference the caller reads out, and the empty state says so rather than
  implying the search looked and failed.
- **Catalogue products are not a result type.** There is no reference lookup for
  an offering that has an agent-side destination; the six types the review names
  are five here.
- **`agent-anna` cannot see device results** — she has no `device:read`, so the
  device probe returns nothing for her and the group never appears. That is the
  role model working, not a bug, and it is why the device half of the suite signs
  in as `demo`.
- **The subscription count is a second round trip** per visible row (capped at
  eight), read off a `fields=id,status` projection. A slow product inventory
  shows the row without its count rather than delaying identification.
- **Natural-language search is not here.** It was explicitly future work in
  #147, and it builds on this typed-result model: a model chooses the type and
  the reference; the rows stay what they are.

## Situation, summarised (2026-09-15)

Assist's Situation block renders the ontology's `summary` — one line per kind
with a severity — so five paused lines read "5 services are paused", an agreed
promise to pay reads "a payment plan is agreed — 1110.66 NOK by 2026-09-25;
nothing else is due until then" instead of "overdue", and info lines say "no
action needed". The customer's Home reads the same summary with their own
token. Customers' verdicts on offers (maybe later, not interested) land in the
same decision log the desk's ranking learns from.

## Wayfinding: the brand goes home, help never dead-ends (2026-09-26)

Two findings from the interaction review of the agent console (spec #144), the
two that were pure dead ends.

**The brand is the way home** (#145). The logo sat where every web application
puts "home" and was wired to nothing — an image and two spans. It is now one
link over the whole brand (logo, area wordmark, org badge) to the desk's default
workspace, which is Customers today and can become a home page later without the
affordance changing. The accessible name is the *tenant's* brand name from the
gateway's per-channel config — `MyGenAlpha CSR home` on the default host,
`Taranga CSR home` on Taranga's — never a name compiled into the build. It is the
first stop on Tab and draws a 3 px outline when a keyboard puts focus on it. The
logo keeps the `onError` fallback that hides it for a tenant with no logo
document.

**Help never opens into a dead end** (#151, the immediate half). The drawer used
to answer *"No help written for this page yet."* — an expectation created and
broken in one sentence, with nowhere to go. It now offers the next step: search,
ask where the agent holds `ai:use`, and questions that belong to the screen the
agent is standing on (Customers asks about a suspended service and an eSIM swap;
Tickets asks about escalation and handover). Picking one fills the search box,
which both searches and arms Ask.

Underneath it was, on the first screen an agent sees, a typo: the drawer built
its shelf tag with `.replace('customer', 'customers')` over the whole path
segment, so the landing page asked the knowledge base for `csr:customerss` and
the article tagged `csr:customers` was never found. The dead end the review
photographed was help that existed and could not be reached.

### Proof

`ops/e2e/csr_wayfinding_test.js` (#242), in a browser as `agent-anna` through the
gateway: the brand is exactly one `<a>` with the logo and wordmark inside it and
nothing of the brand outside it; its accessible name follows a rewritten tenant
config, so no constant can pass; the first Tab lands on it with `:focus-visible`
and an outline that goes from `none` to solid; Enter goes home and so does a
click on the logo image itself; a broken logo still hides itself and the link
survives it. Then: the landing screen shows its authored article instead of the
dead end, an unmatched search offers a next step and suggestions rather than an
apology, picking one arms the search and Ask, the suggestion set differs between
Customers and Tickets, and a screen with no article at all (`csr:migrations` on
the demo fleet) still opens on *How can I help?* with somewhere to go.

Both halves were watched failing first: with the brand reverted to a `<div>` the
suite stops on "the brand is not an `<a>`", and with the old sentence restored it
stops on "the old dead-end sentence is still rendered".

### Honest limits

- The larger half of #151 — the contextual knowledge assistant that reads the
  customer and service in front of the agent and guides troubleshooting — is
  **not** built. This is the empty-state half only.
- The suggested questions are a hand-written list per screen, not learned from
  what agents actually ask. The knowledge-gap ledger already records unanswered
  questions; nothing yet feeds it back into this list.
- The destination is still Customers. A home or dashboard page for the desk is
  its own ticket.
- Only the CSR console's brand is a link. The admin, business, dealer and
  partner consoles still have the same dead logo.

## The customer stays in front of the agent; an action belongs to its row (2026-09-26)

The second pair from the UX review of the agent console (#144): CSR-UX-004
(#148) and CSR-UX-005 (#149). They are one change because they land in the same
two files, and because they answer two of the review's three questions — *which
customer am I working on* and *what will this action change*.

### The customer identity strip

The workspace's top bar was sticky. The customer header was not, so the one fact
an agent must never lose scrolled away exactly when they were deep in a list of
services, bills or orders taking consequential actions. It is now a compact
sticky strip directly under the top bar, and it carries only what a decision
needs:

- **who** — name, and the account reference an agent reads out (the full id is
  on hover, never on the screen);
- **what kind of customer** — Consumer or Business, off the party record's
  organisation, never guessed from the name;
- **what state** — active, all services paused, no running service, deceased;
- **how to reach them** — email, their first numbers, address where it may be
  shown, and the re-verify-against-the-register action that used to live here;
- **whether the caller has been identified** — a red *identity not verified*
  until the agent checks the caller and says what they checked; then a green
  chip naming the check and the time. The check is written to the customer's
  record as an interaction, not only to the screen;
- **two numbers that change the next move** — active services, and money
  outstanding. Both are buttons into the area that owns them.

Everything deeper stays where it was. A strip that grows into a summary page
stops being an anchor, so nothing else was allowed in.

The strip's `top` is measured from the header at runtime rather than pinned to a
constant: the header wraps on a narrow desk, and a constant would be wrong
exactly when it mattered.

### An action inside a row must apply to that row

`upgradeButton(p)` was rendered on **both** branches of a service row — on the
running service, and on a commercial product with nothing running under it. So
"Upgrade options" appeared on objects that could not take the journey, and a
button inside a row is read as being about that row.

It is now three things instead of one:

- a row with a running service under an active product offers the journey
  **scoped to that object and named for it** — *Change plan* on a line,
  *Change package* on TV;
- a product row with nothing running offers *View product* — what the customer
  bought — and nothing that pretends a service exists;
- the **generic** journey, where no object has been chosen yet, moved up to the
  Services header as *Add or upgrade services*, which lists the objects that
  can change and a way to order something new.

And the answer follows the row too: the options card now renders under the row
whose button opened it, the same rule the diagnosis report was fixed to in #142.

### Proof

`ops/e2e/csr_customer_context_test.js` (#244), in a browser as `agent-anna`
through the gateway with a real token. Position and applicability are the point,
so both are measured, not eyeballed:

- the workspace is scrolled ~2 900 px and the strip is still on screen, clear of
  the top bar, with the identity chip inside the viewport;
- the customer and the identity check survive Overview → Services → Activity →
  Billing & account;
- every button in every row is checked against its row: a plan change needs both
  a running service and the product it realises, a line check needs a line, and
  the words "Upgrade options" may not appear on a row at all;
- the options card is asserted to be the **next sibling** of the row that asked.

Both checks were watched failing on purpose, inside the run itself: the strip is
switched to `position: static` and must measure off screen, and the old
"Upgrade options" button is injected back into a product row and must be caught.
A run where either negative control stays green fails the suite.

### Honest limits

- **The identity check is a desk fact, not a party attribute.** It lives in the
  agent's session (and the interaction log), because no component stores an
  identity-verification state for a party yet. It survives a reload and every
  area, not a new browser session or a second agent. A stored, expiring
  verification with a level — and sensitive actions *refusing* until it holds —
  is the next step; today the strip makes the state loud, and nothing blocks.
- **No electronic identity.** The checks offered are the ones an agent can
  actually do on a call. BankID/eID is a seam that does not exist, so it is not
  in the list.
- **Customer status is derived, not declared.** `Individual` carries no status
  field; the word comes from the registry flags and the running services. A
  declared lifecycle state on the party would be better.
- **The row's kind is still guessed from its name.** *Change package* only
  appears on TV because four regular expressions say a name looks like TV. That
  is #143, sequenced ahead of #150, and deliberately untouched here.
- **The contextual sets in #149 are not all built.** *Add data*, *Add channels*,
  *Add mesh point* and add-on *Manage/Remove* have no action behind them today;
  a button that cannot keep its promise is worse than no button, so only the
  actions that exist are on the rows.
- The strip wraps to two lines on a customer with a long contact line. Nothing
  is hidden, but it costs vertical space on every page of the workspace.
