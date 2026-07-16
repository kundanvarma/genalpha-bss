# Bill distribution & remittance — how to use it

The bill's whole journey, both directions: it renders as a **PDF** anyone can
open, leaves for the operator's **distribution partner** in the format a
country demands, the **buyer's answer** comes back onto a delivery ledger, and
the **money comes home** by bank file and settles the bill by reference.
Every step is per-tenant config or a data row — never code.

## E-invoice formats supported

A format is a **profile row** (`bill_format_profile`), editable live in the
console (Bill formats tab) or at `/billFormatProfile`. The row says which
syntax the renderer speaks, what CustomizationID/ProfileID the document
declares, and whether a payment reference is required. Adding a country is an
INSERT, not a deploy.

| Code | Standard | Syntax | Region / use | Notes |
|---|---|---|---|---|
| `ehf` | EHF Billing 3.0 — Norway's CIUS of Peppol BIS Billing 3.0 | UBL 2.1 | Norway | Carries the **KID** payment reference (`cbc:PaymentID`) the NO-R rules demand — the same digits remittance matches on |
| `peppol` | Peppol BIS Billing 3.0 | UBL 2.1 | The Peppol network (most of Europe, SG, JP…) | EN 16931 compliant; same CustomizationID as EHF, without the Norwegian national rules |
| `cii` | EN 16931 | UN/CEFACT CII | DACH / France | The EN's second syntax |
| `aunz` | A-NZ Peppol BIS 3.0 | UBL 2.1 | Australia / New Zealand | Seeded row — the same UBL skeleton wearing the A-NZ customization; the proof that a country is a row |
| `edifact` | UN/EDIFACT INVOIC (D96A shape) | EDIFACT segments | Legacy B2B trading partners | UNB/UNH envelope, `BGM+380`, `MOA+77` total, `?` release character honored; `application/EDIFACT` |
| `facturx` | Factur-X / ZUGFeRD | PDF + embedded CII | France / Germany hybrid | The human-readable PDF with the EN 16931 CII embedded uncompressed as `factur-x.xml` — one file, two audiences. (Full conformance adds PDF/A-3 + XMP; the machine-readable soul is carried) |
| *(yours)* | e.g. XRechnung 3.0 | any of the above | — | Added live in the console: code, syntax, CustomizationID — proven in suite #46 by creating Germany's XRechnung through the UI |

The tenant's `BILL_DISTRIBUTION_FORMAT` names the row by code; the renderer
follows the row. Built-in EHF/CII shapes remain the fail-open fallback so a
missing row can never block a billing run.

## Channels & the customer's choice

- Tenant default: `BILL_DISTRIBUTION_CHANNEL` = `einvoice` | `print`.
  Print ships the rendered PDF to the print house; e-invoice ships the
  profile row's format to the access point.
- **Per-customer preference** overrides the default: `paper` | `einvoice` |
  `digital` (digital = in-app + email only, the partner is skipped). Set from
  the shop's My bills page, by a CSR in Customer 360, or
  `POST /individual/{id}/billDelivery`. Every change notifies the customer.
- The customer picks the *channel*; the partner, token and syntax stay
  tenant config.

## The document itself

- `GET /customerBill/{id}/document.pdf` — owner-scoped exactly like the bill;
  the TMF678 `billDocument` ref points at it. Buttons in the shop, the
  mobile app and CSR Customer 360.
- `POST /customerBill/{id}/resend` — emails the PDF to the **address on
  file** (never one dictated over the phone). In-app inbox copy always;
  a real email with the PDF attached where the tenant has an ESP.

## Delivery: outbox-backed, with a ledger

Cutting a bill writes a `bill_distribution` row **in the same transaction**
(the outbox property). A relay drains pending rows with exponential backoff;
FAILED after the last try. The buyer's **Peppol Invoice Response** (UBL
ApplicationResponse, UNECE 4343 codes) arrives on
`/distribution/v1/response` and lands on the same row — so one row tells the
document's whole story: *pending → sent (attempt 3) → accepted → paid*, or
*rejected* with the buyer's words. The buyer's "paid" is a claim; money is
only real when remittance says so.

Console: **Deliveries** tab (status, attempts, buyer's answer, one-click
Retry for failed rows). API: `GET /billDistribution`,
`POST /billDistribution/{id}/retry` (billing:admin).

## Remittance: the money comes home

`POST /bank/v1/remittance` (per-tenant bank secret — the token IS the
tenant) detects the file's dialect from its content:

| Format | Detected by | Shape | Currency |
|---|---|---|---|
| ISO 20022 **camt.054** | starts `<` | credit-notification XML, structured creditor reference (KID / RF) | from `Amt/@Ccy` |
| Nets **OCR giro** | starts `NY` | 80-char fixed-width, amounts in øre, KID right-adjusted at 51–75 | NOK (domestic giro) |
| **BAI2 lockbox** | starts `01,` | comma-separated, minor units, customer reference on the 16 record | from the 02 group header |

A clean match (one open bill, exact amount, by the bill's payment reference)
books a real TMF676 bank payment and settles the bill through the **same
guarantee path a card uses**; the customer is thanked in the inbox and on the
timeline. A re-sent file books nothing twice (deterministic correlator).
Everything unclear — unknown reference, wrong amount, closed bill — parks on
the **Unapplied cash** worklist (console tab / `GET /remittance/unapplied`)
with its reason. Money is fail-closed: never guessed, never dropped.

## Configuration reference (per tenant; `_NOVA` variants for the second)

```
BILL_DISTRIBUTION_PROVIDER  none | partner
BILL_DISTRIBUTION_URL       the partner's ingestion endpoint
BILL_DISTRIBUTION_TOKEN     shared secret (also authenticates the partner's
                            Invoice Response callback)
BILL_DISTRIBUTION_FORMAT    profile row code: ehf | peppol | cii | aunz |
                            edifact | facturx | (any row you add)
BILL_DISTRIBUTION_CHANNEL   einvoice | print
BANK_TOKEN                  the bank's shared secret for /bank/v1/remittance
```

Dev clocks: `BSS_BILLING_DISTRIBUTION_TICK_MS`,
`BSS_BILLING_DISTRIBUTION_RETRY_SECONDS`.

## Proven by

- **Suite #46** `bill_distribution_test.js` (27 checks): PDF + owner scoping,
  print & EHF channels, CSR view/resend, per-customer preference beating the
  tenant default, digital-only skipping the partner, profiles as load-bearing
  rows (a live row edit changes the next invoice on the wire; XRechnung added
  through the console UI), EDIFACT and Factur-X by row flips, outbox retries
  through a partner outage, buyer responses (accepted/paid/rejected) on the
  ledger, and the Deliveries + Unapplied cash console pages doing their jobs.
- **Suite #47** `remittance_test.js` (9 checks): camt.054 settle + thank-you,
  fail-closed underpayment, unapplied cash with reasons, idempotent re-sent
  files, Nets OCR settling a NOK bill, BAI2 completing the underpaid one.

Research and design history: [bill-distribution-plan.md](bill-distribution-plan.md).
