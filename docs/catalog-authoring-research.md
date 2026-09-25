# Who authors the fulfilment chain, and how — research before the UI

*Research note, 25 September 2026, for CFS step 3 (spec #85). Question: when
a product manager sets up a product in the catalog, what should they have to
say about fulfilment (CFS, RFS, resource specs), what should be said for them,
and how should an AI-native BSS do this better than the incumbents? Primary
sources are the vendors' own documentation and TM Forum's; where a page was
not reachable, the claim is marked as coming from a summary and kept modest.
Companion to [catalog-to-provisioning.md](catalog-to-provisioning.md).*

## What the incumbents do

**Oracle (Solution Designer / OSM).** Authoring is split by role. The
*product specialist* "creates product specifications, along with their
commercial parameters and mappings"; the *inventory specialist* "creates data
elements, service specifications, and resource specifications"; the
*fulfillment specialist* "enriches [the capabilities cartridge] using the
fulfillment model"; a *service catalog administrator* moves an *initiative*
through review and testing and "publishes the initiative" to production. The
product side never edits RFS: a product specification is mapped to a
**fulfillment pattern**, and "many … product specifications can map to the
same fulfillment pattern, which enables you to introduce new products or
product variations without requiring new fulfillment patterns." Telling
detail: "the mapping of data to Fulfillment Function … [is] hardcoded within
the capabilities cartridge" — even Oracle keeps the characteristic-to-function
mapping in code, not in the designer.

**Salesforce Industries (EPC / Order Management).** Two catalogs in one:
"commercial products are the customer-facing assets available for purchase";
"technical products are the underlying back-end components that order
management uses to fulfill orders". "At design time, technical users such as
fulfillment designers configure the decomposition processes." The published
best practice is separation: "ensure only commercial data is modelled in the
commercial layer and avoid modelling technical data requirements for
downstream systems" — the product manager is deliberately kept away from the
technical layer, and technical products are reused across offers.

**Amdocs (Catalog).** Role-based interface and templates "reducing IT
dependency"; the catalog "generates optimized and targeted offers in minutes
by leveraging a newly embedded copilot". The copilot is on the commercial
side (offers), not on the CFS/RFS side, as far as the public material shows.

**Netcracker.** A "Catalog Assistant" scenario in its GenAI telco solution;
public material describes assistants over the catalog, not authoring of the
technical layer.

**TM Forum.** ODA's Product Catalog Management component "enables define
Product specifications based on CFS specifications published by the Service
Catalog Management component — or for tangible products on Resource
specifications published by the Resource Catalog Management component": the
product layer *consumes* published CFS, it does not author them. The
*Generative orchestrator* Catalyst (2025) aims "to expose a new product
specification and structure it into an offer within minutes — using nothing
more than a process diagram", with GenAI producing the specification; the
*Big Deal* Catalyst uses GenAI to guide configuration and contracting on top
of a TMF-compliant catalog.

**The pattern across all of them.** Three things are constant: (1) the product
manager picks a *named pattern* and never sees seams; (2) the technical layer
is authored by a different role, reused across many products, and released
through a review gate; (3) the mapping of characteristics to fulfilment steps
is the least tooled part — hardcoded (Oracle) or configured by technical
users (Salesforce). Nobody shows the product manager *what will actually
happen* when their product is ordered; that is discovered in test.

## What this means for us

Our step 2 already has the pieces the incumbents keep apart: the chain is
standard data (TMF620 → 633 → 634), the orchestrator *records* what it did
(realisations), and a gate compares the two. The gap is the two seats at the
table and the fact that the product manager cannot operate any of it from the
console today (the spec's CFS link and the CFS/RFS editors exist only through
the API and a wholesale-only tab).

### Principles for the authoring experience

1. **The product manager picks a fulfilment pattern by name, never an RFS.**
   "Mobile line", "Broadband access", "Nothing to provision". The pattern *is*
   the CFS; the RFS underneath are shown as consequences, in words, not
   edited on that screen. This is Oracle's and Salesforce's split, and ADR
   0018's rule (screens speak operator language).
2. **A different seat authors patterns**, rarely, with review: the solution
   designer's page lists CFS, the RFS each relies on with a required / "only
   when the product carries…" toggle, and what each consumes as named fields.
   Publishing a pattern change is a governed action with a receipt (ADR 0011),
   because it changes what every order of that pattern does.
3. **The copilot proposes, a human presses Create (ADR 0012).** The product
   copilot already turns "a social pack with WhatsApp and TikTok free" into an
   offering card. It should also propose the pattern: from the description and
   characteristics it says "this is a *Mobile line*; it carries no charging
   plan, so charging will not be provisioned — add one?" The card names the
   consequences; Create is a click; the assignment is a governed action.
4. **Show what will happen before it happens.** A *dry run* of the step-3
   executor — same walk, no adapters called — returns the realisation plan:
   which seams, in which order, with which consumed values and which vendor.
   It sits on the offering page beside the Decomposition panel and becomes a
   launch-readiness item: a product whose dry run disagrees with its pattern
   does not launch. This is the piece none of the incumbents offer to the
   product manager.
5. **Let the evidence talk back.** The disagreement gate already knows when
   the orchestrator did something the pattern never declared. Turn its
   findings into suggestions on the pattern page ("40 orders of Mobile line
   provisioned a SIM your pattern does not declare — adopt it?"), logged as
   decisions with outcomes (ADR 0013), so the catalog learns from what the
   network actually did. The same mechanism proposes patterns *for* the
   5,000-odd services that predate step 2, by reading their resources.
6. **Agents use the same doors.** Every authoring action (assign a pattern,
   author a pattern, approve a pattern change) is an ontology action and an
   MCP tool, so an AI agent authoring a product under a tenant's governance
   goes through the same check/execute/receipt as a human, with the same
   approval ladder for the technical layer.

### What "out of the box" adds beyond the incumbents

- **Conversation as the form, consequences as the card.** The product manager
  describes the product; the copilot fills the commercial fields *and* the
  pattern, and the card shows the fulfilment consequences in one sentence
  each. Editing a field re-runs the dry run live.
- **Patterns mined from reality.** The technical layer is not only authored
  top-down; the realisation records let the system propose the pattern a
  family of products actually needs, which is how a migrated base gets its
  CFS without a designer typing 5,000 links.
- **Drift as a first-class signal**, not a test failure: the gate's findings
  reach the person who owns the catalog, with the fix offered as a click.
- **One vocabulary for humans and agents.** The glossary (CONTEXT.md) terms
  are the ontology's concepts; the copilot, the page and the MCP tools speak
  them, so an agent's proposal reads exactly like a colleague's.

## Recommendation for step 3

Split the UI ticket into three, and put the first two into step 3:

- **8a — Pattern picker and copilot proposal on the product spec** (product
  manager's seat). A *Fulfilment* field listing the tenant's CFS by name with
  their family in words and the RFS consequences underneath; the product
  copilot proposes the pattern and names missing consumed values; assignment
  is a governed action `assignFulfilmentPattern` with a receipt. In the
  existing Product Specification form. About a day.
- **8b — Dry run** (both seats). A SOM endpoint that walks the executor
  without calling adapters and returns the realisation plan; shown on the
  offering page; a launch-readiness item in launch governance. About a day,
  and it is only possible because step 3 builds the executor.
- **8c — Fulfilment patterns page** (designer's seat), with the gate's
  findings as suggestions and pattern-mining from realisations. This is the
  first piece of the Offering workspace, which the backlog already decided to
  build in React from the start (ADR 0017's first island). Two to three days;
  its own arc after step 3, so the executor it edits exists first.

## Honest limits

- Vendor statements summarise public documentation and TM Forum pages; they
  are not an evaluation of any vendor's current release. Several TM Forum and
  Salesforce pages were not reachable at research time and are quoted from
  their public summaries only.
- No incumbent's UI was used hands-on; the "nobody shows what will happen"
  claim rests on their documentation not describing such a screen, not on
  proof of absence.
- The copilot proposing a pattern is a design, not a measurement; whether the
  model picks the right CFS for a free-text description is a suite to write.

## Sources

- Oracle, *Solution Designer concepts — roles and initiatives*:
  https://docs.oracle.com/en/industries/communications/service-catalog-design/8.3/concepts/solution-designer1.html
- Oracle, *Working with fulfillment* (fulfillment patterns, product mapping,
  hardcoded data mapping):
  https://docs.oracle.com/en/industries/communications/service-catalog-design/8.3/users-guide/working-fulfillment1.html
- Oracle OSM, *Working with Fulfillment Patterns*:
  https://docs.oracle.com/cd/E65400_01/doc.731/e65407/sce_com_model_fulf_patt.htm
- Salesforce Trailhead, *Meet Industries Order Management decomposition*
  (fulfillment designers, commercial vs technical):
  https://trailhead.salesforce.com/content/learn/modules/industries-order-management-decomposition-foundations/meet-industries-order-management-decomposition
- Salesforce Help, *Enterprise Product Catalog*:
  https://help.salesforce.com/s/articleView?language=en_US&id=ind.comms_enterprise_product_catalog__epc_.htm&type=5
- Apex Hours, *EPC best practices* (separation of commercial and technical):
  https://www.apexhours.com/enterprise-product-catalog-epc-best-practices/
- Amdocs, *Catalog* product page and amAIz announcements:
  https://www.amdocs.com/products-services/catalog ·
  https://www.amdocs.com/news-press/mwc24-amdocs-amaiz-empowers-service-providers-create-genai-powered
- Netcracker, *GenAI Telco Solution* press release (Catalog Assistant):
  https://www.netcracker.com/news/press-releases/netcracker-launches-pioneering-solution-to-advance-generative-ai-for-the-telecom-industry
- TM Forum, *TMFC001 Product Catalog Management* (ODA component directory):
  https://www.tmforum.org/oda/directory/components-map/core-commerce-management/TMFC001
- TM Forum Catalyst, *Generative orchestrator — automating product and network
  alignment with GenAI*:
  https://www.tmforum.org/catalysts/projects/C25.0.762/generative-orchestrator-automating-product-and-network-alignment-with-genai
- TM Forum Inform, *How to achieve quote automation using GenAI* (Big Deal):
  https://inform.tmforum.org/research-and-analysis/proofs-of-concept/how-to-achieve-quote-automation-using-genai
- This repo: ADR 0011 (governed actions), 0012 (AI proposes, a human decides),
  0013 (decision log), 0017 (React islands), 0018 (operator language);
  `docs/backlog-2026-09-23.md` (Offering workspace in React);
  `docs/console-workspaces-plan.md` (Ask Copilot as an action).
