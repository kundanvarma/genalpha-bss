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

## Proof

`ops/e2e/bills_desk_test.js` (suite #235) drives the real console with a real
token: the table's columns and the absence of a View button, the search
placeholder, a zero count reading as clean, a chip filtering to exactly its own
count, all six workspace sections answering, no identifier and no ISO date
above the technical fold, and the back link returning to the desk it left.

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
