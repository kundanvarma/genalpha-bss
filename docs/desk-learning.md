# Desk learning — the BSS as its own customer

**What it is.** The desks (back-office console, CSR console) report what staff
*do* — a tab opened, a form started, a field focused, a form submitted or
abandoned, a search that found nothing, a copilot draft rewritten — never what
they see, never a customer. Insight turns a week of that into a **friction
report** and into **suggestions** with evidence. Some have a one-click fix; the
rest are for the people who build the product.

**Why.** A platform that measures its own use can improve where the work actually
happens. The honesty rule applies to itself: no "users want X" without the
evidence, and every automatic change is small, reversible and logged.

## What the desk records

| Event | When | Props kept |
|---|---|---|
| `desk.tabs` | at load | the tab list (to find never-opened features) |
| `tab.open` | a tab is opened | |
| `form.start` / `form.field` | first focus in a form / each field focused | field name |
| `form.submit` | a form is saved | the *values shape*: short enumerable fields kept (category, price type, channel…); free text (name, description, content…) as presence only |
| `form.abandon` | a started form is left for another tab | |
| `search.empty` | a page filter or customer search finds nothing | the query (≤ 60 chars) |
| `copilot.draft` | a copilot draft was applied and then saved | edit ratio (0 = kept, 1 = rewritten) |
| `preset.use`, `suggestion.accept/dismiss` | | |

The staff member is stored as a salted hash of their identity; no name, no
email. Sessions are a random id per browser tab. Everything is per tenant under
row-level security. The tenant switches it on with `desk-learning: true` in
`tenants.yml`; off, the desks post once, get `enabled: false`, and stay quiet.

## What comes out

`GET /insight/v1/desk/friction?days=7` — counts: actions, people, abandoned
forms (with the field people stop at), repeated same-shaped submissions, empty
searches, copilot rewrites, features never opened, journeys created without a
holdout.

`GET /insight/v1/desk/suggestions` — each with `title`, `evidence`, `audience`
(`operator` | `vendor`) and, where safe, an `action`:

| Kind | Trigger | Action |
|---|---|---|
| preset | the same form submitted 3+ times by one person with the same values | **Save preset** → the values appear as a chip above that form; one click prefills |
| holdout | a journey created with 0 % holdout | **Apply** → the desk PATCHes the journey to 10 % *with the signed-in user's own token* (insight holds no cross-service credential) |
| abandon | 2+ abandoned starts of a form | evidence for the product team, with the stop field |
| search | the same empty query 2+ times | add an article, an alias or a filter |
| rewrite | 2+ copilot drafts changed by more than half | the prompt or its defaults are off for this tenant |
| unused | tabs never opened this week | hide them or explain them |

Accepted and dismissed suggestions are remembered for 30 days.

## For the vendor, when the tenant is not ours to read

An operator that owns and runs the platform is not a managed tenant; we cannot
read its desks, and should not. `GET /insight/v1/desk/export` (and the **Copy the
anonymised report** button on the Suggestions tab) produces **counts only** — no
names, no values, no hashes, no tenant name — which the operator can hand to us
with a support conversation or a feature request. The suite asserts that the
export carries none of the source values. Automatic sharing to a vendor endpoint
is deliberately not built: sharing is a decision the operator takes each time.

## Where it lives

- insight: `com.bss.insight.desk` (service, controller), entities `DeskEvent`,
  `DeskPreset`, `DeskDecision`, migration V36/V37.
- console: telemetry helper (`desk()`), presets chips on every create form, the
  **Suggestions** tab under AI & Automation.
- CSR: `src/desk.js`; `search.empty` and `tab.open`.
- Proof: `ops/e2e/desk_learning_test.js` (#116).

## Not in this slice

Journey auto-tuning (variants with a holdout, traffic shifted to the winner),
offer proposals through the commercial simulator, and the code loop (an agent
turns an evidence-backed backlog item into a pull request that the suites must
pass). Each is a later slice on the same event stream.
