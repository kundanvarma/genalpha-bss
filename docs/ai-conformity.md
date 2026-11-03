# AI conformity evidence — what an operator inherits

An operator that runs this BSS is, under the EU AI Act, the **deployer** of the
AI it contains; Taranga is the **provider** of the system. A deployer needs
evidence, and evidence written after the fact convinces nobody. This page maps
what the Act and the first harmonised standard ask for onto what the BSS
already does, and names the proof for each — a test, a suite, a record. It is a
map, not a certificate: nothing here has been audited.

Dates are those of the November 2026 research (#422): Art. 50 transparency
applies since 2 Aug 2026; the omnibus moved Annex III high-risk duties (which
include credit scoring of consumers) to 2 Dec 2027; EN 18286 (AI quality
management) is the first harmonised standard, cited in October 2026; ISO/IEC
42001 is the certifiable management-system standard. Norway applies the Act
through the EEA; its own law is expected in 2027.

## The map

| Duty | What the BSS does | Proof |
|---|---|---|
| **Tell people it is AI** — Art. 50(1) | The care chat says so before any answer, labels every bot reply and records when; the bot's first answer says it too | `ops/e2e/care_chat_test.js`, `docs/ai-transparency.md` |
| **Mark AI-generated content** — Art. 50(2) | Every AI draft leaves intelligence with a signed receipt; the campaign component marks campaign and journey copy (branches and A/B arms included) from that receipt itself, so a request body cannot clear it, and the mark rides to the message sent; AI captions are labelled. Copy that arrives with no receipt is marked only if the caller says so | `AiDraftProvenanceTest`, `AiProvenanceTest`, `AiContentMarkingTest`, `docs/ai-content-marking.md` |
| **Human oversight** — Art. 14 | Agents act only through registered ontology actions: permissions, policy, an autonomy gate per action that an agent's own word can only tighten, an approval ladder; money above a line goes to a named approver; a machine's budget is per target, holds under concurrency and binds an approver's token when an agent drives it | `AutonomyGateTest`, `MachineValueBudgetTest`, `CreditLadderTest`, ontology `PostgresMigrationTest` |
| **Record keeping** — Art. 12 | Every model call on the AI ledger (provider, model, tokens, outcome, jurisdiction); every agent action a write-ahead receipt in a hash chain, saying whether the agent was verified; a decision log with outcomes; traces join them | `EvidenceIsAPreconditionTest`, `AgentIdentityTest`, `docs/agent-identity.md` |
| **Information for deployers** — Art. 13 | Operator-language docs per arc, each ending with Honest limits; a model contract per use case | `docs/*.md` |
| **Data protection by design** — GDPR Art. 25 | Prompts redacted before they leave, with reversible placeholders; raw exposure only by tenant opt-in; an EU-only model route per tenant | `RedactorTest`, `AiGovernorRedactionTest` |
| **Decisions about people** — GDPR Art. 22, CJEU C-203/22, CCD2, Annex III from 2 Dec 2027 | A credit decline never refuses on its own: a person reviews; the customer reads the reason in plain words and can ask for a review; a notice before checkout | `CreditExplanationApiTest`, `docs/credit-decisions.md` |
| **Robustness and security** — Art. 15 | Customer chat messages and classified social posts reach the model fenced as untrusted (copilot prompts and a worker's own prompts through the model door are not); a worker's actions still pass the ontology's checks and money budget; tenant walls tested; injection cases in the evals; the AI layer reaches no database | `UntrustedFenceTest`, `GuestShelfTenancyTest`, `ops/evals` |
| **Accuracy, measured** — Art. 15, EN 18286 verification | Evals against the running system: deterministic checks, simulated customers, a versioned judge with a rubric hash | `ops/evals/care_chat_evals.mjs` |
| **Quality management** — Art. 17, EN 18286 | Proof-first delivery: a numbered browser suite per feature, an architecture ratchet and a claims gate in CI, every gate seen failing before it is trusted | `ops/arch/ratchet.sh`, `ops/arch/claims.sh` |
| **AI literacy** — Art. 4 | Every AI surface explains itself in the operator's language; staff drafts say "verify before acting" | the screens |

The first live run of the evals found two real faults in the care chat — every
guest question was being redacted away, and a failed escalation promised a
ticket that did not exist. Both are fixed; that is the point of measuring.

## What a deployer still has to do

- Name the people who approve: the approval ladder needs approvers.
- Keep the logs for its retention period, and run the evals before any release
  that changes a prompt, a model or a rule.
- For credit decisions from 2 Dec 2027: the deployer's impact assessment and
  registration duties.
- Choose its model route (EU-only or not) and tell its customers which.

## Honest limits

- No external audit, no ISO/IEC 42001 certification, no EN 18286 conformity
  statement — this page is where an auditor would start.
- The evals cover the care chat only, run on the laptop fleet with a real model,
  and are not yet scheduled.
- No voice agent exists, so spoken disclosure is untested.
