# Migrating an installed base into GenAlpha — research

*2026-09-14. The question: an operator already runs a BSS (BSCS, Kenan,
BRM, CBS, Singleview, Salesforce Industries, an ERP, an MVNE). How do they
move their customers, subscriptions, numbers, balances and bills onto
GenAlpha? And the constraint that shapes the answer: the operator will not
give the vendor, or the vendor's AI, access to their production data, and
in Norway the law forbids it without a contract anyway. So the migration
has to be something the operator runs inside their own walls, without us.*

This document is research and design conclusions. It is not the build
plan and there is no loader yet; those come after this is agreed.

---

## 1. The answer in one paragraph

Every serious self-service migration tool (Salesforce Data Loader, SAP
Migration Cockpit, Dynamics Data Management, Chargebee, Zuora, Stripe,
Odoo) has converged on the same shape: **a template per object, a staging
area, a validate-or-simulate step, a row-level rejection file with a reason
code, an upsert keyed on the customer's own legacy id, and a re-run of the
rejection file until it is empty.** Billing tools add two things CRMs do
not: **payment cards never travel in the file** (vault-to-vault, tokens
only) and **the current billing term travels with the subscription** so
nothing is rebilled. Telecom adds a third: **the bill history is a legal
artefact and is migrated as documents plus summary rows, never re-rated.**
GenAlpha should ship exactly this as a kit the operator runs in their own
tenancy. The vendor's AI sees the **schema profile** (column names, types,
value patterns, code lists) and **aggregate reports** (counts, sums,
reason-code histograms), never a row. That keeps us outside the data, out
of the GDPR processor role for the migration, and outside the Norwegian
telecom secrecy perimeter, and it means the operator can run it whether or
not we are on the phone.

---

## 2. What the industry does

### The lifecycle every vendor and integrator describes

Assess and profile the source → choose a strategy (big bang, waves,
parallel run) → map the data → load into staging → **mock migrations with
reconciliation between each** → freeze → cut over on a rehearsed
minute-by-minute runbook → hypercare with monitoring and a rollback path.
CSG's public guidance is the clearest: "data preparation determines
migration success more than any other factor"; standardise identifiers,
normalise addresses, dedupe, fill required fields, validate relationships,
then check record counts and key business metrics across both systems and
run a parallel bill. Nobody publishes a fixed number of mock rounds; the
T-Mobile UK/Germany migration (14M+ records) ran "multiple dry runs" for
the first market and far fewer for the second because the tooling had
matured.

### Cases with numbers, and what actually went wrong

| Case | Scale | Shape | Lesson |
|---|---|---|---|
| Vodafone UK 2013–15 | 28.5M accounts, seven legacy billing platforms onto one CRM | big bang after a long programme | "A small proportion" of accounts migrated with wrong billing state; 10,452 pay-as-you-go customers lost top-ups over 17 months; **£4.6M Ofcom fine**, ~£59M total cost. The killer was billing-state edge cases plus slow detection and weak complaint handling, not plan arithmetic. |
| T-Mobile US / Sprint 2021–23 | ~8M+ Sprint accounts | waves: first wave under 1,000 customers, then ~500k, 4M, 8M over 20 months | Sprint rate plans were **re-created in the target catalog** so nobody was forced to change plan; waves sized to care capacity. |
| Airtel / Ericsson 2022 | 70M subscribers, three markets | big bang, six months, industrialised reusable tooling | Big bang is chosen for cost when the tooling is mature and rehearsed. |
| Telenor Pakistan / Huawei 2026 | 90M prepaid + postpaid | one event onto a cloud charging system | Same: industrialised, rehearsed. |
| BT → EE 2024–25 | ~9–10M consumers | waves over a year | Partial migrations, duplicate accounts, lost bill access for months: **identity and bill-archive continuity** are where consumer migrations hurt. |
| TalkTalk / Tiscali 2010–11 | ~62k closed accounts billed | consolidation | Billing closed accounts after cutover; £3M fine, £2.5M refunds. **Status mapping** (closed vs active vs dormant) is the classic defect. |
| Telstra T22 | 1,800 plans → 20 | catalog collapse before migration | Grandfathered plans are the hardest to move; a source→target offering matrix is the first artefact. |
| TSB (bank) 2018 | 5.2M customers | nine dress rehearsals, then platform failure | Rehearsal count is not readiness; **the platform under load** and the runbook matter as much as the data. |

The pattern across the failures: status edge cases (dormant, closed, in
binding, mid-cycle), identity and login continuity, bill archive access,
and slow detection after go-live. Regulators fined the detection and
complaint-handling gaps as hard as the migration defects.

### Wave design, as practitioners do it

Start with the simplest single-play customers; mix simple and complex
products in each later wave; spread frequent callers across waves; defer
customers with a recent complaint; size each wave to care capacity;
disable provisioning triggers during direct inserts (a migrated line must
not be re-provisioned). Align the cutover to a bill-cycle boundary, freeze
change orders three to seven days before, keep usage rating live (usage is
replayed from mediation), keep the legacy system read-only for at least two
cycles so disputes can be answered against the original bill.

### TM Forum

There is one TM Forum document on the subject, GB1004F Migration Assurance
Guidebook (member-gated), and no numbered guide for migrating onto ODA;
the Engage forum's answer is the strangler pattern. **There is no TM Forum
bulk-load API.** The Open APIs are integration surfaces; where the source
is modern (Netcracker, Comarch, Cerillion, Tecnotree) they double as
extract routes. The SID-mapping guides per API (TMF632A, 637A, 666A,
676A, 678A) are the closest thing to a canonical migration vocabulary, and
Salesforce's Communications data-model spec is the only vendor document
that maps its objects to SID explicitly.

### Reconciliation practice

What gets counted: record counts source vs target at every checkpoint;
sums of balances and open receivables; field-level matches; then business
metrics (active lines, ARPU, invoice totals per cycle). The parallel bill
run classifies every variance as **accept** (rounding, formatting),
**review** (timing, proration) or **block** (rating logic, tax, revenue).
Decide up front what "correct" means: invoice total, rated usage, billed
usage or recognised revenue. Keep a set of **golden accounts** that cover
mid-cycle changes, stacked discounts, partial months, cessations and
backdated orders. Published tolerances are generic, not telecom-specific:
record-count variance under 0.01%, field match over 99.9%, invoice match
rate over 98% (Lago), zero critical defects for 30 consecutive days before
exiting hypercare. Small-operator vendors run two to four parallel billing
cycles; tier-one operators do not publish theirs.

---

## 3. What the sources look like

Most vendor data dictionaries sit behind NDAs. What follows is enough to
know the *shape* of each source and the realistic extract route; the
operator supplies the dictionary.

| Source | Where the data lives | Realistic extract route | Quirks that bite |
|---|---|---|---|
| **Ericsson BSCS iX / CBiO** | Oracle; `CUSTOMER_ALL`, `CONTRACT_ALL`, `RATEPLAN`, `STORAGE_MEDIUM` (SIM), `DIRNUM`/`PORT` (numbers), `ORDERHDR_ALL`/`ORDERTRAILER` (bills), `CASHRECEIPTS_ALL`, `FEES` | direct SQL (schema stable across iX releases) | `_ALL` tables carry soft-deleted history; three ids for one customer (`CUSTOMER_ID`, `CUSTCODE`, `CO_ID`); **prepaid balances live in the charging system (SDP/AIR), not the BSCS database**; proration recomputed at bill time, not stored |
| **Amdocs Kenan / CES / Optima** | Oracle or DB2; `CMF`, `SERVICE`, `EXTERNAL_ID_EQUIP_MAP`, `BILL_INVOICE`, `BILL_ACCOUNT`; Optima splits config and account DBs behind an ESB with 300+ REST APIs | direct SQL or Amdocs "migration factory" services | composite keys (`SERVER_ID`+`SUBSCR_NO`); several "customer" levels (parent, child, billing); price-plan history needed to reproduce proration |
| **Netcracker RM** | PostgreSQL + Cassandra + Kafka | TM Forum Open APIs (Ready-for-ODA) + financial export feeds; DB reads need vendor sign-off | balances and counters on Cassandra are not SQL-queryable |
| **CSG Singleview / Ascendon** | Oracle (Singleview); SaaS REST only (Ascendon) | Singleview SQL and reports; Ascendon REST + reporting exports | customer-node hierarchies with per-node invoicing points; normalised-event history is huge and rarely migrated |
| **Oracle BRM + Siebel** | `ACCOUNT_T`, `BILLINFO_T`, `BAL_GRP_SUB_BALS_T`, `DEVICE_NUM_T`/`DEVICE_SIM_T`, `PURCHASED_PRODUCT_T`, `BILL_T`, `EVENT_T`; Siebel Account/Asset/Agreement/Premises | direct SQL, PCM opcodes, REST; Siebel EIM export batches | Oracle's own Conversion Manager rule: **settle cycle fees, rollovers and discounts in the source before extract**; bill history not migrated, only memo events; POID ids |
| **Huawei CBS / BES** | GaussDB/Oracle; USRDB (customer → account → subscriber), BMPDB (products), BILLDB, EDRDB | BMP SOAP queries, batch files, SQL | balances split into account balance and free units with expiry; numeric codes need reference-data joins; CDR history left behind |
| **Comarch, Cerillion, Tecnotree** | Oracle underneath, API-first fronts | 21–59 certified TM Forum APIs; the APIs are the extract route | migration sold as a service; no public spec |
| **Optiva (now Qvantel), MATRIXX (now Amdocs)** | universal data model / in-memory wallets | REST subman/pricing APIs; no SQL on MATRIXX | per-wallet balances with rollover semantics; group wallets shared across members |
| **Salesforce Industries / Vlocity, CloudSense** | Salesforce objects: Account hierarchy, Asset/AssetLineItem, Bill/BillItem, BillingProfile, Contract, Payment; `vlocity_cmt__`/`csord__` namespaces | Bulk API 2.0 (CSV/JSON, 15 GB per query) | assets carry JSON attribute blobs; balances usually mastered in an external biller |
| **Odoo / ERP billing (small operators)** | `res.partner`, `sale.subscription`, `account.move`, `account.payment` | CSV export with External IDs, JSON-RPC `search_read`, or PostgreSQL | no SIM or number inventory; often a spreadsheet or the MVNE |
| **MVNE platforms (Gigs, Telness Tech, Transatel)** | users → subscriptions → SIMs/numbers/usage balances | per-subscription REST, webhooks; **no bulk export** | leaving an MVNE is porting plus bulk SIM swap, run by the MVNE's migration manager |

Two conclusions. First, there is **no shared source format**; the
canonical model has to be ours, TMF-shaped, and every source gets a mapping
to it. Second, the TMF resources do not cover four things every migration
needs: **an opening receivables position** (open items with ageing, or a
balance forward), **prepaid balance buckets with expiry**, **a legacy-id
crosswalk on every entity**, and **the bill archive** (document references
with header rows). These are a separate "financial opening position" and
"archive" pair of files, not a bending of TMF678.

### The canonical set

TMF632 Individual/Organization, TMF669 PartyRole, TMF666 BillingAccount
(with hierarchy), TMF637 Product (subscription with characteristics,
prices, realizing service), TMF638 Service, TMF639 Resource (SIM, number),
TMF651 Agreement (binding, commitment), TMF670 PaymentMethod (tokens only),
TMF654 balances, plus the two extension files above. Interchange format:
**one UTF-8 CSV per object** (RFC 4180, ISO dates, E.164 numbers), a
**manifest** with per-file row count, SHA-256, sum checks and a hash total
over the legacy ids, and JSON Lines where a payload is genuinely nested
(product characteristics). Fixed-width and XML only when a source tool
demands them. Parquet is a lake format; no migration tool ingests it.

---

## 4. What the law says, and why it decides the design

- **GDPR.** A full-base billing migration will in practice always need a
  DPIA (large scale, traffic and location data). Any vendor that touches
  real data is a processor under Art. 28 and needs a DPA; an AI provider
  behind the vendor is a sub-processor needing prior written
  authorisation. Pseudonymised data is still personal data (EDPB
  Guidelines 01/2025). Datatilsynet's position on test data is that
  "bruk av personopplysninger i test bør unngås"; convenience is not a
  justification, and the privacy-by-design award went to the synthetic
  Test-Norge population.
- **Ekomloven § 3-10 (taushetsplikt, in force 1 Jan 2025).** The secrecy
  duty binds the provider and *anyone performing work or services for the
  provider*, personally, and survives the engagement. It covers traffic,
  location, login records and technical set-up. A migration tool or an
  assistant reading CDRs, balances or usage is inside that perimeter.

So the vendor-blind design is not a nicety. In this market it is the only
posture in which the vendor is not a processor at all for the migration:
no data leaves the operator's tenancy, the DPA needs no migration scope,
and the operator's DPIA records "vendor tooling runs on-premises; vendor
receives schema profile and aggregate reports only". The option ladder,
which the audit trail should record: hosted model on schema only →
private model on masked samples → no model.

---

## 5. How self-service migration tools do it

| Tool | What it does that we should copy |
|---|---|
| Salesforce Data Loader / Bulk API | upsert on an **External ID**; every run writes `success.csv` (with new ids) and `error.csv` (row plus reason); the error file is re-submittable as-is |
| Chargebee | template CSV per module; validation adds a `validation_errors` column per row; **on live sites only the first three rows run until you confirm**; subscriptions import with `current_term_start/end` so nothing is rebilled; **cards migrate vault-to-vault first, then data** |
| Stripe | merchant never touches card numbers; Stripe returns a **post-import mapping file** (old id → new id); subscriptions imported with the original billing anchor and reviewed before billing |
| Zuora | "Silent for Migration" communication profile (no emails during load); external invoices imported as **standalone documents keyed by externalId**, never re-rated |
| SAP Migration Cockpit | staging tables; **value-mapping tasks** ("USA" → "US") confirmed once and reused across objects; Validate → Convert → **Simulate** → Execute |
| Dynamics Data Management | staging per entity; type and duplicate checks then referential checks; **execution levels** enforce dependency order; "skip bad records and continue, fix later" |
| Odoo | Test Import button; the whole import is one transaction |
| Zendesk | organisations before users, enforced |
| Auth0 / Keycloak | password hashes import only when the algorithm is supported; otherwise **reset on first login** or **just-in-time migration** (validate against the legacy store on first login, then persist locally) |

Six conventions fall out: template per object; staging then simulate;
row-level rejects with a closed reason enum; upsert keyed on the legacy
id with an exportable crosswalk; dependency-ordered levels; silence
during load.

---

## 6. What this means for GenAlpha

### What exists today

- **Billing migration rehearsal** (`POST /tmf-api/customerBillManagement/v4/migrationRehearsal`):
  a legacy export of `{externalRef, offeringName, currentMonthly}` rows
  priced by the same engine that cuts real bills; matched within
  tolerance, price-differs by how much, offering-missing; exceptions by
  name; `readyToCutOver` flag; read-only. This is the parallel bill run
  in embryo.
- **Shadow billing**: expected vs billed delta per cycle, a per-wave
  reconciliation oracle.
- **Base migration**: source→target offering matrix, rehearsal gate,
  per-customer state machine with snapshot and rollback, waves with a
  circuit breaker, notice regime per jurisdiction. Built for plan sunsets
  inside GenAlpha; the wave and rollback machinery is reusable.
- **Front doors that already accept a lived-in customer**: party
  (TMF632), billing account (TMF666), product inventory (TMF637 POST),
  resource inventory (TMF639 POST for SIM and number), agreement
  (TMF651), payment method (TMF670), prepaid balance adjust and top-up
  (TMF654-shaped), porting orders. The history seed scripts prove a
  whole customer can be built through these doors.
- **Foreign-event bridge** (`bss-bridge`) for coexistence with a legacy
  system that stays live.
- **Tenant isolation, per-tenant Keycloak realm**, so the whole exercise
  runs in the operator's own tenant.

### What is missing, honestly

1. **No legacy-id crosswalk.** Party, product, service and billing
   account carry no `externalReference`. Without it there is no idempotent
   upsert and no mapping file to hand back.
2. **No opening financial position door.** Billing has no way to accept
   an external invoice as an open item, a balance forward, an ageing
   bucket or a dunning stage. Bills only exist if our engine cut them.
3. **No bill archive door.** The document service stores our artefacts;
   there is no "legacy bill PDF with header row" object.
4. **No bulk staging, validation, rejection or control-total machinery.**
   The rehearsal takes a request body, not a manifest.
5. **No silence mode.** Loading a customer through the front doors today
   fires welcome journeys, events to martech, and provisioning.
6. **Identity migration** has no first-login re-link: no password-hash
   import, no just-in-time validation against a legacy store, no
   national-id (or keyed hash) match for BankID re-attachment.
7. **Services are born from orders.** There is no direct "this line
   exists, active since 2019" door; the loader would have to create the
   product and the service and the resource separately with the lineage
   fields set.

### The design that follows: the migration kit

A container the operator runs inside their tenancy. Nothing calls home.

- **Templates and a schema profiler.** One CSV template per canonical
  object with a data dictionary. The profiler reads the operator's
  extract locally and produces a *schema profile*: column names, types,
  null rates, cardinality, value patterns, and top values only for
  low-cardinality code columns (status codes, plan codes; never names).
  It generates synthetic rows from the patterns. That profile is the
  only thing that leaves the building.
- **Mapping spec, declarative.** Source column → canonical field →
  transform → value map → rejection rule. The assistant proposes it from
  the profile with a confidence per line; the operator's analyst
  confirms it in the console; the kit executes it. Vendor starter
  packs for the sources in §3 ship as mapping specs, so BSCS or Kenan
  extracts need edits, not authoring.
- **Manifest with control totals.** Per file: row count, SHA-256, sums
  (open receivables, monthly recurring, prepaid balances), hash total
  over legacy ids, as-of date, cutover date. The loader refuses on any
  mismatch; that alone catches truncated extracts and re-sent old files.
- **Staging, validate, simulate.** Load into staging tables in the
  tenant's own database; type and referential checks; the billing
  rehearsal runs on the staged base as the simulate step.
- **Levels.** Catalog reference → parties and organisations → billing
  accounts and hierarchy → contacts and identities → payment tokens →
  products and agreements → services and resources (numbers, SIMs) →
  opening financial position → archive index → open orders and cases.
  Each level runs in parallel within itself.
- **Idempotent upsert on the legacy id**, a crosswalk table
  `(sourceSystem, legacyId) → genalphaId` per entity, exported as the
  operator's mapping file for their OSS, CRM and reporting.
- **Rejects with a closed reason enum**, one file per object, original
  row plus `reasonCode` and detail. The histogram of reason codes, with
  one synthetic exemplar per code, is what the assistant sees.
- **Reconciliation console.** Per-domain scorecards (source, loaded,
  rejected, delta, sum checks against tolerance), a zero-difference bill
  test on the last closed cycle with variances bucketed accept / review /
  block, per-customer bill diff, a sample panel (random N plus all
  outliers plus all high-value accounts) with named sign-off, and gates:
  load complete → counts reconciled → bill diff within tolerance →
  sample signed → go-live. Each gate records who approved and the
  evidence hash. That record is the DPIA artefact.
- **Silence mode** on the tenant during load: no journeys, no outbound
  messages, no provisioning triggers, no martech events; lifted per wave.
- **Identity.** Customers: no passwords migrate; first login re-links via
  BankID or eID on a keyed hash of the national id, or a one-time code to
  the migrated number, or a legacy-store check while legacy is still up.
  Staff: not migrated at all; federate the tenant's realm to the
  operator's directory.
- **Cutover pattern.** Opening receivables per account plus open invoices
  as line records; closed history as PDFs with a header index; current
  term dates on every subscription so the next bill lands on the original
  date; freeze at a cycle boundary; legacy read-only for two cycles;
  waves sized to care capacity with the base-migration circuit breaker.

### The operator's runbook, without us

1. Export from the legacy system with their own DBA or vendor (routes in
   §3), into the kit's templates or their raw shape.
2. Run the profiler; send us the profile if they want mapping help, or
   start from a vendor starter pack.
3. Confirm the mapping spec in the console.
4. Load to staging; read the rejects; fix the extract or the mapping;
   repeat until the reject files are empty or explained.
5. Run the billing rehearsal on the staged base; work the exceptions by
   name; agree tolerances.
6. Mock migration into a rehearsal tenant; reconcile; sign the sample.
7. Freeze, load the delta, cut over wave one; hypercare against the
   scorecards; widen.

At no step does anyone outside the operator read a customer row.

---

## 7. Sources

Industry practice: CSG data migration strategy; PhixFlow T-Mobile UK/DE
case; Ravus parallel bill runs; A1QA back-to-back testing; Cygnet parallel
run playbook; Lago billing migration playbook; Tekton telecom billing
migrations; Neon-soft migration guide; Solix cutover reconciliation;
TM Forum GB1004F, IG1166, IG1228, Engage thread on legacy CRM to ODA.
Cases: Ofcom CW/01160 (Vodafone), diginomica and The Register coverage;
tmo.report and T-Mobile Sprint migration centre; Ericsson Airtel blog;
Developing Telecoms (Telenor Pakistan); ISPreview and BT newsroom (EE);
ITPro (TalkTalk); FCA (TSB); Slaughter and May TSB review.
Sources and models: Oracle BRM Conversion Manager docs; Siebel EIM
export; Amdocs CRM data model and Optima on AWS whitepaper; Huawei CBS
V5 solution description; Netcracker RM and ODA directory; CSG Singleview
and Ascendon; Salesforce Communications data model spec and Bulk API
limits; CloudSense support; Totogi migrate; Odoo import docs; Gigs,
Telness Tech, Transatel developer docs; TM Forum SID mapping guides.
Tooling: Salesforce Data Loader, HubSpot, Zendesk importer, ServiceNow
import sets, Shopify/Matrixify, Stripe PAN and subscription import,
Chargebee bulk operations and card migration, Recurly imports, Zuora
import and standalone invoices, Odoo, Dynamics DMF, SAP Migration
Cockpit, Workday EIB; Nacha/Moov ACH control records; ECB T2S data
migration tool spec.
Law: Datatilsynet on test data and DPIA; ICO DPIA and pseudonymisation
guidance; EDPB Guidelines 01/2025; GDPR Art. 28; lovdata ekomloven
2024-12-13-76 kap. 3; Nkom on provider secrecy. Identity: Auth0 bulk
import; Keycloak user-migration providers; Signicat BankID OIDC.
