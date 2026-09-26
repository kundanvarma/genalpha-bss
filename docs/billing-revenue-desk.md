# Billing & Revenue

The department used to be called Money, and it exposed the ledger's entities as
fourteen peer tabs. An operator had to know the internal model before they
could find a task — and worse, the same customer looked different in different
places, because a bill's lateness was read from a raw state plus whatever each
channel decided about it. An approved ten-day extension did not stop the bill
reading as overdue in Collections, on the CSR desk or in the customer's app.

This document is the Billing & Revenue arc: what a finance operator can do on
the desk today, and the one thing underneath it that made the desk possible. It
has two halves, and the order matters — the architecture came first because the
experience could not be honest without it.

**The architectural half.** A bill carries a due date, and one service computes
its **situation** from the facts. Every channel reads that one answer.
`invoice.overdue = true` is never a UX input again.

**The experience half.** The desk follows the revenue lifecycle instead of the
schema. The six destinations, in the order the money moves:
**Overview · Billing · Payments · Collections · Accounting · Configuration**.
Normal states stay calm; exceptions are loud.

---

## The architectural half: what is true about this bill right now

A bill's situation is not a field and not a flag. It is a judgement over six
facts — the bill's stored state, what has been allocated against it, whether a
promise to pay is standing, whether a dispute is open, the day it fell due, and
the tenant's today — and it is made in exactly one place:
`services/billing/src/main/java/com/bss/billing/service/BillSituations.java`.
The calculator is pure: facts in, a situation out, no repository and no clock of
its own, so every combination that changes the answer is a unit test
(`BillSituationsTest`) rather than a fleet.

There are eight situations, and their precedence is fixed. Highest first:
`writtenOff` · `paid` · `disputed` · `arrangement` · `overdue` ·
`partiallyPaid` · `outstanding` · `issued`.

Each rung is there for a reason a person can state. Written off means the money
was given up on, so nothing else can be true of it. A paid bill is never late.
A disputed amount is contested, so collection does not chase it. **Arrangement
is the rung the UX paper was about**: an approved promise to pay, still
standing, stops the bill reading as overdue — in every channel, the moment it is
granted. Overdue is past the day it is due with money still owing. Outstanding
is owed and not yet due, which is the normal, calm state and by far the most
common one.

The answer carries its own reason in operator language and the dates that
justify it — the original due date, the due date that applies *now*, and the
arrangement's date when there is one — so a screen can explain itself without
asking a second question.

### Served three ways, read four

The situation is served as a house block beside the standard TMF678 bill
(`billSituation`), on a list endpoint the desk counts with, and in the
customer-facing bill summary the app and the CSR desk already read. The
standard payload stays standard; the house field rides beside it.

Four channels read it, and each has exactly one module that turns a situation
into words and a tone:

| Channel | Its situation module |
|---|---|
| Storefront | `apps/storefront/src/pages/bills/Situation.jsx` |
| Mobile app | `apps/mobile/src/situation.js` |
| CSR console | `apps/csr-console/src/pages/customer/situation.jsx` |
| Back office | `apps/admin-console/island/src/bills/words.js` |

Each of the four knows all eight situations, and **none of them owns a clock**.
No channel file compares a due date to today; a channel that wanted to invent
its own idea of lateness would have to write date arithmetic, and the claims
gate refuses it (see *What is checked, and by what*). That is the whole point:
the definition of overdue lives in one Java file, and a new channel cannot
disagree with it by accident.

The customer's side of the same fact is deliberately gentler. Where the back
office says "Overdue", the app says "arrangement in place" when there is one —
the same situation, the operator's promise made visible to the person it was
made to.

---

## The experience half: six destinations

| Destination | What is there | Why |
|---|---|---|
| **Overview** | what needs attention, what is queued, where the book stands | the day starts with exceptions, not a table |
| **Billing** | Bills · Disputes | money owed, and the money owed that is being argued about |
| **Payments** | unapplied cash, payments received, reconciliation | money received is its own job, not a tab inside money owed |
| **Collections** | Cases · Dunning · Risk | money that is late: the case, the ladder behind it, and the score the case aggregates |
| **Accounting** | Journal · Chart of accounts | what the books say, and what the books are told to say |
| **Configuration** | financial changes, bill formats, deliveries, shadow billing | setup, off the daily path |

Four of the six are one screen each, and that screen carries its own areas —
Configuration's four chips, Accounting's two, the sections on Payments. Those
show no second row of navigation, because a choice printed twice, one line
above itself, is worse than a choice printed once. Billing and Collections are
genuinely several pages, so those do get a second row.

**Nothing moved house.** Every one of the fourteen pages keeps its path, its
title and its role gate, so `#/billFormatProfile`, the ⌘K palette and every
suite that clicks a tab by its text land exactly where they did. One name
changed, and only in the row: the collection-case list is **Cases** there, so
the row under Collections does not read "Collections".

### Role visibility

Which destinations an operator is shown is decided by the same per-page role
gates the rail has always used — a destination appears when the token can see
at least one page under it, and disappears with the last one.

| Destination | Shown to |
|---|---|
| Overview, Billing, Payments, Accounting, Configuration | `billing:admin` |
| Collections | `billing:admin` (Cases, Dunning) **or** `risk:assess` (Risk) |

Those are roles the services themselves enforce, which is the point: a tab a
token can see is a tab whose API answers it, never a button that leads to a 403.
The gate errs strict where it must — see the Accounting note under Honest limits
— but it never errs open. So finance-staff — `billing:admin`, `billing:read`,
`party:read` — is shown all six destinations and every page in them except
Risk, and a risk analyst holding `risk:assess` alone is shown Billing & Revenue
holding **Collections and nothing else**, with Risk the only page under it.
Departments are composite roles a tenant's IdP admin edits from the Staff desk,
not code.

Hiding a page is ergonomics; the 403 underneath is the security. Both are
proven, per persona, in the suites below.

---

## Overview: the day starts with exceptions

The desk's first screen answers three questions and nothing else — are we
billing correctly, are customers paying, what needs attention — and it ranks
the answers, because what an operator does next is decided by what is loud,
what is merely visible, what is calm and what is folded away:

| Tier | What is there |
|---|---|
| Loud, and leads somewhere | overdue bills, unapplied cash, a failed billing run |
| A visible queue | disputes, collection cases, payment arrangements |
| Calm status, never a warning | outstanding-but-not-due, the last run |
| Folded away | earlier runs, settled money |

A zero exception reads `✓ No overdue bills` — quiet, and it leads nowhere.

**No figure on this screen is a page of the book wearing the book's clothes.**
That distinction cost a defect worth naming: counting one page of bills said
103 overdue when 3 815 of the tenant's bills are overdue. Plausible,
actionable, and wrong. Counts are asked of the situation endpoint, which judges
every bill before it pages. And where a true total cannot be had honestly — the
*money* behind thousands of overdue bills — the screen gives the count and the
way in rather than a sum that is quietly one page wide.

---

## Billing: the list is the book

The page states one goal — every bill is right, on time and paid; watch overdue
and disputes — and then shows the book.

Every row carries the bill number, the customer, the period, the amount, the
**situation** and the due date. The bill number is the way in. There is no
per-row View button, because a row that is already a link does not need one.

The situation is not computed here. Billing computes it once, with the
precedence above, and carries its own reason and its own dates. The desk only
decides how loudly to say it:

| Situation | How it reads |
|---|---|
| Overdue, In dispute | loud — these are exceptions |
| Arrangement | warning — a promise is running |
| Outstanding, Issued, Partly paid | calm — a normal financial state, not a problem |
| Paid, Written off | quiet — settled |

Outstanding-but-not-yet-due is the common case, and it is calm. That was the
paper's sharpest point: a desk that shouts at every unpaid bill teaches its
operator to ignore it.

Four chips sit above the table — overdue, in dispute, arrangement, outstanding.
A chip filters the table to exactly what it counts. **A zero count reads as
clean**, not as a warning: the chip says "No overdue bills", with a tick, and
never a red nought. Search covers the customer, the bill number and the
account, and the placeholder says so.

### One bill, whole

The bill number opens a workspace in the same page, so the shell keeps its
navigation, its command palette and its deep link. Six sections, each a
question a person actually asks:

| Section | The question |
|---|---|
| Bill | What was charged, and why? |
| Payments | What has been paid or allocated? |
| Adjustments | What was credited, disputed, corrected or written off? |
| Delivery | Was it delivered, and through which channel? |
| Journal | Which accounting postings did it create? |
| History | Who changed what, when and why? |

Every section reads an API that already existed. Nothing here is a new store or
a new number. A section with nothing to show says so in a sentence — "Nothing
has been paid against this bill yet" — and never renders an empty box.

Identifiers are folded away under **Technical details** in each section, never
used as the headline. Services write their own prose, and that prose carries
identifiers and ISO timestamps: "Cash received — 0b3ae95d-…", "Overdue since
2026-09-23". The desk strips the identifier, says the date the way a person
says it, and tidies what removing an identifier leaves behind. It removes
identifiers only — never a word, never an amount.

---

## Payments: money received is its own job

Unapplied cash is the exception this screen is built around, and it is stated
as one: money the bank named that no bill claimed. The row carries the
service's own reason and the actions that resolve it, where the problem is.

**An action with no endpoint says so.** Refund is disabled with its reason in
its title, because billing serves no refund for cash that was never applied.
A disabled control that explains itself is honest; a control that pretends is
not.

Two defects here were the same shape as the overview's. The match form posted
`billNo` where the endpoint takes `billId` — the body was accepted, the field
ignored, and nothing ever matched; the number an operator types is now looked
up first, and an unknown one is refused in words. And the parked-payment list
is a hard top hundred with no total, so a full page is said as a floor: "at
least 100 waiting", never a count that is really a page height.

---

## Collections: a case is the unit of work

A collection case aggregates the six things the paper names — the debt, the
commitments, the stage, what has been said, what is restricted, the risk — so
one screen is the case. Each section states its facts or says plainly that
there are none.

The ladder's words are the service's own: current → reminded → warned →
restricted → suspended → terminated, plus written off. They live in one file
(`apps/admin-console/island/src/billing/ladder.js`) so the list and the case
cannot drift and no screen invents a rung. A cured case is not a separate
state — it returns to the foot of the ladder with nothing owed and a date on
it, which is why "is this case still running" is a question about the money and
not about the word. Counting by state called twenty cleared cases open.

Three more defects came from looking at the screen rather than from a suite,
and each was a reader that turned a refusal into silence:

- "Nothing is restricted" printed beside "service suspended" — the
  enforced-service list is empty on a seeded case, but a suspended customer is
  cut off. The restriction is read from the rung now.
- "Nothing has been sent about this debt" printed beside "last warned 27 Aug" —
  notices were asked for with `relatedParty.id`, which that API refuses with a
  400, which the reader turned into null, which reads exactly like a quiet
  customer. The key is `relatedPartyId`.
- Most case rows naming "this customer" — a hundred party lookups fired at once
  lose some, and the loser was cached over a real name. Eight at a time now,
  and a failed lookup is never cached.

---

## Accounting: the journal and the chart, one destination

Journal and Chart of accounts were two peer tabs among fourteen, which meant a
controller reconciling a month had to know the data model before they could
find either. They are one job — what the books say, and what the books are told
to say — so they are one page with two views.

**The journal reads as business events.** A posting is a bill issued, cash
received, a credit note raised, a handset handed over; it is a row of debits
and credits only once you ask. So the row leads with the event, and the double
entry lives one click down. Clicking the event discloses the balanced posting;
the entry identifier, the source reference and the party ride one fold further,
under **Technical details**, where a support call can still reach them.

The filter bar narrows by date range, by kind of business event and by account
code, and **the count above the table is the service's judged total for that
filter** — not the length of the page. That distinction is the whole reason the
journal needed a service change: this tenant's book holds four thousand
postings and a page serves fifty, so a page-counted figure reads as a plausible
lie. `X-Total-Count` on `/revenue/v1/journalEntry` is the answer, and the
export honours the same filter, built from one query string in one file. A
reconciliation that downloads something other than what it looked at is worse
than no export at all. Three layouts: the subledger's own, SAP-shaped and
NetSuite-shaped.

**The chart of accounts leads with what an account books.** "What customers owe
— every invoice issued lands here and every payment clears it" is the headline;
`ar` is a posting key, and a posting key is an identifier, so it sits under
technical details with the code. Each row says how many booked lines already
carry its account, because that number is what makes a change consequential.
The service counts them once, with the chart, rather than the page asking
thirty times and reading a trimmed burst as thirty unused accounts.

Nothing on this page edits anything. `+ New account` and `Propose a change`
both write a proposal and hand it to Configuration.

---

## Configuration: a ladder, because these settings move real money

Which account a kind of money books into is not a form field. A change to it is
written down, checked against the live books, signed for by name and only then
applied:

| Rung | What happens |
|---|---|
| Drafted | the proposal, with what the account says today beside it |
| Validated | the service says what it would do, and refuses what it must not |
| Approved | somebody puts their name to the consequences |
| Activated | the books change, and future postings follow |

The service refuses every step taken out of order, so the screen shows a ladder
rather than enforcing one: approve before validate is a 409, activate before
approve is a 409, and **activation revalidates against the live row** — an
approval granted yesterday cannot activate a change that became dangerous
overnight, and a stale draft that would overwrite a colleague's edit is sent
back to draft instead.

What validation refuses: an account left without a code a general ledger can
read, an account left without a name, a negative setting, a VAT rate above one
hundred percent. What it allows but says out loud: moving an account that
already carries postings — "5 booked lines keep account 2150, because a posting
keeps the code it was born with" — because booked lines keep their snapshot and
the ledger will hold both codes until the general ledger is told. That sentence
is what the approver signs for.

The same checks answer the direct `POST /revenue/v1/accountMapping` that seeds
and machine callers use, because a rule only the screen enforced would not be a
rule.

Configuration also holds the three setup pages that used to sit in the daily
path — **Bill formats** (what a country's electronic invoice is), **Deliveries**
(every bill's trip to the distribution partner, where a failure is the only
loud thing on the page) and **Shadow billing** (what would bill differently next
cycle). Each keeps its own tab, its own path and its own role gate, and lands
on its own area of the page, so every deep link and every suite still works.

---

## How it is built

Every new screen in this arc is a React island mounted inside the console's own
panel (ADR-0022), not a new single-page application and not a new route. The
vanilla shell still owns the page head, the department rail, the destination
row, the tabs and the palette; the island owns the panel beneath. Because an
island states its own goal and its own counts, the shell hides its generic
intro and its KPI chips on an island page — otherwise the page said the same
thing twice, in older words and with a different number.

The islands live under `apps/admin-console/island/src/` — `bills/`, `billing/`,
`accounting/`, `configuration/` — each file well under the 300-line front-end
rule, with the words and the tone of each desk in one place (`words.js`,
`ladder.js`) so a change of language is a change in one file.

Two seams were added to the shell for them, and only two:
`window.mountIsland` puts a React root in a panel, and `window.consoleGoTo`
is the one way into a page from outside the rail. Without the second an island
would reach into the shell's DOM and click a tab by its label. `consoleGoTo`
reads the same visibility the rail reads, so it cannot open a page the token's
roles hide.

The demo tenant's own data had to be cleaned up before the customer column on
Bills was worth looking at — three quarters of its people were dead suite runs.
The rule for removing a fixture person, and the counting mode that reports
before it acts, is in **[Demo data hygiene](demo-data-hygiene.md)**.

---

## Proof

Five suites, each driving the real console or the real channels through the
gateway with a real token.

`ops/e2e/bill_situation_test.js` (suite #233) is the arc's acceptance test: a
bill past its due date reads `overdue` in the back office, on the CSR desk and
in the customer's app; an approved extension flips all three to `arrangement`
with the new date; the dunning case stops chasing. One fact, four channels, one
answer.

`ops/e2e/bills_desk_test.js` (suite #235) drives the Bills desk: the table's
columns and the absence of a View button, the search placeholder, a zero count
reading as clean, a chip filtering to exactly its own count, all six workspace
sections answering, no identifier and no ISO date above the technical fold, and
the back link returning to the desk it left.

`ops/e2e/billing_overview_test.js` (suite #236) proves the Overview's
**ranking**, not its pixels: the headline count must equal the service's judged
total, exceptions must be loud and lead somewhere, a zero must read as a tick,
the queue must not carry the exception colour, calm lines must have no border
and no danger colour, history must stay closed, and the four tiers must appear
in that order.

`ops/e2e/accounting_configuration_test.js` (suite #238) drives Accounting and
Configuration: the journal's count equals the subledger's own total, no
identifier is visible above the technical fold, a filter narrows the screen and
the downloaded file to the same number, an account leads with what it books, a
proposal changes nothing until it is activated, approve before validate and
activate before approve are both refused by the service, a broken account code
is refused at the ladder AND at the direct remap, a product manager gets 403
both ways, and the activated change carries four names. `ChartGuardTest` covers
every input combination of the validator without a fleet.

`ops/e2e/billing_ia_test.js` (suite #240) proves the shape and the gates with
real tokens: the six primaries in lifecycle order, each destination's second
row exactly as the table above says, all fourteen old tabs still opening under
the destination they were re-homed to, finance-staff's Collections carrying
Cases and Dunning but not Risk (with `riskManagement` answering him 403), and a
narrow role landing on its own area alone. That last one grants `risk:assess`
to a CSR through the console's own TMF672 door, looks at what they see, then
takes it back and proves the department goes with the role.

`BillSituationsTest` covers every input combination that changes the situation,
without a fleet. The accessibility scan covers the new screens
(`A11Y_TARGETS=console,bills,accounting`) at zero WCAG 2.2 AA violations. All
of these sit in the **nightly** tier rather than the pull-request tier, because
the PR slice seeds the catalog and no billing, and a scan reaching for a bill
that cannot exist is a failure that says nothing. Each suite's downstreams are
recorded in `ops/e2e/suite-needs.txt`.

### What is checked, and by what

Every number and every structural claim in this document is read off the code
by `ops/arch/claims.sh`, which calls `ops/arch/billing_claims.py`. None of them
is typed from memory, and each has been watched going red:

| The claim | Where the truth lives |
|---|---|
| the eight situations and their precedence | the order of the returns in `BillSituations.java`, resolved through the constants in `BillSituation.java` |
| four channels, each knowing all eight | the four channel modules named above |
| no channel computes lateness | no `Date.now()` or bare `new Date()` in any of the four |
| the six destinations, in lifecycle order | the `groups` of the Billing & Revenue workspace in `nav.js` |
| fourteen pages under the department | that workspace's `tabs` |
| `nav.js` is at the front-end ceiling | `wc -l` against `MAX_FE_LINES` in `ops/arch/ratchet.sh` |
| every suite this document names | the file exists in `ops/e2e/` |
| every suite number it cites | no higher than the count of tracked suites |
| how many suites this section claims | the suite paths named in it |

A count is checked in **every phrasing and every file**, not only where it reads
most naturally. One fact gets said several ways — "fourteen peer tabs",
"fourteen old tabs", "fourteen pages" — and the README says the short version of
the same thing; a phrasing nobody checks is how a stale number survives the fix
to its neighbour.

The helper prints a sentinel naming how many claims it checked, and the gate
refuses a run that does not produce it — because a checker that crashes and
prints nothing reads exactly like a pass, which is the failure this whole gate
exists to prevent.

**The sentinel guard was itself broken when it was written**, and breaking it on
purpose is the only reason anyone knows. It read
`ran=$(grep -c … || echo 0)`; `grep -c` prints `0` *and* exits 1 when it finds
nothing, so the `||` appended a second `0`, the string never equalled `0`, and
the guard concluded that every crashed run had done its checks. It used `grep -q`
after that. Every claim above was then made false on purpose and watched exiting
non-zero, because a gate nobody has seen fail is not a gate.

---

## Honest limits

### The situation

- **The situation has no history.** It is computed from today's facts every
  time it is asked. "Was this bill overdue on the 14th" cannot be answered, and
  a promise to pay that has lapsed leaves no trace in the situation itself —
  only on the collection case. A dated situation ledger is a separate piece of
  work.
- **A promise to pay lives on the collection case**, so a bill whose account
  has no case cannot carry an arrangement. In practice a case exists by the
  time an extension is granted, but the coupling is real and it is the reason
  the calculator takes a date rather than asking a repository.
- **The four channels are proven to know all eight situations, not to render
  them identically.** The gate checks coverage and the absence of a clock; the
  words and the tone are deliberately each channel's own, and only the
  cross-channel suite compares what a person actually sees.

### The bill list and the workspace

- **History is assembled, not audited.** It is built from the dated facts the
  BSS records against a bill — issued, disputed, credited, paid, sent,
  answered. There is no per-field edit trail, so "who changed the due date"
  cannot be answered. A real audit log is a separate piece of work.
- **The list is one page of 100 bills.** The chips count that page, not the
  whole ledger, and they say so by counting what the table shows. Server-side
  totals and paging are not built.
- **Search is client-side** over the loaded page, for the same reason.
- **Cross-links are reads, not navigation.** The workspace shows the payment,
  the credit note, the dispute and the postings; it does not yet jump to those
  desks with the row selected.
- **Disputes and deliveries have no per-bill query.** Credit notes and journal
  postings are asked for by the bill they belong to; disputes and bill
  distributions are not, so the workspace reads those two lists and filters
  them in the browser. That is fine at demo scale and wrong at real scale. The
  fix is a filter on each endpoint, not more code here.
- **Customer names resolve one party at a time** after the table paints, so a
  slow tenant shows a placeholder in the customer column for a moment.
- **Nothing on this desk writes.** Every section reads. Raising a credit,
  settling a dispute or taking a payment still happens on its own page.

### The overview, payments and collections

- **The overdue amount is not on the overview.** The paper asks for it
  prominently; a true sum needs every one of the overdue rows, and billing
  serves no per-situation total. The count is the service's own and the card
  leads to the bills. A summary endpoint is the fix, and it is a billing
  change.
- **Unapplied cash is capped at 100** by the repository's own top-100 query,
  with no total and no paging. The screens say "at least 100" rather than
  implying a count.
- **Refund has no endpoint** for unapplied cash. Disabled with the reason, not
  invented.
- **Communications are filtered client-side** by subject against the party's
  messages; there is no per-case communication log. A case that records a
  warning with no message logged says both facts rather than only the flat
  "nothing has been sent".
- **Some seeded collection cases have no party record at all**, so those rows
  honestly read "this customer". The suite checks against what the party
  service can actually resolve rather than against a fraction.

### Accounting and Configuration

- **There is no four-eyes rule.** The ladder refuses a skipped rung, but the
  same person may draft, validate, approve and activate. Requiring a second
  name is a tenant setting this arc did not build, and the audit trail records
  who did each step, so the gap is visible rather than hidden.
- **The ladder governs the chart of accounts only.** Bill formats, delivery
  configuration and shadow billing are edited directly on the same page. They
  do not decide where money is booked, but "live financial configuration" is a
  larger set than one table and the rest of it is a follow-up.
- **The direct remap endpoint is still open.** It enforces the same blocking
  rules as the ladder, so nothing dangerous passes through it, but a seed or a
  machine caller can still change an account without the ceremony. Closing it
  means moving the seeds and `revenue_test` onto the ladder first.
- **An export carries at most ten thousand lines.** It is a reconciliation
  file, not a database dump; a larger period has to be taken in slices.
- **The journal's filters do not include the customer.** Date, kind of business
  event and account code are served; searching a party means going through the
  bill.
- **Journal and Chart of accounts still have their own tabs.** They open the
  same page on their own view, and they sit under one primary named Accounting,
  which shows no second row because the page's own chips are the choice. The
  tabs survive as the deep-link and palette contract.
- **An account's name is the tenant's, its posting key is not.** The thirty
  posting keys the subledger books against are fixed in the service; a tenant
  can rename and re-code them but cannot invent a thirty-first.

### The information architecture

- **Four destinations have no second row of navigation**, because their screen
  carries its own areas. The ticket asked for "six primaries with secondary
  navigation"; printing a row that repeats the chips already on the page would
  have honoured the words and wronged the operator. The secondary navigation
  for those four is inside the page, and the suite asserts the chips are really
  there — otherwise this would just be a row quietly deleted.
- **Unapplied cash has no seat in the row.** The Payments screen states it,
  counts it and offers the match form, so the older plain-table tab would be
  the same worklist named twice. That table is still the better page for a long
  queue — it pages and searches, where the screen shows the hundred most recent
  — and it is still reachable by path and from the palette. Merging the two is
  a follow-up.
- **The finance roles are one role.** `billing:admin` is what the billing and
  revenue services enforce for almost everything here, so a collections agent,
  a controller and an administrator are the same token today, and each is shown
  all five of its destinations. The spec's user story asking a narrow role to
  see only its areas is honoured by the mechanism, and proven with the one
  genuinely narrow finance-adjacent role that exists (`risk:assess`); splitting
  `billing:admin` into per-area authorities is a change to the services, not to
  a console, and it is not built.
- **The console's gate on Accounting is stricter than the API's.** The revenue
  service serves the journal and the chart to `billing:read`; the console shows
  them on `billing:admin`. The reason is structural: `billing:read` is part of
  the composite every self-registered customer holds, so the console cannot use
  it as a gate without opening the desk to shoppers. Erring strict hides a page
  from someone whose token would have answered — the safe direction, but **a
  read-only controller cannot be given Accounting** without `billing:admin`
  today.
- **Bills is not walled by a 403.** A bill list is `billing:read`, so the
  negative pair that proves the other destinations are really shut cannot be
  drawn there. The suite asserts the 403 where there is one to assert —
  Payments, Configuration and Risk — and says so rather than claiming a wall
  that is not built.
- **Risk still shows ids and seed epochs where customers belong.** Looking at
  the narrow persona's screen — which is Risk and nothing else — the customer
  column reads an id stub for a party that does not resolve, and a real name
  with a seed epoch glued to it for one that does. The generic table already
  asks the party service for a name and falls back to an id stub when there is
  none, so the first is the honest fallback for seeded parties with no party
  record; the second is a real name the React screens strip with `plain()` and
  the vanilla table has no equivalent for. This arc re-homed that page, it did
  not build it, and neither defect is new — but a role whose whole desk is that
  one page reads them first, so they are named here rather than left for
  someone to find. The fix is `plain()` in the shell's `partyName`, which would
  improve every generic table at once.
- **The page heading is the page's, not the destination's.** Under Collections
  the row says Cases and the heading still says Collections. `short` renames a
  page in the row only, deliberately, so the crumb and the title stay stable —
  but it does mean one screen is called two things.
- **`apps/admin-console/site/core/nav.js` is 300 lines and the front-end
  ceiling is 300 lines.** The file that carries the six destinations sits
  exactly on the limit the ratchet enforces, which is why its comments are
  tight — and it means the next department to be re-homed cannot be described
  in that file without something leaving it first. Splitting the workspace
  table out of `nav.js` is the obvious move and it is not made here. The claims
  gate checks both numbers, so this limit cannot quietly stop being true.

### Proving it

- **A suite number is prose, and there is no registry.** The gate checks that
  every suite this document names exists as a file, and that no number cited
  anywhere is higher than the count of tracked suites — but nothing binds a
  number to a name, so two documents can still claim the same one. That is not
  hypothetical: `billing_ia_test` and `party_debris_test` were both written up
  as #239 by two branches landing the same night, and this document moved the
  first to #240 because it merges second. A numbered ledger in the repository
  would make the identity checkable, and it is not built.
- **Every suite in this arc is nightly, not the pull-request tier.** They need
  billing, revenue, payment, party-account, communication, intelligence and
  user-roles; the smoke slice seeds the catalog and no billing. Nothing here is
  proven on a pull request, and that is a real gap rather than a preference —
  closing it means a smoke fleet that bills.
- **The laptop was the constraint throughout.** Several results were taken on
  the eighth or ninth attempt in a quiet window, and
  `console_workspaces_test` — seven OIDC sign-ins and the heaviest console
  suite there is — was given 90 seconds on its navigation for that reason. CI
  never comes close to either limit, but a timing limit raised to suit one
  laptop is a limit that no longer measures anything on that laptop.
