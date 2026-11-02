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

## Where the mark lives

| Where | What a person sees | What a machine reads |
|---|---|---|
| **Campaign desk** (Marketing → Campaigns) | Press **✨ Draft** and the *AI-written* field fills with the model that wrote the copy (for example `anthropic/claude-sonnet-4`). Edit the subject and message as much as you like: the campaign stays marked as AI-assisted. Clear the field only if you rewrote the message yourself. | The campaign's `message` carries `aiGenerated: true` and `aiModel`. A message typed by hand carries neither key, exactly as before. |
| **Journeys** (Marketing → Journeys) | Describe the journey and press **✨ Draft**: every message step the copilot wrote is marked. The step builder keeps the mark through edits. | Each drafted message step carries `aiGenerated: true` and `aiModel`. A step a person adds is unmarked. |
| **The message a customer receives** (in-app inbox, email, SMS, push) | Nothing changes in the subject or the body. | The TMF681 `communicationMessage` carries two characteristics beside the standard payload: `{name: aiGenerated, value: true}` and `{name: aiModel, value: "<provider>/<model>"}`. Communication stores them with the message and answers them back on every read — the customer's own inbox, the staff view and the `CommunicationMessageCreateEvent`. |
| **The shop** (the *Recommended for you* rail) | The caption a model writes over the rail ends with *· AI-written* (bokmål *Skrevet av KI*, nynorsk *Skrive av KI*). | The caption's element carries `data-ai-generated="true"`. |
| **The app** (the *For you* card) | The same caption ends with *· AI-written*. | A screen reader hears "AI-written: …"; the web build carries `data-ai-generated="true"`. |

A send from an A/B arm is not marked: arm copy is typed into the arms box by a
person. If an arm's own copy declares `aiGenerated`, that declaration is used
instead of the step's.

## How it flows

1. The console asks the copy assistant (`/ai/v1/campaignCopy`) or the journey
   copilot (`/ai/v1/journeyDraft`). Both answers name `provider` and `model`.
2. The console puts the copy in the form and the model name beside it. On
   Create it sends `message: {subject, content, aiGenerated: true, aiModel}`
   (campaign) or the marked steps (journey).
3. The campaign component stores the mark (`campaign.ai_generated`,
   `campaign.ai_model`, migration V30). A journey stores it inside its steps
   document, as it stores everything else about a step.
4. When a customer is reached, campaign hands communication the copy and,
   for marked copy, the two characteristics. The standard fields — subject,
   content, messageType, relatedParty — are untouched.
5. Communication keeps the mark with the message
   (`communication_message.ai_generated`, `ai_model`, migration V14) and
   answers it back on every read and in the create event.

## How it is proved

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
- `ops/e2e/for_you_test.js` now also asserts the shop caption carries
  `data-ai-generated="true"` and the label, and the app caption carries the label.

## Honest limits

- **The shop's Home page is not marked, on purpose.** The lead pick's
  *Why this?* line on Home comes from the recommendation engine's rules (or a
  fixed sentence), not from a model, so labelling it *AI-written* would be a
  false mark. It still shows a ✨; whether that sparkle should go is a design
  question, not a compliance one. The model-written caption is the one on the
  shop's *Recommended for you* shelf, and that is what carries the label.
- **Product descriptions drafted by the product copilot are not marked yet.**
  They reach the catalog as plain text; the same pattern (a flag beside the
  text, carried to the channels) is the next step.
- **Staff-only drafts keep their existing wording.** The CSR desk's copilot
  card and wrap-up already say *Drafted by <provider> (<model>) — verify before
  acting* / *— edit before logging*; they are not customer-facing until a person
  acts on them, and they are not marked further here.
- **Templated sends are not marked.** A message rendered from a message
  template is the template author's copy; if a template was itself drafted by
  a model, nothing records that.
- **The mark rides beside the message, not inside it.** An email's body and
  headers carry no AI marker, and the ESP, SMS and push forwarders do not pass
  the characteristics on. A recipient's mail client cannot see the mark; the
  BSS, its events and its APIs can.
- **The marketer can clear the mark.** The *AI-written* field on the campaign
  form is editable so that someone who rewrote the copy can say so. Nothing
  checks how much of the text was rewritten. It is an ordinary form field, so
  a desk preset that learned it could pre-fill it on a hand-written campaign —
  an over-mark, not a missing one.
- **The app has no translation layer.** Its label is English, as is the rest
  of the app.
- **A journey edited as raw JSON** keeps the mark only if the author keeps the
  two keys.
- The browser suite was extended but not run for this change (no fleet was
  started); the service tests, including both components' Postgres migration
  tests against a real Postgres, were.
