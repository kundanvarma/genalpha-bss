# Sales agentic layer + bid/RFP automation — plan (v2)

**Status:** planned (not built). v1 written 2026-08-17 was parity engineering —
it ported Agentforce/Loopio patterns onto our seams. v2 leads with the thesis
that only a real BSS can deliver. Build post-demo, in a git worktree off `main`.

## The gap
Sales is the one major desk with **no agentic layer** — scoring/routing/guided/
pricing are rules, forecast is arithmetic. Catalog has the Product copilot,
marketing the Marketing copilot, ops the AI Workforce + Runbooks. Sales has
neither a copilot nor a worker. The two strongest B2B agentic use cases in the
market — autonomous SDR/deal work and RFP/bid response — live exactly here.

## The thesis: **grounded-in-delivery bidding**
The market's dominant failure mode is one thing: **hallucination, and the trust
collapse after it.**
- RFP tools: *"a single wrong answer can disqualify an entire submission."* The
  2026 fix is **grounding** — trace every statement to an indexed source; RAG
  cuts hallucination ~40%.
- AI SDRs: *only ~2% survive year one; 50–70% churn;* 11x had a fabricated-claims
  scandal and legal threats. *"Only as good as the CRM feeding it."*
- Telecom CPQ: the bleed is the **quote-to-cash gap** — quotes that can't be
  delivered without re-keying; analysts say CPQ + order management must be one.

Every incumbent grounds prose in a **document library** (past answers) and bolts
agents onto **someone else's CRM**. We can do something none of them can, because
**we are the system of record** for catalog, coverage, inventory, pricing, orders,
usage, billing, and wholesale: **ground every answer in the live delivery system,
not a library.** Three consequences no bolt-on can match:

1. **Verifiable, not plausible.** Each bid answer is a **live query with a
   re-runnable receipt** — "we serve 187/200 sites" is a TMF679/coverage result,
   the price is a real catalog calc, the SLA comes from the actual service spec.
   The buyer's evaluator can re-run it. This doesn't reduce hallucination — it
   removes the category: you cannot claim a capability the system will refuse to
   fulfil.
2. **Deliverable by construction.** The bid *is* a TMF648 quote already wired to
   the order + TMF651 agreement path. Win → it's already an order. No
   quote-to-cash gap, no re-keying.
3. **Provable after award — a primitive nobody else has.** Because we're also the
   usage/billing/SLA system, the bid's promises become **monitored obligations**:
   the platform that won the bid proves ongoing compliance from real telemetry.
   Standalone tools exit at "submitted." We close the loop
   **bid → agreement → live SLA proof** — call it **continuous bid-compliance.**

## Why only a real BSS can do this (the moat)
- **Feasibility is a query, not a claim** (coverage map, TMF679 qualification).
- **Price is a real calc** (catalog + pricing rules + wholesale rate cards), so
  **bid/no-bid is a margin decision** (ledger/COGS), not a gut feel.
- **Supply is two-sided.** We already have MEF Sonata wholesale seeker/provider
  rails, so gaps can be **sourced**, not just conceded (see Component C).
- **Delivery + assurance are ours**, so promises are enforceable after award.

## Design principles (the honesty rails ARE the moat)
In a market where autonomous players churn at 70% and get sued over fabrication,
our governance is the only posture that survives — and it's already how the rest
of the BSS behaves. Non-negotiable, and we lead with them:
- **Propose, don't send.** No autonomous outbound email, submission, or
  above-threshold discount without human approval (`WorkforceApproval`).
- **Cite or flag.** Every answer links to a live system fact or an approved
  library entry; ungrounded → **"needs SME,"** never invented.
- Reuse the seams: `intelligence` copilots, `WorkforceService` KINDs, `AiGovernor`
  (policy→model→meter→audit), the tenant **AI kill-switch** (one lever stops
  copilots AND workers). Real Claude via the provider seam; stub for CI.

## Component A — Sales copilot (grounded proposals)
Beside Product/Marketing copilots: a copilot in `intelligence` + a **Sales
copilot** tab (Sales desk, `quote:read`). Chat → propose → seller applies.
- Quote-from-a-sentence → proposes TMF648 lines priced by the real `QuoteService`
  (a receipt, not a guess).
- Deal summary / next-best-action grounded in the account's **real** state
  (orders, usage, entitlements), not scraped enrichment.
- Pipeline Q&A over live pipeline arithmetic.

## Component B — Deal-desk AI worker (fires on operational ground-truth)
New `WorkforceService` kinds — `KIND_LEAD`, `KIND_OPPTY`, `KIND_QUOTE` — derived
live from the sales backlog like `KIND_TICKET`/`KIND_CASH`. The differentiator vs
the AI-SDR pack: triggers are **real operational events off our own bus**, not
scraped intent — *"this prospect's area just got fibre-lit,"* *"the trial's usage
crossed a threshold,"* *"a covered site saw an outage."* Actions are **proposals**
(draft follow-up, propose next stage, flag at-risk, assemble a quote draft);
outbound/high-blast-radius → approval; verified completion; revocable badge.

## Component C — Bid/RFP response automation ★ the centerpiece
The delivery-grounded bid lifecycle. New native entities (`rfp`,
`rfp_requirement`) in the sales/quote service; TMF where it fits.
1. **Intake / shred** → structured **requirements matrix** (section, text,
   mandatory/optional), each requirement carrying a compliance slot.
2. **Feasibility-first** → run **TMF679 qualification + coverage** on the ask
   *before* any prose. We never draft a beautiful answer to a requirement we
   can't meet — we surface the gap with a build-timeline or a sourcing option.
3. **Machine-verifiable compliance** → each requirement → **compliant / partial /
   gap**, tied to a **live system fact** (an offering id, a coverage result, an
   SLA from a service spec) **+ citation**. This is the compliance matrix, but
   *checked against system state*, not against a prose library.
4. **Two-sided sourcing** (unique) → for gap sites, **auto-issue outbound
   wholesale RFQs** over MEF Sonata to owners/carriers; fold the responses into a
   **blended bid**: on-net where we reach, wholesale-sourced where we don't. "200
   sites: 187 on-net + 13 sourced," one deliverable price.
5. **Bid/no-bid on real margin** → catalog price − COGS − wholesale cost from the
   ledger; recommend bid/pass; human decides.
6. **Draft** → prose from the **knowledge library** (reuse the `knowledge`
   service) + catalog facts; gaps flagged for an SME; a compliance-gap check gates
   "ready."
7. **Assemble / submit** → priced **TMF648 quote + generated compliance-matrix
   doc**; discounts via `QuoteConfigRule`; human approves → the CPQ path
   (accept → TMF622 order + TMF651 agreement). **Deliverable by construction.**
8. **Continuous bid-compliance** (unique) → the agreement's promised SLAs/coverage
   become **monitored obligations**; assurance telemetry proves them post-award,
   and a breach opens the loop back (ticket/credit). The bid keeps proving itself.
9. **Post-bid learning** → win/lost feedback (won-by-source) + approved answers
   promote into the knowledge library (Runbooks-style).

## Staging (each phase suite-proven, worktree off `main`)
| Phase | Deliverable |
|---|---|
| SA1 | Sales copilot: grounded quote-from-sentence + deal summary/NBA |
| SA2 | Deal-desk worker: KIND_LEAD/OPPTY/QUOTE, event-triggered, propose→approve |
| RFP1 | Intake + requirements matrix (entities + AI shred with citations) |
| RFP2 | **Feasibility-first**: TMF679/coverage per requirement → compliant/partial/gap + citation |
| RFP3 | **Two-sided sourcing**: outbound wholesale RFQ for gap sites → blended bid |
| RFP4 | Bid/no-bid on real margin; AI draft from the knowledge library + gap flagging |
| RFP5 | Assemble priced TMF648 quote + compliance doc → approval → order+agreement |
| RFP6 | **Continuous bid-compliance**: promises → monitored obligations → live SLA proof |
| RFP7 | Post-bid learning (win/lost + answer promotion) |

## Cross-cutting
- **Events → martech (standing rule):** new events (`rfp.received`,
  `bid.submitted`, `bid.won`, `bid.lost`, `obligation.breached`) must update the
  martech/CDP consumers.
- Migrations: odd Vn common / even Vn RLS; globally unique; table < RLS version.
- Build hygiene: worktree off `main`; secret-scan; suites green before merge; the
  demo gets a Sales-agentic + Bid scene only once the slices land.

## The demo moment (what a prospect should see)
Paste a 200-site enterprise RFP → the matrix fills itself → feasibility lights
187 green / 13 red → the 13 auto-issue wholesale RFQs and come back sourced →
a blended, margin-checked, **priced** bid assembles with a compliance matrix
where **every line is a re-runnable receipt** → one approval turns it into an
order + contract → post-award, the same screen shows the SLAs being kept.
"Not a proposal that sounds right — a bid the system can prove, and then does."

## Sources
- [AI-SDR limitations, honest assessment (instantly.ai)](https://instantly.ai/blog/ai-sdr-limitations-honest-assessment/)
- [The AI sales industry 2026 field guide (SellScale)](https://www.sellscale.com/blog-posts/the-ai-sales-industry-in-2026-a-field-guide)
- [AI hallucination risk in proposals (AutogenAI)](https://autogenai.com/blog/ai-hallucination-how-can-proposal-teams-reduce-risk/)
- [Grounded / no-hallucination RFP answers (Iris AI)](https://heyiris.ai/blog/preventing-ai-hallucinations-with-verified-data)
- [AI proposal tools — source-of-truth & compliance (Anchor)](https://www.getanchor.ai/articles/ai-proposal-tools-security-compliance-officers-2026)
- [Telecom order management + CPQ as one (CSG)](https://www.csgi.com/insights/why-telecom-order-management-and-cpq-systems-need-to-work-as-one-to-win-b2b)
- [Telecom CPQ challenges & serviceability (Techno FAQ)](https://technofaq.org/posts/2026/03/telecom-cpq-challenges-and-best-practices-for-quoting-telecommunications-services/)
- [Agentforce SDR overview 2026](https://www.salesforge.ai/directory/sales-tools/agentforce-sdr)
