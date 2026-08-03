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
| Configure & validate complex offers, any channel | TMF760 Product Configuration v5 | `product-catalog` + policy | ✅ #77/#78 (server-side: both pick bounds, values scoped to the picked option, priced to the pick, policy consulted; agent channel configures → checks → orders with nested items) |
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
| Bill customers (runs, invoices, e-invoice formats, credit notes) | TMF678 (+CTK) | `billing` (crash-resumable) | ✅ #57, #71 (numbered GAPLESS credit notes: unpaid bills reduce, settled refund via PSP, disputes mint their own paper) |
| Deliver bills & apply incoming payments | distribution seam | `billing` | ✅ |
| Collect & safeguard payments | TMF676/670 | `payment` + `payment-method` | ✅ (◐ PSP: mock default, Stripe adapter shaped — certify per deployment) |
| Collect overdue debt (in-house) | billing clocks | `billing` | ✅ (❌ external collections-agency handoff) |
| Charge in real time (prepay balance) | TMF654 facade + OCS seam | `usage` + mock-ocs | ◐ the TMF654 API face is CTK-certified (282/282); REAL online charging (Gy/Ro, 5G CHF) still needs a production OCS behind the seam |
| Calculate jurisdictional taxes | — | — | ❌ prices are tax-inclusive by convention; jurisdictional tax (VAT/US telecom tax) needs Vertex/Avalara-class |
| Account revenue (GL, rev-rec ASC 606) | subledger journal export (house `/revenue/v1`) | `revenue` (#35) | ✅ #70 the FEED (balanced double-entry journal from billing/payment events, CoA mapping as data, tax split, dispute credits, priced loyalty liability, period close, SAP/NetSuite-shaped CSV, cent-exact reconciliation tie-out); ❌ the LEDGER itself + the rev-rec engine stay in the ERP — by design |
| Settle wholesale / interconnect / roaming | — | — | ❌ carrier-grade settlement is its own product |
| Fight acquisition fraud (party/order risk) | TMF696 Risk Management | `intelligence` risk engine | ✅ #82 (transparent additive score from real signals — unpaid bills, credit notes, velocity, tenure, verified session; threshold enforced as a policy rule, reversibly) |
| Assure revenue (usage reconciliation, leakage) | — | — | ❌ specialist tooling (the audit trails feed one nicely) |

## 5. Service & Resource (the thin OSS)

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Fulfil & activate services | TMF641/640/638 | `service-orchestration` | ✅ thin: mock activation, real state machine |
| Orchestrate & EXPLAIN order processes | TMF701 Process Flow Management | `process` (#36) | ✅ #72 (design intent as data, flows projected from the event stream with their cross-system timeline, STUCK is a state on the bus, operator recovery lever; and failures feed an agent whose MEMORY COMPOUNDS: governed L0 diagnosis to a ticket note, episodic traces w/ mandatory verdicts, runbooks promoted on N recurrences + human approval — the fourth identical failure auto-diagnosed from memory with ZERO model calls, revocation proven as the brake) |
| Manage numbers & SIMs | TMF685 + TMF639 facade + SIM/OTA seams | SOM | ✅ (◐ real HLR/HSS/OTA behind seams; #76: pools + issued ledger honestly faced) |
| Port numbers in & out (MNP) | clearinghouse seam | `porting` | ✅ (◐ NRDB shaped, not connected) |
| Detect & resolve service problems | TMF642/656 + TMF653 tests + TMF724 incidents | `assurance` + `service-orchestration` + `intelligence` | ✅ (thin; real FM/PM needs the NMS; #76: diagnose as serviceTest with history, agent memory as standard incidents) |
| Qualify serviceability (commercial) | TMF679 | `qualification` | ✅ |
| Qualify service delivery (technical: technology, bandwidth, alternative) | TMF645 Service Qualification | `qualification` coverage map | ✅ #79/#80 (never a bare no — the best available technology proposed on refusal; ordering gates every placed item at create; the cart names technology + speed) |
| Schedule installations | TMF646 | `appointment` | ✅ (❌ full field-service mgmt: routing, van stock, workforce scheduling — now behind a REAL seam: work orders #73) |
| Ship & install what was sold | TMF700 Shipping Order + TMF697 Work Order | `fulfilment` (#37) | ✅ #73 (parcel + visit as resources, warehouse/installer API, machine completion when both gates pass, milestones on the process timeline; customers track deliveries) |
| Network inventory (physical/logical), mediation (CDR pipelines) | — | — | ❌ real OSS estate — or wrap it (suite #67 pattern) |

## 6. Partner & Enterprise

| Business capability | TMF tech capability | Component | Status |
|---|---|---|---|
| Manage channel partners & their compensation | dealer API | SOM | ✅ |
| Manage customer agreements & commitments | TMF651 | `agreement` | ✅ |
| Honor service-level promises (SLA → credit) | TMF623 SLA Management | `assurance` + `billing` | ✅ #74 (SLA terms as agreement DATA; late-resolved problems mint violations on a capped ledger; billing compensates with the PRE-AGREED credit note — no human decides at breach time) |
| Quote complex B2B deals | TMF648 | `quote` | ✅ (◐ full CPQ: approvals, versioned proposals, e-sign — no) |
| Operate multiple brands / tenants (MVNE) | tenant registry + realm mint | fleet-wide | ✅ #49 |
| Bundle partner services (VAS) | entitlement seam | SOM | ✅ (◐ settlement/rev-share no) |
| Type partnerships & their permitted roles (onboarding) | TMF668 Partnership Type | `agreement` | ✅ #83 (kinds as data; typed partnerships validated at signature) |
| Name & reuse customer/org sites (the address book) | TMF674 Geographic Site | `geographic-address` | ✅ #84 (site = name + owner + lifecycle + stored TMF673 address, embedded; reused on orders end to end) |
| HR / workforce mgmt (human), ERP, procurement | — | — | ❌ enterprise systems |
| BI / data warehouse | — | `insight` events help | ◐ insight is a CDP-ish spine, not a DWH |

## 7. Cross-cutting (the differentiators)

| Capability | Where | Status |
|---|---|---|
| AI control plane (meter/budget/kill-switch/audit) | `intelligence` | ✅ #59 |
| Govern AI at scale, standards-addressable (TMF915: model contracts, per-scenario brake, ai:admin) | `intelligence` /tmf-api/aiManagement | ✅ #81 (models the ledger proves; contracts with real metrics incl. every refusal class; suspend one scenario without stopping the fleet) |
| Agentic surfaces: GEO / ACP / MCP / workforce / closed loop | gateway + catalog + cart + intelligence + packages | ✅ #64–66, #68 |
| Legacy overlay (wrap an existing BSS) | 3 per-tenant seams | ✅ #67 |
| Event backbone + observability (Live Flow) | Kafka outbox + `flow` | ✅ |
| Let partners SUBSCRIBE to the event stream | TMF688 Event Management | `event-hub` (#38) | ✅ #75 (callback + filter, tenant-walled, delivery ledger w/ backoff retries + dead-letter that keeps its error) |
| Hardening: tick locks, resumable billing, backups, GDPR, PQC-ready | fleet | ✅ #56–58 |

## The honest summary for a buying CSP

**Complete here**: digital BSS core (catalog→order→activate→bill→cash),
six channels, martech, personalization, the full agentic layer, and
multi-tenant operation — proven by 76 suites and 13 CTKs.

**Bring (or keep) from elsewhere**: a production OCS for real-time
charging, a taxation engine, ERP/GL and rev-rec (now FED by the #70 journal-export seam), wholesale/roaming
settlement, fraud/revenue assurance, full field-service
management, SMS/push gateways, real OSS inventory + mediation, and the
enterprise estate (HR, procurement, BI warehouse). Every one of these
sits behind an existing seam or the overlay pattern (suite #67): the
list is an integration plan, not a wall.
