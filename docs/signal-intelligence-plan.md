# Signal intelligence — the BSS that listens (VoC / customer-signal module)

**Status:** SI-P1 SHIPPED 2026-08-19 (`customer_signal` V26+V27 RLS; PII firewall at ingest — `PiiRecognizer` bean discovery, deterministic NO/EN floor (FNR/CARD/EMAIL/PHONE), redacted-only storage + on-row redaction audit; `POST/GET /insight/v1/signal` idempotent by (source, sourceRef|text) hash; internal sources feed themselves — trouble tickets via `SignalListener` on `bss.ticket.events`, mentions + DMs in-process at sync; fleet privacy orchestrator export/erase now covers signals (`signalsDeleted` in the report), active-contract 409 untouched. Proven live: planted fnr/phone/email/card all caught in Norwegian text, dedup, ticket→signal over the bus with firewall applied, erasure cascade; privacy_test + martech_test + product_trait_retraction green. DEVIATIONS: `signal:write` machine scope deferred to SI-P2's connector family — POST rides the house `insight:read` until then; redaction audit lives on the row, the AI-ledger entry starts when a model-based NER recognizer joins the list) · SI-P2 SHIPPED 2026-08-19 (`signal_connector` V28+V29 RLS — per-tenant bindings, kind/source/mode, secrets as env-var names; `SignalConnectorAdapter` family with `servicedesk` as the named reference (Zendesk wire, `mock-servicedesk` :8142 with PLANTED PII); `POST /connector/{name}/sync` idempotent via the signal dedup; the GENERIC inbound webhook `POST /hook/{connectorId}` — anonymous at the gateway, per-connector shared secret compared constant-time, foreign shape mapped by JSON pointers, unknown-vs-wrong-secret refusals indistinguishable (existence never leaks); call transcripts = a webhook connector with `/transcript` pointers (`seed_signal_connectors.py` binds both). Proven live: desk sync 4 tickets with fnr/phone/email caught, re-sync 0/4 duplicates, Norwegian transcript through the hook redacted, 401 on wrong/missing secret; martech + retraction suites green. `signal:write` machine scope still deferred — webhook secrets cover external push without a realm change) · SI-P3 SHIPPED 2026-08-19 (`signal_classification` V30+V31 RLS in insight; the battery lives in intelligence — `SignalClassifier` sweep (scheduled + `POST /ai/v1/signalSweep`), FAST tier through the `AiGovernor` (audit + budget), strict-JSON taxonomy: sentiment/aspect/category/painPoint+impact/loyaltyIndicator/churnSignal+reason; EVIDENCE quotes verified TWICE — classifier-side and at the store, which 422s fabricated quotes; provider+model ride the row (anthropic/claude-sonnet-5 live); accepted churnSignal sets the `churnSignal` trait (audience-targetable) and `SignalClassifiedEvent` goes on `bss.insight.events` — campaign topics updated per the standing martech rule. Proven live: 13 signals classified 0 dropped incl. a Norwegian switch-threat caught as churnSignal=true with verbatim evidence; hallucination gate refused fabricated quotes; martech + risk suites green. NOTE: the LOCAL-model posture is config-ready but genalpha's FAST tier currently falls back to the frontier model — the honesty-box eval-set task before calling the tier settled stands) · SI-P4 SHIPPED 2026-08-19 (console **Voice of Customer** pane in the Marketing desk — per-aspect cards with sentiment mix, this-week trend, top pain points w/ impact, click-to-drill to source signals, and the honest method label ON the pane: "battery-aggregates-v1 … not embedding clustering". Early warning: `voc_alert` V32+V33 — one auditable alert per (aspect, ISO week) when 7-day negatives ≥2× trailing baseline and ≥5, `VocDeviationEvent` on the bus (hourly sweep + `POST /insight/v1/voc/sweep`). CLTV in intelligence: `CltvScorer` (hourly + `POST /ai/v1/cltvSweep`) — transparent formula `avgMonthlyBilled × expectedMonths (churn-alerted ? 9 : 24)` from REAL bills + the churn book, event carries its inputs, lands as NUMERIC `cltv` trait via the trait listener → "worth ≥ 5000" is an audience leaf. Proven live: 5 planted network complaints → aspect deviating → alert fired ONCE (idempotent per week) + bus event; 301 customers CLTV-scored, trait values landed; pane browser-verified as the marketing persona; martech + retraction suites green. DEVIATION: CSR-360 CLTV display deferred — agents lack insight:read; needs a scoped read or a csr-side proxy) · SI-P5 SHIPPED 2026-08-19 (ASK: `POST /ai/v1/voc/ask` — SMART tier through the AiGovernor, grounded on AGGREGATES ONLY (never raw signal text, the doctrine held), pain points cited by [signalId], "say so when the data doesn't answer"; the VoC pane gains the Ask box with the grounding label printed on it. ACT: connector kind `slack-webhook` / mode `notify` — the webhook URL is a SECRET (env-var name; a Slack incoming-webhook URL is a credential), `AlertNotifier` posts the Slack-shaped {text} on every fired VoC alert, fail-open per webhook; `mock-chatops` :8143 is the dev sink, Teams/Slack = the real URL. Consolidated acceptance `signal_intelligence_test.js` (7 legs): firewall+idempotence, connectors incl. 401 door, battery evidence + 422 forgery, billing-flood → ONE alert + chat notification + same-week dedup, CLTV numeric trait, grounded ask, orchestrated erasure — ALL GREEN first run; martech/retraction/privacy green after. Conversational Slack/Teams BOT stays a later arc as planned) · SI-P6 PLANNED (standalone packaging — deployment profile + bridge front door; unblocked, unscheduled) · **Depends on:** the insight service (trait store, social listening/care, audiences), the intelligence LLM seam (`LlmRouter` FAST/SMART tiers, openai-compatible provider), the STT seam (`SpeechController` + mock-whisper), the bridge + connector doctrine (manual ch. 41), the AI audit ledger

**The gap.** The martech module knows what customers **do** (orders, bills, usage → traits →
audiences → attribution). It does not know what customers **say** — reviews, support tickets,
chat logs, call transcripts, CRM notes. The VoC platform category exists to mine exactly that
text into churn risk, pain points, trends, and alerts. A BSS that already owns the operational
truth can do it *better*: the signal lands next to the subscription, the bill, and the churn
outcome label — signal-to-revenue joins a bolt-on listening tool has to reconstruct.

---

## 1. What we found (grounded in the code)

- **The LLM seam is already local-model-ready.** `AI_PROVIDER=openai-compatible` +
  `AI_BASE_URL=http://ollama:11434` is a documented deployment mode (docker-compose comment),
  and `LlmRouter` routes work by tier — the design comment says it outright: *"a local cheap
  endpoint for FAST and a frontier API for SMART can serve the same tenant at once."* So
  "classify PII-adjacent text on a local model, summarize aggregates on a frontier model" is
  CONFIG, not architecture work.
- **The signal sources' nearest kin already live in insight**: `social_mention` (sentiment) and
  `social_dm` (DM → TMF621 ticket) are per-source signal tables with sync endpoints — the
  pattern to generalize, not replace. `party_trait` is where signal-derived facts must land to
  become targetable.
- **Call transcripts have a waiting seam**: `SpeechController` (`/ai/v1/transcribe`) with
  per-tenant speech provider config and mock-whisper.
- **Foreign-system ingestion has two proven doctrines**: the bridge (foreign event shapes →
  our envelope) and the carrier connector family (bind / wire / build, manual ch. 41) — the
  signal connectors follow the same three tiers.
- **The honesty machinery transfers 1:1**: the market-provider arc's *extract-and-copy, never
  model-recall* rule (every emitted fact carries a verified quote from the source) is exactly
  the "traceable to source + hallucination defense" a signal pipeline needs. The AI audit
  ledger already records provider+model per call.
- **Churn already closes its loop**: churn sweep → `churnRisk` trait → retention campaign →
  *outcome recorded as a training label* (martech_test proves it). Signal-derived churn
  indicators plug into an existing target, with existing ground truth.

## 2. Best practice (researched, cited)

- **PII redaction is layered, deterministic-first.** The production doctrine around
  [Microsoft Presidio](https://pasqualepillitteri.it/en/news/5538/microsoft-presidio-pii-data-protection-ai)
  (MIT-licensed; analyzer = NER + regex + context + checksums): start with field minimization
  and deterministic rules, add the NER framework for free text, add a local contextual model
  where names dominate ([layered guidance](https://wavect.io/blog/pii-redaction-before-llm-prompts/),
  [enterprise patterns](https://www.researchgate.net/publication/399570056_Enterprise-Scale_PII_De-Identification_with_Microsoft_Presidio_Anonymizer_Architecture_Use_Cases_and_Best_Practices)).
  Crucial legal nuance: redaction of stored free text is **pseudonymization, not
  anonymization** — the EDPB is explicit that pseudonymized data remains personal data. The
  design must assume GDPR applies to the signal store forever.
- **Small local models are genuinely good enough for the classification battery.** Recent
  benchmarks show an 8B decoder **beating GPT-4o and Claude 3.5 Sonnet by ~10 points** on
  aspect-based sentiment ([arXiv 2601.03940](https://arxiv.org/html/2601.03940v1)), and
  instruction-tuned 7–8B models (Llama 3.1 8B, Mistral 7B) consistently outperforming baseline
  approaches on ticket-style classification
  ([survey](https://arxiv.org/html/2504.01930v1), [ABSA comparison](https://arxiv.org/pdf/2407.02834)).
  Classification is a NARROW task — this is where local wins on quality-per-krone, not just privacy.
- **Legal footing**: mining support conversations runs on **legitimate interest** (service
  improvement / churn prevention) with a documented LIA + DPIA, an Art. 28 DPA for any hosted
  model, and data-subject rights over the signal store
  ([AI+GDPR Norway](https://aikias.no/en/blog/ai-og-gdpr-norge-en),
  [GDPR for AI support](https://www.twig.so/blog/is-ai-customer-support-gdpr-compliant)).
  Nordic DPAs are strict about consent-as-basis and Datatilsynet has AI as a priority
  supervisory area — the local-model option is not just cost, it is the cleanest DPIA story:
  *raw customer text never leaves the deployment*.
- **Topic/trend analytics**: the production-standard shape is embedding clustering with
  c-TF-IDF labeling and `topics_over_time` trends
  ([BERTopic](https://bertopic.org/), [feedback-at-scale write-ups](https://medium.com/@rahulpoonia1997/topic-modelling-for-labelling-large-text-collections-b8c6335db0cd)) —
  local sentence embeddings, so the privacy posture holds through analytics too.

## 3. Decision

**The signal store is a module of insight, not a new platform.** Signals are one entity with
many sources; classification is one battery with one taxonomy; everything traces to source.
Three doctrines locked:

1. **PII firewall at the door.** Redact at ingest (deterministic patterns first — fnr, phone,
   email, card — then NER), store ONLY redacted text + a redaction audit (types/counts, never
   values), link to the party by id (pseudonymous). Raw text is never persisted. The signal
   store is still personal data — RLS, retention windows, erasure by partyId.
2. **Local-first AI tiers.** The per-signal battery runs on the FAST tier → local
   openai-compatible endpoint (Ollama/vLLM): redacted text never leaves the deployment, and
   the task class is where 7–8B models beat frontier models anyway. The SMART/frontier tier
   sees only AGGREGATES (topic rollups, weekly summaries) — never signal text. Both are
   config on the existing seam.
3. **No insight without a receipt.** Every classification stores the quoted evidence span and
   a verify pass confirms the quote exists verbatim in the signal (extract-and-copy). A
   classification whose quote fails verification is dropped, not shown. Every aggregate links
   back to its signals.

## 4. The phases

### SI-P1 — the signal store + PII firewall
- insight `customer_signal` (Flyway + RLS): tenant, `source` (ticket/review/chat/call/crm/…),
  `sourceRef` (traceability), `partyId` (nullable), channel, lang, `text` (REDACTED),
  `context` JSON, `dedupHash` (unique per tenant), receivedAt, `redactions` JSON
  (types+counts). `POST /insight/v1/signal` (machine scope `signal:write`), idempotent by hash.
- Redaction pipeline in insight: deterministic recognizers for NO+EN (fødselsnummer, phone,
  email, IBAN/card, address-ish) + a pluggable NER pass (seam: local model; stub = patterns
  only). Redaction is logged to the AI audit ledger like any model call.
- Internal sources feed themselves: trouble tickets, social mentions/DMs, storefront support
  form → signals via the bus (per the standing events→martech rule, listeners updated).

### SI-P2 — signal connectors (bind / wire / build)
- `signal_connector` per-tenant config (source, baseUrl, secretRef, mode poll|webhook) +
  adapter family in insight — same three tiers as carriers: named adapters, a **generic
  inbound webhook** (safe here: inbound text through the PII firewall, no money), and
  one-class custom adapters. `integrations/mock-servicedesk` (Zendesk-shaped: tickets +
  comments) as the reference mock; the existing mock-social stays the reviews/DM source.
- Call transcripts: a `call` source posting transcripts through the same door; real telephony
  = STT seam (already built) + this connector. Demo: mock transcripts with planted PII to
  PROVE the firewall on camera.

### SI-P3 — the classification battery (FAST tier, local-capable)
- Per signal: sentiment, aspect (product/billing/network/support/price), type
  (fault | feature-request | question | praise | complaint), painPoint {summary, impact 1–5},
  loyaltyIndicator, churnSignal (bool + reason), journeyStage. One
  `signal_classification` row per signal, each field with its **evidence quote**, verified
  verbatim against the redacted text (fail → dropped). Aspect taxonomy is per-tenant config.
- Outputs join the fleet: `churnSignal` feeds the churn model's inputs (labels already
  exist); `SignalClassifiedEvent` on `bss.insight.events` (martech listeners updated per the
  standing rule); high-impact fault clusters can open TMF621 tickets (social-care pattern).

### SI-P4 — Voice-of-Customer analytics + early warning + CLTV
- Console Growth → **Voice of Customer** pane: mood over time, top aspects/topics with
  volume + sentiment trend (week/month), each row drilling to its source signals.
- v1 trends: aggregate the battery per aspect×week (SQL) + FAST-tier labeled rollups; v2:
  local embeddings + clustering (BERTopic shape) once volume justifies it — SAY which one is
  running.
- Early warning: deviation alerts (aspect volume or sentiment shifts beyond a band) →
  bus event → operator inbox/journey trigger — the OCS "running low" alert pattern.
- **CLTV** in intelligence from data a listening tool never has: billing history + usage +
  margin + churn probability → `cltv` trait (audience-targetable, CSR 360 visible, risk-pane
  input). Formula visible in the UI — no black-box number.

### SI-P5 — ask & act surfaces
- Growth copilot extended over VoC aggregates ("what are fibre customers complaining about
  since the price change?") — answers cite signal ids, console-first.
- Slack/Teams: a thin outbound webhook connector for ALERTS first (config, Tier-2-safe);
  a conversational bot in Slack/Teams is a separate later arc (real workspace app + auth).
- Suite `signal_intelligence_test.js`: ingest with planted PII → stored text is clean +
  redaction audited; dedup; classification with verified quotes (a planted
  hallucination-bait signal proves the drop path); trend row appears; alert fires; erasure by
  partyId empties the store.

### SI-P6 — standalone packaging (sell it without the BSS)
- **The add-on cluster as a deployment profile**: a compose/Helm subset that stands up only
  insight + intelligence + campaign + communication (+ gateway slice, keycloak, kafka,
  postgres) with the bridge as the documented front door — the same cluster the martech
  add-on thesis already proves, now carrying the inbound half too. Mostly labeling work: the
  services are already independent.
- **Three buyer fits, in order of strength**: (1) another telco BSS — bridge maps its events,
  signal connectors need no BSS at all; (2) non-telco subscription businesses (energy, ISP,
  insurance, banking, SaaS) — the per-tenant aspect taxonomy carries the domain, and the
  differentiators are the signal-to-revenue join (honest CLTV + churn-labeled signals from
  THEIR billing via the bridge) and the local-model/PII-firewall posture ("customer
  conversations never leave your infrastructure" — the procurement-winning line in regulated
  industries); (3) pure generic VoC against entrenched incumbents — weakest fit, enter only
  on the self-hosted angle.
- **What the buyer must feed it**: customer identity + billing/contract events over the
  bridge (for joins, CLTV, churn labels) — or nothing, in which case it degrades honestly to
  listening-only (signals, classification, VoC trends, alerts; no revenue joins, and the UI
  says so).
- Out of product scope, named anyway: pricing/licensing and SOC 2-type attestation are
  company work, not code.

## 5. Honesty box

- **Pseudonymized ≠ anonymized.** The signal store remains personal data (EDPB); retention
  windows, RLS, and partyId erasure are in SI-P1, but the LIA/DPIA is per-operator paperwork
  the product cannot ship for them.
- **The local-model quality claim must be re-proven on OUR taxonomy — and the first eval
  says: not yet at 3B.** Experiment 2026-08-19 (harness: copy the Claude-classified reference
  set under a throwaway party → flip FAST to a local model → sweep → compare → erase):
  **qwen2.5:3b via host Ollama went 0/13** — it systematically echoed labels into the evidence
  fields instead of verbatim quotes, and the double verification refused every row. The
  evidence contract proved itself a real quality gate, not decoration. Claude (fallback tier):
  13/13 with verbatim quotes. Local latency ~1–4 s/call was fine; the failure is instruction-
  following, not speed. ROUND 2 (same day): the hardened prompt
  (worked copy-these-words example, "never put a label in evidence") + **qwen2.5:7b: 5/13
  accepted** (sentiment 100% agreement where accepted) — but 8/13 still failed the evidence
  gate, and the 7B **over-flags churnSignal on ordinary complaints** (bill-wrong ≠ leaving),
  which would poison the churn trait. VERDICT: the local tier stays FRONTIER until a model
  passes this harness; the gate refused two weaker models exactly as designed. NEXT
  (unscheduled): llama3.1:8b / a churn-negative example in the prompt / fine-tune. The flip is
  four env vars (AI_*_FAST) after 524bbc9 fixed the silently-ignored tenant tier keys and the
  tier-blind ledger labels the experiment exposed; the harness (copy → flip → sweep → compare →
  erase) is repeatable in one command.
- The demo ships with mocks (mock-servicedesk, planted transcripts) and the stub/pattern-only
  redactor as the floor; real NER redaction and a real local model are config on existing
  seams (`AI_PROVIDER=openai-compatible`, redactor seam) — same claim discipline as carriers.
- v1 topics are battery-aggregates, not embedding clustering — the pane must label which.
- A Slack/Teams *bot* (not webhook alerts) is out of scope until a real workspace app exists.
