# Credit notes — the reversing document billing never had — plan

*2026-07-25. The operator assumed credit notes were already built; the
grep said no. What exists is the adjacent machinery — dispute credits
(a negative rate line + a smaller due) and PSP refunds — but no
NUMBERED DOCUMENT reversing a posted invoice. Bookkeeping law cares
about the document: this arc builds it, refactors dispute credits to
ride it, and books it in the revenue subledger.*

## Research findings

- **The law is specific** (Norwegian bokføringsforskriften, aligned
  with EU VAT practice): a credit note carries the SAME information as
  an invoice, gets its OWN number in an unbroken sequence, and must
  REFERENCE the original invoice. Corrections are never edits — the
  wrong invoice stays, the kreditnota reverses it. In Norway credit
  notes travel the same structured channel as invoices (EHF / Peppol
  BIS CreditNote) — which maps onto billing's existing format-profile
  and distribution seams (P2 here, honestly deferred).
- **TMF678 has no credit-note resource** — the house adds `creditNote`
  alongside `customerBill` on the same API, TMF-envelope style.
- **Repo recon**: bill PDFs are programmatic OpenPDF (no template);
  billNo is UUID-suffixed (`BILL-202607-XXXXXXXX`) — there is NO
  sequential counter anywhere in billing, so the gapless series is
  new infrastructure (`document_sequence`, row-locked increment).
  Dispute resolve's credit path (negative `disputeCredit` rate line +
  reduced due for unpaid; `payments.refund` for settled) is exactly
  the seam to refactor. Neither frontend has a dispute-RESOLVE face
  today. Billing migrations pair schema (`migration/`, next V24) with
  RLS (`migration-postgresql/`, V25).

## Design

### The document (billing)

`credit_note` (V24 + V25 RLS): id, tenant, **credit_note_no** —
`CN-<6-digit seq>`, gapless per tenant via a `document_sequence` row
locked in the issuing transaction — bill_id + bill_no SNAPSHOT (the
legal reference), owner_party_id, amount{value,unit}, **reason
(required — a reversing document has a cause)**, settlement
(`reduced` | `refunded`), refund_ref, dispute_id (when born from a
dispute), issued_at. Append-only: no PATCH, no DELETE — corrections
of corrections are new credit notes.

**Issue** = `POST /customerBill/{id}/creditNote {amount?, reason}`
(billing:admin — money-moving stays senior/back-office; agents open
disputes, admins decide them):
- amount defaults to the full remaining due; refuses `> remaining`.
- UNPAID bill → negative `creditNote` rate line + reduced due (the
  dispute-credit mechanics, generalized); due reaching zero settles
  the bill — nothing left to collect.
- SETTLED bill → the money moves BACK through the PSP (existing
  refund path); settlement=`refunded`, refund_ref kept.
- Mints the number, publishes `CreditNoteIssuedEvent` (key
  `creditNote`: id, creditNoteNo, billId, billNo, amount, reason,
  settlement, relatedParty).

**Reads**: `GET /creditNote` (staff all, customers their own —
party-scoped like bills), `GET /creditNote/{id}`,
`GET /creditNote/{id}/document.pdf` — OpenPDF, same house style as
invoices: "Credit note CN-000001 — credits invoice BILL-…", the
reason, the negative amount. Same fields as an invoice, per the law.

**Dispute refactor**: resolve(outcome=credit) now ISSUES a credit
note (reason = the dispute's) and records dispute_id on it — every
dispute credit becomes document-backed. `DisputeResolvedEvent` still
fires (martech listeners unchanged).

### The subledger (revenue)

New posting key `creditNote` (4093, contra-revenue).
`CreditNoteIssuedEvent` with settlement=`reduced` books
contra-revenue debit / AR credit (sourceRef `creditNote:<id>`);
settlement=`refunded` books NOTHING here — the refund event already
books the cash reversal, one path never two. The
`DisputeResolvedEvent` branch is REMOVED (credit notes carry that
flow now); suite #70 leg 10 re-targets accordingly.

### The faces

- **Storefront** (read-only — customers receive credit notes, never
  issue them): each bill row lists its credit notes — chip
  `credit-note-chip` (number + amount) + `credit-note-pdf` link,
  nb-NO strings.
- **CSR console**: "Issue credit note" on the bill row
  (`csr-issue-credit-note`): amount prompt (blank = full), REQUIRED
  reason prompt, `act()` + interaction logged. The privilege is real:
  an agent WITHOUT billing:admin gets a clean 403 — proven in the
  suite as a wall, not hidden.

## The proof (suite #71, creditnote_test.js)

1. Reason-less issue refuses 400; a partial credit note on an unpaid
   bill mints `CN-000001`-style number, reduces the due, adds the
   negative line; the customer reads their OWN credit note; the PDF
   serves with the invoice reference.
2. A second credit note increments the sequence by exactly one —
   gapless proven, not promised.
3. A full credit takes the due to zero and settles the bill.
4. A credit note on a SETTLED bill refunds through the PSP
   (settlement=`refunded`, refundedTotal rises on the payment).
5. Dispute resolve(credit) yields a document-backed credit note
   carrying the dispute id.
6. Revenue books `creditNote:<id>` (4093 contra / AR credit) for
   reduced notes, books NOTHING extra for refunded ones, and no
   `dispute:` entries exist anymore.
7. Storefront: the customer's Bills page shows the chip + PDF link
   (Playwright).
8. Walls: amount > remaining refuses; an agent without billing:admin
   gets 403; nova sees nothing.

## Order of work

1. **P1 (this arc)**: V24/V25 + `document_sequence`, CreditNoteService
   + controller + PDF, dispute refactor, `CreditNoteIssuedEvent`,
   revenue listener + chart key + #70 leg 10 retarget, storefront +
   CSR faces, suite #71, regressions (revenue, storefront, csr),
   capability-map/README counts (71 suites), books.
2. **P2 (open)**: EHF/Peppol BIS CreditNote via the bill-distribution
   seam; credit-note lines (partial per-line credits); dispute-resolve
   worklist UI in the CSR console.

## Shipped

**P1 — 2026-07-25, suite #71 green (eight legs).** The document exists:
`CN-000001` and `CN-000002` minted CONSECUTIVELY on camera (the gapless
series is billing's first sequential counter — `document_sequence`,
row-locked), each referencing its invoice number, reason required (400
without). Unpaid bills came down and settled at zero; a settled bill
refunded 1.00 back through the PSP (refundedAmount rose on the settling
payment); the dispute resolve now mints its own numbered paper; kai
read his own note and its PDF on the storefront (chip + kreditnota
nb-NO); the subledger booked the REDUCED note under the document's
number and refused to double-book the REFUNDED one; and the walls held
— over-credit 400, agent-without-billing:admin 403, nova nothing.

Regressions green: revenue #70 (leg 10 retargeted to the credit-note
posting; the tax-split tie-out STRENGTHENED — booked AR minus the
bill's credit-note postings must equal the live due, with legacy
pre-refactor dispute postings counted, because the journal is
append-only and history keeps its source type), refunds (a
pre-existing CALENDAR FLAKE surfaced and fixed: the suite hardcoded a
3.50 credit against a month-end prorated bill of 3.39 — now adaptive),
storefront, csr. Build note: injecting JSX next to a conditionally
rendered sibling needs the conditional's braces re-balanced — vite
said so immediately.
