# Tvilling — frontier-grade AI on customer text that never exposes a real person

**Status:** T-P1 SHIPPED 2026-08-19 (the deterministic twinning engine: `TwinningService` supersedes RedactionService — ONE pass yields the redacted stored text AND the twin, HMAC(per-signal key) surrogates from same-shaped Norwegian pools, offset map persisted; `PiiRecognizer` gains span-finding, `NameRecognizer` = the dictionary+capitalization floor (demo personas + ~90 NO/common names — recall is the honest limit), `NerRecognizer` = the config-enabled local-model seam (`bss.insight.ner-url`, fail-open); `twin_vault` V34+V35 RLS with party-keyed erasure — the orchestrator's erase now reports `twinKeysDestroyed`; `GET /signal/{id}/twin` shows the operator exactly what would leave. Proven live: "Kai Kunde… +47 99 88 77 66… kai@…" became "Eivind Lund… +47 43 18 42 61… vegard.haugen@example.net" — no real fact in the twin, in-signal consistency (same person = same fiction twice), cross-signal difference (signal 2 = "Sindre Tangen" — no longitudinal profile), the churn phrase verbatim-intact, 2 twin keys destroyed on erasure. signal_intelligence + martech + privacy suites green) · T-P2–T-P4 PLANNED · **Depends on:** the signal store + PII firewall (`PiiRecognizer`
seam, SI-P1), the evidence-verified battery (SI-P3), the AI audit ledger (AiGovernor), the fleet
privacy orchestrator, the hybrid-tier + flywheel roadmap (signal-intelligence plan)

**The problem, stated honestly.** Today the trade is three-cornered and every corner loses
something: *redaction* punches holes in the text (names and circumstances leak past a
deterministic floor, and what IS caught leaves semantic holes that weaken classification);
*local models* keep the data home but fail the evidence contract below ~30B; *hybrid* still
ships the hardest half to a US-processed API. Every vendor in the VoC category picks one corner
and writes a whitepaper around it.

**The idea.** Don't send the customer's text with pieces missing — send a **twin**: the same
narrative about a person who does not exist. Every identifying span (name, place, amount, date,
number) is swapped for a *consistent, same-shaped surrogate*; everything that carries meaning —
sentiment, syntax, the complaint, the threat to leave — survives intact. The frontier model
thinks at full power about the twin. Its evidence quotes come back in twin-space, and because
every swap is span-local and we hold the **offset map**, the quotes re-anchor to the real text
exactly — where the store's verbatim gate verifies them as if the frontier had read the truth.

What makes this more than clever: **the evidence gate turns twin fidelity from an assumption
into a per-signal measurement.** If a twin distorted the meaning, its quotes fail re-anchoring
or its classification fails verification — the signal drops to the local path instead of being
stored wrong. No other privacy architecture we know of can *prove each inference* survived its
own anonymization; surrogate substitution is established practice in clinical de-identification
(the "hiding in plain sight" family), but coupling it to a verbatim-evidence contract with
exact span re-anchoring, an erasure-cascading key vault, and a governance ledger is — as far as
we can tell — new, and it is only possible here because the receipts system already exists.

## The four properties that fall out

1. **A provider breach yields fiction.** What Anthropic (or any cloud) ever held was a story
   about "Håkon Lie in Bergen who owes 240" standing in for Kai in Oslo who owes 310 — same
   shape, same mood, no real fact. The DPIA conversation changes category.
2. **Erasure reaches the cloud retroactively.** The surrogate map is keyed per signal and
   stored in an RLS'd vault; the fleet's privacy orchestrator already cascades erasure to the
   signal store — destroy the mapping key and the twin becomes *permanently unlinkable*. What
   remains anywhere outside is anonymous fiction. (Stated carefully: pseudonymization whose key
   is destroyed *approaches* anonymization; the LIA/DPIA still gets written, but it gets
   written about fiction with a destroyed key, not about customer data in transit.)
3. **Classification quality is frontier quality.** Nothing is deleted from the text — no
   holes, no [TOKENS] where the emotional center of the sentence used to be. The twin reads
   like a real complaint because it is one, about nobody.
4. **The exposure is measurable, per call.** Every outbound AI call gets an **exposure
   receipt** on the audit ledger: which data classes left (twinned narrative / aggregates /
   nothing), to which provider and jurisdiction, under which basis, with which twin-fidelity
   result. Compliance becomes a live pane, not an annual PDF.

## The phases

### T-P1 — the twinning engine (deterministic core)
- Generalize `RedactionService` into a `TwinningService`: the `PiiRecognizer` seam (plus the
  planned local-NER recognizer for names/addresses — REQUIRED here, it finds what to swap)
  yields spans; each span is replaced by a surrogate drawn deterministically via
  HMAC(signalKey, span) from same-shaped pools — names→names (same cultural register), NO
  cities→NO cities of similar size, amounts→jittered buckets, dates→shifted consistently
  within the signal. Consistency matters: the same person mentioned twice becomes the same
  twin twice.
- Output: twin text + **offset map** (real-span ↔ twin-span) + a `twin_vault` row (V-next,
  RLS): signalId, signalKey, map — erased by partyId with everything else (the orchestrator
  hook exists).
- No LLM in the loop: the twinning is deterministic, testable, and auditable. (A local-LLM
  fluency pass is a later option, off by default — determinism is the feature.)

### T-P2 — frontier inference in twin-space + re-anchoring
- The battery's escalation path (the hybrid from the SI plan) sends the TWIN, never the
  original. Evidence quotes return in twin-space; the offset map re-anchors them to real-text
  spans; the store's verbatim gate then verifies the re-anchored quotes exactly as today.
  A quote that spans a swap boundary re-anchors cleanly (swaps are span-local); one that
  cannot re-anchor drops the classification — fidelity failure is a DROP, never a wrong row.
- Twin-fidelity metric on the ledger per call: re-anchor success, quote-verify success.
  The pane's method label stays honest about which path classified what.

### T-P3 — exposure receipts + the compliance pane
- AiGovernor records, per call: exposure class (`none` local / `twin` / `aggregate` /
  `raw-redacted` legacy / `raw` for old copilot flows), provider, jurisdiction, basis. One
  new column family on the existing ledger.
- Console: Privacy & governance → **AI data flows** — live counts by exposure class, the
  copilot flows named and visible (the audit will show the CSR-summary flow is the rawest one
  in the fleet — making the unexamined visible is the point).
- The copilot prompt audit rides here: each AI use-case declares its exposure class; `raw`
  requires an explicit per-tenant opt-in flag. Governance as configuration.

### T-P4 — canaries + the per-tenant model farm
- **Canaries:** a per-call synthetic marker embedded in outbound twins; scheduled probes
  verify no provider ever regurgitates one — provable non-retention monitoring, on the ledger.
- **The farm:** the flywheel's evidence-verified pairs accumulate per tenant; nightly LoRA
  fine-tunes give each tenant its own local adapter (their taxonomy, their language mix,
  their labels — trained only on receipt-verified rows, so the model is never taught a
  hallucination). The frontier share shrinks tenant by tenant; a tenant whose adapter passes
  the harness goes fully local and their exposure class pane reads `none`.

## The economics (why volume doesn't scare this design)

Twinning is deterministic local string surgery — it adds ~zero cost. The frontier calls are
the cost, and five levers compound against them: (1) the HYBRID sends only local evidence-
failures (~50% today, shrinking via the flywheel toward a rounding error — steady state is
one local GPU's electricity); (2) PROMPT CACHING: every battery call shares one identical
system prompt, ~90% off repeated input — roughly halves the bill by itself; (3) BATCH API:
the battery is a scheduled sweep, nobody waits — 50% off; (4) TIER CHOICE: `AI_MODEL_FAST` to
a Haiku-class model is one env var at ~5× cheaper than Sonnet, and unlike a local 8B it very
likely passes the evidence gate (one harness run verifies); (5) the AiGovernor's per-window
BUDGET refuses runaway spend by design. Order of magnitude: ~500 in / ~200 out tokens per
signal ⇒ naive Sonnet ≈ $0.005/signal; Haiku+caching+batch ≈ $0.0002–0.0005 — a
1M-subscriber operator at 50k signals/month lands in the tens of dollars, decaying as the
per-tenant adapter matures. T-P3's pane shows spend beside exposure class: what left, and
what it cost.

## Honesty box
- Surrogate substitution is not new (clinical de-identification has done it for years); the
  claimed novelty is the COMPOSITION — twin + verbatim-evidence re-anchoring + erasure-cascade
  vault + exposure ledger — and it should be stated exactly that way, nowhere stronger.
- Twinning inherits the NER recognizer's recall: a name the NER misses is a name that travels.
  T-P1 ships with the NER layer and its measured recall ON the pane, not with a promise.
  Deterministic floor + NER + twin is defense in depth, not perfection.
- "Destroyed key ≈ anonymous" is an argument, not a statute: counsel signs the final word
  per market. The design's job is to make that argument as strong as it can be made.
- The legacy copilot flows are TODAY'S largest raw exposure and predate this plan; T-P3 makes
  them visible and gated rather than pretending the new module was the problem.
- Discretion: this architecture is a competitive asset — code-visible in the repo per the
  standing decision, but not for public marketing until cleared.
