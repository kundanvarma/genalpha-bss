# Capability map — TM Forum business capabilities → this BSS, and the honest gaps

*2026-07-24. The question a CSP architect asks first: which business
capabilities does this cover, through which TM Forum tech capabilities,
in which component — and what do I still need from elsewhere? Mapped
against the TM Forum framing (eTOM business process domains, ODA
functional blocks, TMF Open APIs). Legend: ✅ implemented **and proven
by a numbered suite** · ◐ partial / shaped seam (honest note attached)
· ❌ not here — plan another system.*

## 1. Market, Sales & Channel

| Business capability | TMF tech capability | Component / app | Status |
|---|---|---|---|
| Digital storefront (B2C self-service) | Open API consumer over the fleet | `apps/storefront` /shop | ✅ (suites throughout) |
| Mobile app (iOS/Android/web) | same | `apps/mobile` (Expo) | ✅ |
| B2B self-care (org admin + member) | TMF632/672 org model | `apps/business-console` /biz | ✅ |
| Assisted sales / CSR desk | TMF629 customer 360 pattern | `apps/csr-console` /csr | ✅ |
| Retail dealer & telesales channel | dealer API + TMF683 logging | SOM dealer module + `apps/dealer-console` | ✅ #48/#51 |
| Campaign management / martech | TMF Campaign-shaped | `campaign` (journeys, A/B, holdout lift, caps) | ✅ #35–39 |
| Lead → opportunity funnel | TMF699 Sales Management | `quote` service | ✅ |
| Loyalty / rewards program | TMF Loyalty | — | ❌ points/tiers/earn-burn need a loyalty engine |
| Agentic commerce (AI agents buy) | ACP + MCP over TMF620/663/622 | catalog + cart + gateway | ✅ #64 |
| AI answer-engine discoverability | GEO dual-serve + JSON-LD | catalog `/seo` + gateway | ✅ #68 |

## 2. Product & Offer

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Product catalog, bundles, variants | TMF620 (+CTK) | `product-catalog` | ✅ CTK zero |
| Configured / conditioned pricing | TMF620 `prodSpecCharValueUse` | catalog + policy + billing | ✅ #24 |
| Dynamic pricing & order rules as data | TMF policy pattern | `policy` (JSON-logic) | ✅ |
| Promotions / discounts | TMF671 | `promotion` | ✅ |
| Product content / imagery | TMF667 + PIM seam | `document` + per-tenant PIM | ✅ #23 |
| AI product authoring (copilot/advisor) | governed LLM over TMF620 | `intelligence` | ✅ #25/#53 |
| Full enterprise PIM (workflow, DAM, syndication) | — | — | ❌ the catalog is the commercial master, deliberately not a PIM |

## 3. Customer

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Party / customer / account | TMF632/666/669 (+CTK) | `party-account` | ✅ |
| Identity & access (per-tenant IdP) | TMF672 over any OIDC | `user-roles` | ✅ |
| Consent & privacy (GDPR export/erase) | privacy front door | party-account fan-out | ✅ #58 |
| Trouble ticketing | TMF621 | `trouble-ticket` | ✅ |
| Omnichannel interaction record | TMF683 | `party-interaction` | ◐ CTK long tail open |
| Notifications / messaging | TMF681 + ESP seam | `communication` | ✅ (email/inbox; ◐ no SMS/push gateway — bring an SMSC/push provider) |
| Knowledge base + semantic search | TMF knowledge + pgvector | `knowledge` | ✅ |
| Churn prediction & NBO | governed AI | `intelligence` | ✅ (rules + trained LR; ◐ GBM/QoE features future) |
| AI digital workforce (care/back-office) | MCP + workforce API | `intelligence` + package | ✅ #65–66 |
| Social/community management, NPS/CX survey suite | — | — | ❌ |

## 4. Revenue

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Usage rating & consumption | TMF635/677 (+CTKs) | `usage` | ✅ |
| Billing runs, invoices, e-invoice formats | TMF678 (+CTK) | `billing` (crash-resumable) | ✅ #57 |
| Bill distribution + remittance (camt.054/OCR/BAI2) | distribution seam | `billing` | ✅ |
| Payments & tokenized vault | TMF676/670 | `payment` + `payment-method` | ✅ (◐ PSP: mock default, Stripe adapter shaped — certify per deployment) |
| Dunning / in-house collections | billing clocks | `billing` | ✅ (❌ external collections-agency handoff) |
| Prepay balance / OCS | TMF654 facade + OCS seam | `usage` + mock-ocs | ◐ REAL online charging (Gy/Ro, 5G CHF) needs a production OCS behind the seam |
| Taxation engine | — | — | ❌ prices are tax-inclusive by convention; jurisdictional tax (VAT/US telecom tax) needs Vertex/Avalara-class |
| General ledger / ERP finance, rev-rec (ASC 606) | — | — | ❌ export to ERP |
| Wholesale / interconnect / roaming settlement | — | — | ❌ carrier-grade settlement is its own product |
| Revenue assurance & fraud management | — | — | ❌ (the audit trails feed one nicely) |

## 5. Service & Resource (the thin OSS)

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Service orchestration & activation | TMF641/640/638 | `service-orchestration` | ✅ thin: mock activation, real state machine |
| Resource pools (MSISDN), SIM | TMF685 + SIM/OTA seams | SOM | ✅ (◐ real HLR/HSS/OTA behind seams) |
| Number porting (MNP) | clearinghouse seam | `porting` | ✅ (◐ NRDB shaped, not connected) |
| Assurance, alarms → problems, self-heal | TMF642/656 | `assurance` | ✅ (thin; real FM/PM needs the NMS) |
| Qualification / serviceability | TMF679 | `qualification` | ✅ |
| Appointments (install slots) | TMF646 | `appointment` | ✅ (❌ full field-service mgmt: routing, van stock, workforce scheduling) |
| Network inventory (physical/logical), mediation (CDR pipelines) | — | — | ❌ real OSS estate — or wrap it (suite #67 pattern) |

## 6. Partner & Enterprise

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Partner/dealer agreements & commissions | dealer API | SOM | ✅ |
| Agreements & commitments | TMF651 | `agreement` | ✅ |
| B2B quotes (born from intent) | TMF648 | `quote` | ✅ (◐ full CPQ: approvals, versioned proposals, e-sign — no) |
| Multi-tenant operator platform / MVNE | tenant registry + realm mint | fleet-wide | ✅ #49 |
| VAS partner entitlements | entitlement seam | SOM | ✅ (◐ settlement/rev-share no) |
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
multi-tenant operation — proven by 68 suites and 11 CTKs.

**Bring (or keep) from elsewhere**: a production OCS for real-time
charging, a taxation engine, ERP/GL and rev-rec, wholesale/roaming
settlement, fraud/revenue assurance, loyalty, full field-service
management, SMS/push gateways, real OSS inventory + mediation, and the
enterprise estate (HR, procurement, BI warehouse). Every one of these
sits behind an existing seam or the overlay pattern (suite #67): the
list is an integration plan, not a wall.
