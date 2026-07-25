# Capability map — TM Forum business capabilities → this BSS, and the honest gaps

*2026-07-24. The question a CSP architect asks first: which business
capabilities does this cover, through which TM Forum tech capabilities,
in which component — and what do I still need from elsewhere? Mapped
against the TM Forum framing with the LEVELS kept honest: a **business
capability** is an ability the business needs, phrased verb-first and
implementation-free; a **tech capability** is the IT function enabling
it (an ODA functional block / TMF Open API); a **component** is the
software realizing it. Section headings are eTOM-style DOMAINS, not
capabilities. Legend: ✅ implemented **and proven
by a numbered suite** · ◐ partial / shaped seam (honest note attached)
· ❌ not here — plan another system.*

## 1. Market, Sales & Channel

| Business capability | TMF tech capability | Component / app | Status |
|---|---|---|---|
| Sell & serve consumers digitally (self-service) | Open API consumer over the fleet | `apps/storefront` /shop | ✅ (suites throughout) |
| Serve customers on mobile devices | same | `apps/mobile` (Expo) | ✅ |
| Let business customers self-manage | TMF632/672 org model | `apps/business-console` /biz | ✅ |
| Sell & support through assisted channels | TMF629 customer 360 pattern | `apps/csr-console` /csr | ✅ |
| Sell through indirect channels (retail, telesales) | dealer API + TMF683 logging | SOM dealer module + `apps/dealer-console` | ✅ #48/#51 |
| Plan, run & MEASURE marketing campaigns | TMF Campaign-shaped | `campaign` (journeys, A/B, holdout lift, caps) | ✅ #35–39 |
| Capture & develop sales leads | TMF699 Sales Management | `quote` service | ✅ |
| Reward & retain customers (loyalty) | TMF658 Loyalty Management | `loyalty` (#34) | ✅ #69 (earn on settled bills; burn to DATA at the meter, single-use TMF671 vouchers, TIERS as pricing rules; expiry sweep + liability; tier changes trigger campaigns; storefront + app cards) |
| Sell through AI shopping agents | ACP + MCP over TMF620/663/622 | catalog + cart + gateway | ✅ #64 |
| Be recommendable by AI answer engines | GEO dual-serve + JSON-LD | catalog `/seo` + gateway | ✅ #68 |

## 2. Product & Offer

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Define & manage sellable offers (bundles, variants) | TMF620 (+CTK) | `product-catalog` | ✅ CTK zero |
| Price offers by configuration | TMF620 `prodSpecCharValueUse` | catalog + policy + billing | ✅ #24 |
| Govern ordering & pricing with business rules | TMF policy pattern | `policy` (JSON-logic) | ✅ |
| Run promotions & discounts | TMF671 | `promotion` | ✅ |
| Manage product content & imagery | TMF667 + PIM seam | `document` + per-tenant PIM | ✅ #23 |
| Author products conversationally (AI-assisted) | governed LLM over TMF620 | `intelligence` | ✅ #25/#53 |
| Full enterprise PIM (workflow, DAM, syndication) | — | — | ❌ the catalog is the commercial master, deliberately not a PIM |

## 3. Customer

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Know the customer (party, account, roles) | TMF632/666/669 (+CTK) | `party-account` | ✅ |
| Authenticate & authorize per tenant | TMF672 over any OIDC | `user-roles` | ✅ |
| Honor privacy rights (consent, export, erasure) | privacy front door | party-account fan-out | ✅ #58 |
| Resolve customer problems | TMF621 | `trouble-ticket` | ✅ |
| Remember every customer interaction | TMF683 | `party-interaction` | ✅ CTK zero (846/846) |
| Notify & message customers | TMF681 + ESP seam | `communication` | ✅ (email/inbox; ◐ no SMS/push gateway — bring an SMSC/push provider) |
| Help customers help themselves | TMF knowledge + pgvector | `knowledge` | ✅ |
| Predict churn & recommend the next best offer | governed AI | `intelligence` | ✅ (rules + trained LR; ◐ GBM/QoE features future) |
| Staff care & back-office with AI workers | MCP + workforce API | `intelligence` + package | ✅ #65–66 |
| Social/community management, NPS/CX survey suite | — | — | ❌ |

## 4. Revenue

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Rate usage | TMF635/677 (+CTKs) | `usage` | ✅ |
| Bill customers (runs, invoices, e-invoice formats) | TMF678 (+CTK) | `billing` (crash-resumable) | ✅ #57 |
| Deliver bills & apply incoming payments | distribution seam | `billing` | ✅ |
| Collect & safeguard payments | TMF676/670 | `payment` + `payment-method` | ✅ (◐ PSP: mock default, Stripe adapter shaped — certify per deployment) |
| Collect overdue debt (in-house) | billing clocks | `billing` | ✅ (❌ external collections-agency handoff) |
| Charge in real time (prepay balance) | TMF654 facade + OCS seam | `usage` + mock-ocs | ◐ the TMF654 API face is CTK-certified (282/282); REAL online charging (Gy/Ro, 5G CHF) still needs a production OCS behind the seam |
| Calculate jurisdictional taxes | — | — | ❌ prices are tax-inclusive by convention; jurisdictional tax (VAT/US telecom tax) needs Vertex/Avalara-class |
| Account revenue (GL, rev-rec ASC 606) | subledger journal export (house `/revenue/v1`) | `revenue` (#35) | ✅ #70 the FEED (balanced double-entry journal from billing/payment events, CoA mapping as data, CSV export, cent-exact reconciliation tie-out); ❌ the LEDGER itself + the rev-rec engine stay in the ERP — by design |
| Settle wholesale / interconnect / roaming | — | — | ❌ carrier-grade settlement is its own product |
| Assure revenue & fight fraud | — | — | ❌ (the audit trails feed one nicely) |

## 5. Service & Resource (the thin OSS)

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Fulfil & activate services | TMF641/640/638 | `service-orchestration` | ✅ thin: mock activation, real state machine |
| Manage numbers & SIMs | TMF685 + SIM/OTA seams | SOM | ✅ (◐ real HLR/HSS/OTA behind seams) |
| Port numbers in & out (MNP) | clearinghouse seam | `porting` | ✅ (◐ NRDB shaped, not connected) |
| Detect & resolve service problems | TMF642/656 | `assurance` | ✅ (thin; real FM/PM needs the NMS) |
| Qualify serviceability | TMF679 | `qualification` | ✅ |
| Schedule installations | TMF646 | `appointment` | ✅ (❌ full field-service mgmt: routing, van stock, workforce scheduling) |
| Network inventory (physical/logical), mediation (CDR pipelines) | — | — | ❌ real OSS estate — or wrap it (suite #67 pattern) |

## 6. Partner & Enterprise

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Manage channel partners & their compensation | dealer API | SOM | ✅ |
| Manage customer agreements & commitments | TMF651 | `agreement` | ✅ |
| Quote complex B2B deals | TMF648 | `quote` | ✅ (◐ full CPQ: approvals, versioned proposals, e-sign — no) |
| Operate multiple brands / tenants (MVNE) | tenant registry + realm mint | fleet-wide | ✅ #49 |
| Bundle partner services (VAS) | entitlement seam | SOM | ✅ (◐ settlement/rev-share no) |
| HR / workforce mgmt (human), ERP, procurement | — | — | ❌ enterprise systems |
| BI / data warehouse | — | `insight` events help | ◐ insight is a CDP-ish spine, not a DWH |

## 7. Cross-cutting (the differentiators)

| Capability | Where | Status |
|---|---|---|
| AI control plane (meter/budget/kill-switch/audit) | `intelligence` | ✅ #59 |
| Agentic surfaces: GEO / ACP / MCP / workforce / closed loop | gateway + catalog + cart + intelligence + packages | ✅ #64–66, #68 |
| Legacy overlay (wrap an existing BSS) | 3 per-tenant seams | ✅ #67 |
| Event backbone + observability (Live Flow) | Kafka outbox + `flow` | ✅ |
| Hardening: tick locks, resumable billing, backups, GDPR, PQC-ready | fleet | ✅ #56–58 |

## The honest summary for a buying CSP

**Complete here**: digital BSS core (catalog→order→activate→bill→cash),
six channels, martech, personalization, the full agentic layer, and
multi-tenant operation — proven by 70 suites and 13 CTKs.

**Bring (or keep) from elsewhere**: a production OCS for real-time
charging, a taxation engine, ERP/GL and rev-rec (now FED by the #70 journal-export seam), wholesale/roaming
settlement, fraud/revenue assurance, full field-service
management, SMS/push gateways, real OSS inventory + mediation, and the
enterprise estate (HR, procurement, BI warehouse). Every one of these
sits behind an existing seam or the overlay pattern (suite #67): the
list is an integration plan, not a wall.
