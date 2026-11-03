# AI content marking

*Issue #422. EU AI Act Article 50(2): text a model generates must be marked as
such in a machine-readable way. For systems already running, that applies from
2 December 2026.*

## What changed, in one paragraph

Before this, the AI layer knew which text a model had written — every draft
from the copy assistant comes back with the provider and model — but the
knowledge was dropped the moment a marketer pressed Create. The campaign
stored plain text, the customer's message carried nothing, and the shop showed
a sparkle and nothing else. Now the mark is kept where the text is kept, and it
travels with the text to the customer.

A review of that first version found the mark was only what the request said:
the Growth copilot's Create sent model-written copy with no mark at all, an API
caller could send `aiGenerated: false`, an A/B arm that replaced only the
subject dropped the mark on the model's body, and the step builder lost it on a
branch's copy. So the mark is now decided **on the server**: every draft leaves
intelligence with a signed receipt, and the campaign component marks the copy
from the receipt itself.

## Where the mark lives

| Where | What a person sees | What a machine reads |
|---|---|---|
| **Campaign desk** (Marketing → Campaigns) | Press **✨ Draft** and the *AI-written* field fills with the model that wrote the copy (for example `anthropic/claude-sonnet-4`). Edit the subject and message as much as you like: the campaign stays marked as AI-assisted. Clearing the field does not unmark drafted copy — the draft's receipt travels with the form, and the server marks by the receipt. | The console sends the receipt as `message.aiDraft`; the campaign stores `aiGenerated: true` and the `aiModel` the receipt names. A message typed by hand carries neither key, exactly as before. |
| **Journeys** (Marketing → Journeys) | Describe the journey and press **✨ Draft**: every message step the copilot wrote arrives marked. The step builder keeps the mark and the receipt through edits — on a message step, and on a decision's *then*/*else* and a wait's *on timeout* copy. | Each drafted message step carries `aiGenerated: true`, `aiModel` and `aiDraft`; the campaign component re-checks the receipt on every save and sets the mark itself. A step a person adds is unmarked. |
| **Growth copilot** (Marketing → Growth) | The proposal card says *AI-written by &lt;model&gt;*; **Create** posts the proposal as it came. | The copilot's answer arrives with every piece of copy marked and carrying its receipt — a campaign's message; a journey's message steps, *on timeout* and *then*/*else* copy — so Create sends marked copy without the console deciding anything. |
| **The message a customer receives** (in-app inbox, email, SMS, push) | Nothing changes in the subject or the body. | The TMF681 `communicationMessage` carries two characteristics beside the standard payload: `{name: aiGenerated, value: true}` and `{name: aiModel, value: "<provider>/<model>"}`. Communication stores them with the message and answers them back on every read — the customer's own inbox, the staff view and the `CommunicationMessageCreateEvent`. |
| **The shop** (the *Recommended for you* rail) | The caption a model writes over the rail ends with *· AI-written* (bokmål *Skrevet av KI*, nynorsk *Skrive av KI*). | The caption's element carries `data-ai-generated="true"`. |
| **The app** (the *For you* card) | The same caption ends with *· AI-written*. | A screen reader hears "AI-written: …"; the web build carries `data-ai-generated="true"`. |

**A/B arms follow the text that is sent.** A journey arm that replaces all of
a step's copy (subject and message, or a template) speaks with its own
provenance: typed by a person, it is unmarked. An arm that replaces only part
of it — a new subject over the model's message — still sends the model's
words, so the send stays marked with the step's model. An arm whose own copy
carries a mark or a receipt is marked. A campaign's arms (`messageVariants`)
are typed into a box by a person and are not marked.

## How it flows

1. The console asks the copy assistant (`/ai/v1/campaignCopy`), the journey
   drafter (`/ai/v1/journeyDraft`) or the Growth copilot
   (`/ai/v1/journeyCopilot`). Every answer names `provider` and `model` — the
   ones that actually wrote the copy, as the AI governor served the call (the
   FAST tier, or the tenant's fallback when the first choice was down; a journey
   whose stages were written by different models names each one) — and
   carries a **receipt**, issued for that served model: `provenance` on campaign copy; `aiGenerated`,
   `aiModel` and `aiDraft` on every piece of journey and proposal copy.
2. The receipt is `aid1.<payload>.<signature>`. The payload is
   `{tenant, model, draft, issuedAt}`; the signature is an HMAC-SHA256 under the
   operator's provenance key (`AI_PROVENANCE_KEY`, property
   `bss.ai.provenance-key`), which intelligence and campaign share.
3. The console puts the copy in the form and keeps the receipt with it (a
   hidden field on the campaign form; inside each step for a journey). On
   Create it sends `message: {subject, content, aiGenerated, aiModel, aiDraft}`
   or the steps as they are.
4. The campaign component checks every receipt it is given — the signature, and
   that it was issued to this operator — and then sets `aiGenerated: true` and
   the receipt's model itself, whatever the body says. A receipt that does not
   check out (another key, another operator, an edited payload, junk) is
   refused with a 400. Copy with no receipt keeps whatever the body declared;
   copy with neither is human.
5. The campaign component stores the mark (`campaign.ai_generated`,
   `campaign.ai_model`, migration V30). A journey stores it inside its steps
   document, receipt included, as it stores everything else about a step, and
   re-checks it on every edit. A stored receipt marks the send even if the
   flag beside it was later cleared.
6. When a customer is reached, campaign hands communication the copy and,
   for marked copy, the two characteristics. The standard fields — subject,
   content, messageType, relatedParty — are untouched.
7. Communication keeps the mark with the message
   (`communication_message.ai_generated`, `ai_model`, migration V14) and
   answers it back on every read and in the create event.

## How it is proved

- `services/intelligence` — `AiDraftProvenanceTest`: the receipt's format is
  pinned to a vector computed outside Java (Python's `hmac`), the same string
  campaign's test verifies; campaign copy comes with a receipt for this tenant
  and model; every drafted journey message is marked and a wait is not; the
  Growth copilot's campaign proposal arrives marked, and a journey proposal's
  message, *on timeout* and *then*/*else* copy are all marked; with no key no
  receipt is issued, but the mark still travels.
- `services/campaign` — `AiProvenanceTest`: a receipt from this operator marks
  the copy whatever the body says; another tenant's, another key's, an edited
  payload and junk are refused; with no receipt the declaration stands; with
  no key a receipt still marks; stamping marks a journey's branch and timeout
  copy; a subject-only arm over a model's step stays marked, a whole-copy human
  arm does not. `CampaignApiTest.aDraftReceiptMarksTheCampaignAndTheBodyCannotClearIt`
  and `aJourneysDraftedCopyIsMarkedOnTheServerAndStaysMarkedThroughAnEdit`
  prove it through the API: `aiGenerated: false` beside a receipt is stored as
  marked, an edit keeps the mark, a forged receipt is a 400.
- `services/campaign` — `CampaignApiTest.aiWrittenMessageIsMarkedOnTheWire`
  (the mark is stored and answered back; hand-typed copy shows none),
  `CampaignEngineTest.aiWrittenCopyKeepsItsMarkAllTheWayToTheSend` and
  `humanCopyGoesOutUnmarked`, `AiContentMarkingTest` (the exact body handed to
  communication: the two characteristics after any existing one, standard
  fields unchanged; a marked journey step and an unmarked one).
- `services/communication` — `CommunicationApiTest.aiMarkedCopyIsStoredAndAnsweredBack`
  (the mark survives the send and the customer's own inbox read; a hand-typed
  message has no characteristic block). Writing it caught a real trap: the inbox
  lists by Query-by-Example, so a primitive `false` on the entity would have
  filtered every AI-written message out of every inbox. The column is TRUE or
  empty for that reason.
- `services/intelligence` — `ServedModelMarkerTest`: with FAST and SMART on
  different providers, campaign copy, a journey draft and a care-desk ticket
  reply name the FAST model that wrote them, and copy the fallback wrote names
  the fallback. The copy and journey tests failed on the old code.
- The step builder was proved in a real browser: the console's own
  `step-builder.js` loaded into Chromium, AI-marked steps in, the steps it
  would post read back. Before the fix the receipt and the branch marks were
  dropped; after it every mark and receipt survives and a person's step stays
  unmarked. That script is not one of the numbered suites.
- `ops/e2e/journey_copilot_console_test.js` now also asserts that every message
  step of the journey the Growth copilot created is marked, and that the card
  says *AI-written*.
- `ops/e2e/for_you_test.js` now also asserts the shop caption carries
  `data-ai-generated="true"` and the label, and the app caption carries the label.
- The shop's Norwegian: `ops/arch/choices_i18n_check.py` (run by the claims
  gate) now covers the care chat and the *AI-written* label beside the Choices
  and credit screens, and fails on a key with no nynorsk entry of its own — the
  nynorsk bundle is built over bokmål, so a missing key read bokmål, not
  nynorsk. The chat's own strings had no Norwegian at all and now have both.

## Honest limits

- **The receipt proves a draft, not the words.** It says this operator's
  intelligence component drafted copy with that model. It does not bind the
  text, because a marketer edits the draft and edited copy is still
  AI-assisted. So copy whose every word was rewritten still goes out marked —
  an over-mark, not a missing one — and a receipt copied onto other copy marks
  that copy too, in the same safe direction.
- **Copy without a receipt is trusted as declared.** Text pasted in from
  outside the system — another model's output, a chatbot in a browser tab —
  carries no receipt and cannot be detected; it is marked only if the caller
  says `aiGenerated` or names a model. An API or MCP caller that drops both the
  receipt and the flag sends unmarked copy. The server stops a caller from
  *clearing* the mark on copy that came from a draft; it cannot stop one from
  never asking for a draft.
- **One shared key, and changing it has a cost.** Intelligence signs and
  campaign verifies with the same `AI_PROVENANCE_KEY`. The laptop fleet
  (`docker-compose.yml`) and the chart ship a dev value; a deployment sets its
  own, in both. The application default is empty: intelligence then issues no
  receipts, and campaign marks a receipt it cannot check (with the declared
  model) rather than refuse it. After the key changes, saving a journey whose
  steps still carry receipts signed under the old key is refused with a 400
  until those steps are re-drafted or their receipts removed — and removing a
  receipt removes the mark unless the flag stays.
- **The *AI-written* field is still a form field.** It shows the model, and on
  copy without a receipt it is the declaration itself, so a desk preset that
  learned it could pre-fill it on a hand-written campaign — an over-mark. The
  receipt itself cannot become a preset: each draft's is unique.
- **The shop's Home page is not marked, on purpose.** The lead pick's
  *Why this?* line on Home comes from the recommendation engine's rules (or a
  fixed sentence), not from a model, so labelling it *AI-written* would be a
  false mark. It still shows a ✨; whether that sparkle should go is a design
  question, not a compliance one. The model-written caption is the one on the
  shop's *Recommended for you* shelf, and that is what carries the label.
- **Product descriptions drafted by the product copilot are not marked yet.**
  They reach the catalog as plain text; the same pattern (a receipt beside the
  text, checked by the component that stores it) is the next step.
- **Staff-only drafts keep their existing wording.** The CSR desk's copilot
  card and wrap-up already say *Drafted by <provider> (<model>) — verify before
  acting* / *— edit before logging*; they are not customer-facing until a person
  acts on them, and they are not marked further here.
- **Three SMART-tier copilots still name the configured model.** The journey
  copilot, the product copilot and next-best-offer report the tenant's SMART
  configuration rather than the route that served; that is the right model
  unless the fallback answered, in which case they name the one that was down.
  Campaign copy, journey drafts, the care desk's drafts, knowledge answers and
  signal classification name the served model.
- **Templated sends are not marked.** A message rendered from a message
  template is the template author's copy; if a template was itself drafted by
  a model, nothing records that.
- **The mark rides beside the message, not inside it.** An email's body and
  headers carry no AI marker, and the ESP, SMS and push forwarders do not pass
  the characteristics on. A recipient's mail client cannot see the mark; the
  BSS, its events and its APIs can.
- **The app has no translation layer.** Its label is English, as is the rest
  of the app.
- **A journey edited as raw JSON** keeps the mark as long as the author keeps
  `aiDraft` (or the flag); deleting both deletes the mark.
- The browser suites were extended but not run for this change (no fleet was
  started); the service tests were. The review's fixes added no migration: the
  receipt lives in the journey's steps document, and a campaign stores the
  mark in the columns it already had.
