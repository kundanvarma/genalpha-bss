# Sales agentic layer + bid/RFP automation — plan

**Status:** planned (not built). Written 2026-08-17. Build post-demo, in a git
worktree so the demo stack (`main`) is never touched mid-arc.

## The gap
Sales is the one major desk with **no agentic layer**. What it has today is
deterministic and rule-based, and honest about it:
- lead **scoring** = rules (`LeadScoringRule`), lead **routing** = rules
- **guided selling** = a Q&A decision tree, not an LLM
- **pricing** = volume/segment rules; pipeline/forecast = arithmetic

Meanwhile catalog has the **Product copilot**, marketing has the **Marketing
copilot**, and ops has the **AI Workforce + Runbooks**. Sales has neither a
copilot nor a worker. And the two strongest B2B agentic use cases in the market —
autonomous SDR/deal coordination and RFP/bid response — live exactly here.

## Landscape (grounded, 2026)
- **Agentforce SDR / sales agents:** autonomous multi-step actions (retrieve CRM
  data, outreach, update records, escalate) driven by LLM reasoning **plus
  configurable rules**; ~70% autonomous resolution in early deployments, with
  humans taking the **warm, qualified handoff** rather than cold work.
- **AI RFP response (Responsive, Loopio, AutogenAI, Thalamus):** "shred" the RFP
  document → **extract requirements** → build a **compliance matrix**
  (requirement → response section, compliant/partial/gap) → draft from a
  **content library** → **compliance-gap check before submission** → full bid
  lifecycle (capture, bid/no-bid, SME routing, addenda, post-bid learning).

## Design principles (fit our architecture + the honesty rules)
Reuse the seams we already have — do **not** invent a parallel AI stack:
- Copilots live in the `intelligence` service (**propose → human applies with
  their own token**; the model never writes).
- Autonomous work rides `WorkforceService` (task KINDs derived live from real
  backlog; lease semantics; **verified completion**; `WorkforceApproval` on
  high-blast-radius; revocable `workforce:use` badge).
- `AiGovernor` governs every agent action (policy → model → meter → one audit
  row); the tenant **AI kill-switch** stops copilots AND workers with one lever.
- **Receipts, not vibes:** every AI answer cites its source (a catalog fact, a
  content-library article); ungrounded → flagged "needs SME," never invented
  (the Product-advisor rule).
- **No autonomous send/submit:** a human approves any outbound email, any
  discount past threshold, any bid submission.
- Real Claude via the existing AI-provider seam; a stub for CI.

## Component A — Sales copilot
Beside Product/Marketing copilots: a copilot package in `intelligence` + a
**Sales copilot** tab on the Sales desk (gated `quote:read`). Chat → propose →
seller clicks apply.
- **Quote from a sentence:** "quote AcmeCo 50 Unlimited 5G + 20 iPhone 17,
  12-month term" → proposes TMF648 line items priced by the existing
  `QuoteService` rules, for review.
- **Deal summary / next-best-action:** "this opp stalled 20 days in Proposal —
  suggest a nudge and draft the follow-up activity."
- **Pipeline Q&A:** "what's my commit forecast this quarter, and which three
  deals are most at risk?"
Boundary: proposes only; no auto-send.

## Component B — Deal-desk AI worker
New `WorkforceService` kinds alongside `KIND_TICKET` / `KIND_CASH`:
- `KIND_LEAD` (unworked/aging leads), `KIND_OPPTY` (stalling opportunities),
  `KIND_QUOTE` (quotes awaiting action) — **derived live** from the sales
  backlog, never a stale copy.
Worker actions are **proposals**: draft a follow-up, propose a next stage/nudge,
flag at-risk, assemble a quote draft. High-blast-radius (send email, submit a
quote, discount past threshold) → `WorkforceApproval`, human holds the key.
Verified completion (a lead task closes only when the lead is actually worked).

## Component C — Bid/RFP response automation  ★ the centerpiece
The full agentic bid lifecycle. New native entities in the sales/quote service;
TMF where it fits (there is no official TMF "RFP API").
1. **Intake / shred:** upload an RFP document → extract a structured
   **requirements matrix**. New: `rfp`, `rfp_requirement` (section, text,
   mandatory/optional).
2. **Bid / no-bid:** AI scores fit vs our catalog + capability (reuse **TMF679
   Product Offering Qualification** for serviceability) → recommends bid or pass;
   human decides.
3. **Match:** each requirement → a catalog offering / a content-library answer,
   with **compliance status** (compliant / partial / gap) and a **citation**
   (which offering or knowledge article backs it).
4. **Draft:** AI drafts each response from the **content library — reuse the
   `knowledge` service** as the answer store — plus catalog facts; gaps flagged
   for an SME. Compliance-gap check before the bid can be marked ready.
5. **Assemble / price:** the priced response becomes a **TMF648 quote** (a quote
   *is* the response to an RFQ) + a generated **compliance-matrix document**;
   discounts/approvals via the existing `QuoteConfigRule`.
6. **Submit:** human approves → the bid rides the CPQ path we already built
   (accept → TMF622 order + **TMF651 agreement**).
7. **Post-bid learning:** won/lost feeds back (won-by-source already exists);
   approved answers **promote into the content library** (Runbooks-style: a
   human-confirmed answer becomes reusable next time).

## Staging (each proven by a browser/API suite)
| Phase | Deliverable |
|---|---|
| SA1 | Sales copilot: quote-from-sentence + deal summary/NBA |
| SA2 | Deal-desk worker: KIND_LEAD/OPPTY/QUOTE derive → propose/approve → verified completion |
| RFP1 | RFP intake + requirements matrix (entities + AI shred with citations) |
| RFP2 | Requirement → catalog/knowledge matching + compliance matrix + bid/no-bid |
| RFP3 | AI drafting from the content library + gap flagging |
| RFP4 | Assemble priced bid as TMF648 quote + compliance doc → approval → order+agreement |
| RFP5 | Post-bid learning (win/lost feedback + answer promotion) |

## Honesty boundaries (stage rules — non-negotiable)
- Every RFP answer cites its source; ungrounded content is flagged "needs SME,"
  never invented.
- No autonomous submission — a human approves the bid.
- Estimates are labelled as estimates; the AI kill-switch stops copilot + worker.
- CI runs against the stub AI; real Claude only in the live stack.

## Cross-cutting
- **Events → martech (standing rule):** new events (`rfp.received`,
  `bid.submitted`, `bid.won`, `bid.lost`) must update the martech/CDP consumers.
- **Migrations:** odd Vn in the common dir, even Vn (RLS) in migration-postgresql;
  globally unique; table version < RLS version.
- **Build hygiene:** worktree off `main`; secret-scan every commit; suites green
  before merge; demo docs get a Sales-agentic scene only once SA1/SA2 land.

## Sources
- [Loopio — best AI tools for RFP responses (2026)](https://loopio.com/blog/best-ai-software-rfp-responses/)
- [AutogenAI — best RFP software 2026 (Gamma Review: extraction + compliance tracing)](https://autogenai.com/blog/best-rfp-software-2026/)
- [Thalamus — best AI RFP software 2026 (full bid lifecycle)](https://thalamushq.ai/blogs/best-ai-rfp-software)
- [Salesforce — Agentforce AI agent platform](https://www.salesforce.com/agentforce/)
- [Agentforce SDR overview 2026](https://www.salesforge.ai/directory/sales-tools/agentforce-sdr)
