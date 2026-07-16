# Bill documents & distribution — research and plan

Status: planned 2026-07-16 · targets: PDF on every bill, per-tenant distribution seam

## 1. What the field actually does (research)

**Norway — EHF.** EHF Billing 3.0 is Norway's national customisation (CIUS) of
**Peppol BIS Billing 3.0**, which itself implements the European semantic
standard **EN 16931** in **UBL 2.1** XML. Implementing EHF *is* implementing
Peppol BIS 3.0 — the differences are Norwegian schematron rules (the `NO-R-*`
family: KID payment references, organisasjonsnummer). Delivery rides the
Peppol eDelivery network through Access Points. B2G has been mandatory for
years; Norway has proposed **mandatory B2B e-invoicing from 2027**.

**Europe — not EDI.** The premise needs correcting: Europe converged on
**EN 16931** (Directive 2014/55/EU), which defines the *semantics* and blesses
exactly two syntaxes: **UBL 2.1** and **UN/CEFACT CII**. UBL dominates via
Peppol (Nordics, Benelux, XRechnung's UBL binding); CII dominates DACH/France,
including **Factur-X/ZUGFeRD** — a hybrid that embeds CII XML inside a
PDF/A-3, so one file is both human- and machine-readable. Classic **EDIFACT
INVOIC** is the legacy B2B channel: still alive in retail/automotive supply
chains, but explicitly *not* an EN 16931-compliant syntax. A telco billing
its consumers and SMEs should speak Peppol/EHF first; EDIFACT only where a
specific trading partner demands it.

**Physical mail.** Operators do not print: a distribution partner (in Norway
typically Posten/Bring-adjacent print houses) takes the rendered document and
handles print, envelope and postage. The seam is the same — a partner
endpoint; only the payload differs (a PDF instead of XML).

## 2. Design

1. **PDF on every bill** — `GET /customerBill/{id}/document.pdf`, rendered
   server-side in the billing component with OpenPDF (pure Java, no native
   deps): brand header, bill number, period, customer, the applied-rate line
   items (which already carry their dated proration windows), total. Owner-
   scoped exactly like the bill itself. The bill's TMF678 `billDocument`
   carries the attachment ref; the shop's bill row gets a **PDF** button.

2. **Distribution seam, per tenant** — TenantRegistry (billing) grows:
   `bill-distribution-provider` (none|partner), `-url`, `-token`,
   `-format` (**ehf** | **peppol** | **cii**), `-channel` (einvoice|print).
   After the billing run creates a bill, it pushes to the partner —
   fire-and-forget and fail-open: distribution never blocks billing.
   - `ehf`/`peppol`: UBL 2.1 Invoice XML — real element names, EN 16931 core
     (ID, IssueDate, DueDate, Supplier/Customer parties, InvoiceLines,
     TaxTotal, LegalMonetaryTotal), with the EHF/Peppol
     `CustomizationID`/`ProfileID`; EHF adds the Norwegian payment reference.
   - `cii`: the UN/CEFACT CrossIndustryInvoice skeleton — the other EN 16931
     syntax, for the DACH/France half of Europe.
   - `print`: the partner receives the rendered PDF (base64) plus the postal
     address — print house semantics.
   - EDIFACT INVOIC stays a *named follow-up* for legacy trading partners —
     supported by the same seam when a partner demands it, but not the
     European default the question assumed.
3. **mock-distribution** container (SendGrid-mock pattern): `POST /invoices`
   with the tenant's token; validates an EHF payload actually carries the
   EHF customization; `GET /invoices` for the suites. Dev config: **nova →
   ehf + einvoice** (Norway), **genalpha → cii + print** — both formats and
   both channels proven from one deployment, config apart.

## 3. Proof (suite #46)

PDF: magic bytes + owner-scoping (stranger = 404) + the shop button.
Distribution: nova's bill lands at the partner as EHF UBL naming the bill and
total; genalpha's lands as a print job carrying the PDF; the difference is
config, not code.

## 4. Shipped after the plan (same seam, new doors)

- **The invoice copy** — CSR Customer 360 opens the bill PDF and emails it;
  customers self-serve the same from the shop and the app. The copy goes to
  the **address on file only** (never one dictated over the phone). Delivery
  rides the existing per-tenant ESP seam: an in-app inbox message always,
  plus a real email with the PDF attached where the tenant has an ESP.
- **Per-customer delivery preference** — `paper | einvoice | digital` on the
  individual (null = tenant default), set from the shop or by the CSR, read
  fail-open by the billing run, honored by the distribution seam: the
  customer picks the *channel*; the partner, token and XML syntax stay
  tenant config. Every change notifies the customer.
- **Format profiles as config rows** — what a country's profile IS
  (syntax, CustomizationID, ProfileID, payment-reference rule) lives in
  `bill_format_profile` rows, tenant-scoped under RLS, seeded with EHF,
  Peppol BIS, CII and A-NZ, readable/editable at
  `/billFormatProfile` (edits are `billing:admin`). The renderer reads
  the row; built-in shapes remain the fail-open fallback. Adding a
  country is an INSERT, not a deploy — proven live: an admin edit to the
  EHF row changed the next e-invoice on the wire.
- **Console page over the rows** — Bill formats in the operator console
  (next to Disputes and Dunning): the seeded profiles render as a table,
  and a new country enters as a form — proven live by adding XRechnung
  (Germany) through the UI and reading it back from the API.
- **Outbox-backed delivery** — cutting a bill writes a `bill_distribution`
  ledger row in the same transaction (the outbox property); a relay
  drains pending rows with exponential backoff and marks FAILED after
  the last try. `GET /billDistribution` is the back-office ledger
  (billing:admin), `POST /billDistribution/{id}/retry` the second
  chance. Proven with a chaos switch on the mock: two 503s, recovery on
  attempt three, exactly one delivered copy.
- Still named follow-ups: EDIFACT INVOIC for legacy trading partners;
  Factur-X (CII embedded in PDF/A-3) as a hybrid format; the RETURN
  path — remittance ingestion (OCR/KID files, ISO 20022 camt.054, Peppol
  Invoice Response) auto-settling bills by payment reference.
