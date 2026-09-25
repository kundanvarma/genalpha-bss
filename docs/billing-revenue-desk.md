# The Billing & Revenue desk

The department used to be called Money. It is now Billing & Revenue, and its
first page is Bills. This document covers what a finance operator can do there
today: read the book at a glance, and open one bill and understand it without
visiting four other modules.

It answers the two complaints the Billing & Revenue UX paper made loudest — a
list that makes you compute lateness in your head, and a financial event you
can only understand by touring the system.

## Bills: the list is the book

The page states one goal — every bill is right, on time and paid; watch overdue
and disputes — and then shows the book.

Every row carries the bill number, the customer, the period, the amount, the
**situation** and the due date. The bill number is the way in. There is no
per-row View button, because a row that is already a link does not need one.

The situation is not computed here. Billing computes it once, in one place,
with a fixed precedence — written off, paid, disputed, arrangement, overdue,
partly paid, outstanding, issued — and carries its own reason and its own
dates. The desk only decides how loudly to say it:

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

## One bill, whole

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

## How it is built

Both screens are React islands mounted inside the console's own panel
(ADR-0022), not a new single-page application and not a new route. The vanilla
shell still owns the page head, the department rail, the tabs and the palette;
the island owns the panel beneath. Because an island states its own goal and
its own counts, the shell now hides its generic intro and its KPI chips on an
island page — otherwise the page said the same thing twice, in older words and
with a different number.

The Bills desk is the first real desk to convert. The islands live in
`apps/admin-console/island/src/bills/`, each file well under the 300-line rule,
with the words and the tone of the desk in one place (`words.js`) so a change
of language is a change in one file.

## Accounting: the journal and the chart, one destination

Journal and Chart of accounts were two peer tabs among ten, which meant a
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

## Six destinations, and who may stand in each

The desk exposed fourteen peer tabs, which meant an operator had to know the
ledger's data model before they could find a task. It reads as the revenue
lifecycle now:

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

**Nothing moved house.** Every one of the fourteen keeps its path, its title
and its role gate, so `#/billFormatProfile`, the ⌘K palette and every suite
that clicks a tab by its text land exactly where they did. One name changed,
and only in the row: the collection-case list is **Cases** there, so the row
under Collections does not read "Collections".

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
proven, per persona, in the suite below.

## Proof

`ops/e2e/billing_ia_test.js` (suite #239) proves the shape and the gates with
real tokens: the six primaries in lifecycle order, each destination's second
row exactly as the table above says, all fourteen old tabs still opening under
the destination they were re-homed to, finance-staff's Collections carrying
Cases and Dunning but not Risk (with `riskManagement` answering him 403), and a
narrow role landing on its own area alone. That last one grants `risk:assess`
to a CSR through the console's own TMF672 door, looks at what they see, then
takes it back and proves the department goes with the role.

`ops/e2e/bills_desk_test.js` (suite #235) drives the real console with a real
token: the table's columns and the absence of a View button, the search
placeholder, a zero count reading as clean, a chip filtering to exactly its own
count, all six workspace sections answering, no identifier and no ISO date
above the technical fold, and the back link returning to the desk it left.

The accessibility scan covers both screens (`A11Y_TARGETS=console,bills`). They
sit in the nightly tier rather than the pull-request tier, because the PR slice
seeds the catalog and no billing, and a scan reaching for a bill that cannot
exist is a failure that says nothing.

`ops/e2e/accounting_configuration_test.js` (suite #238) drives the same console
for Accounting and Configuration: the journal's count equals the subledger's
own total, no identifier is visible above the technical fold, a filter narrows
the screen and the downloaded file to the same number, an account leads with
what it books, a proposal changes nothing until it is activated, approve before
validate and activate before approve are both refused by the service, a broken
account code is refused at the ladder AND at the direct remap, a product
manager gets 403 both ways, and the activated change carries four names.
`ChartGuardTest` covers every input combination of the validator without a
fleet. The accessibility scan covers all three screens
(`A11Y_TARGETS=console,accounting`); like the bills targets they sit in the
nightly tier, because the pull-request slice seeds the catalog and no billing,
and a scan reaching for a posting that cannot exist is a failure that says
nothing.

## Honest limits

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
  same page on their own view, and they now sit under one primary named
  Accounting, which shows no second row because the page's own chips are the
  choice. The tabs survive as the deep-link and palette contract.

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
  from someone whose token would have answered — the safe direction, but a
  read-only controller cannot be given the books without `billing:admin` today.
- **Bills is not walled by a 403.** A bill list is `billing:read`, so the
  negative pair that proves the other destinations are really shut cannot be
  drawn there. The suite asserts the 403 where there is one to assert —
  Payments, Configuration and Risk — and says so rather than claiming a wall
  that is not built.
- **The page heading is the page's, not the destination's.** Under Collections
  the row says Cases and the heading still says Collections. `short` renames a
  page in the row only, deliberately, so the crumb and the title stay stable —
  but it does mean one screen is called two things.
- **An account's name is the tenant's, its posting key is not.** The thirty
  posting keys the subledger books against are fixed in the service; a tenant
  can rename and re-code them but cannot invent a thirty-first.
