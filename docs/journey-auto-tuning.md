# Journey auto-tuning — the system learns which message works, and shows its evidence

**What it is.** A journey's first message can carry **arms**: message variants
(`[{name, subject, content}]`). Every treated customer is dealt an arm by the
journey's current **traffic weights**; the holdout gets nothing, as before.
Conversions read per arm. With **auto-tune** on, a tuner judges the arms on a
clock and shifts traffic to the winner — only when the evidence clears a
threshold, never below a floor per arm, and every judgement is a ledger entry.

**Why it is honest.** The holdout still measures lift against silence; the arms
measure which words. The rule is one paragraph and every decision carries the
numbers that produced it, so a marketer can disagree with it.

## The rule

1. An arm is judged only after `journey-tune-min-per-arm` treated enrolments
   (default 20). Until then the decision is `waiting`.
2. The best arm is compared with the runner-up by conversion rate, one-sided
   two-proportion z. If `z ≥ journey-tune-z` (default 1.64, about 95 %), the best
   arm takes `100 − floor × (arms − 1)` percent and every other arm keeps the
   floor (`journey-tune-floor-percent`, default 10). Decision `shift`.
3. Otherwise nothing moves: decision `hold`, with the z it saw.
4. A new weight moves **new** enrolments only; a customer keeps the arm they
   were dealt, so per-arm rates stay clean.
5. The ledger (`tuningLog`, last 30) records at / arms / before / after / z / why.

The clock runs every `journey-tune-ms` (default 10 min) over every active
auto-tune journey of every tenant; `POST …/journey/{id}/tune` runs it now.

## API

| Call | What |
|---|---|
| `POST /journey` with `arms`, `autoTune` | create with variants; weights start equal |
| `PATCH /journey/{id}` `arms` / `autoTune` | change variants (weights reset to equal) |
| `POST /journey/{id}/enrollments {partyIds, context?}` | enrol a list by hand; returns `dealt: {partyId: arm | holdout}` |
| `POST /journey/{id}/conversion {partyId, value?}` | record a conversion that did not arrive as an event (a store sale, a call-centre close) |
| `POST /journey/{id}/tune` | judge now |
| `GET /journey/{id}/stats` | `arms: [{name, weight, enrolled, converted, rate, revenue}]`, `autoTune`, `tuningLog` |

## Console

Journeys form: **Message variants (A/B arms)** and **Auto-tune**. The View row
shows each arm's weight, sends, conversions and rate, and the last decision in
plain words.

## Proof

`ops/e2e/journey_autotune_test.js` (#117): waiting on thin data; 120 enrolments
dealt across arms and holdout; the tick speaks in the arm's subject; conversions
per arm; a shift to 90/10 with z above threshold and a ledger entry; new traffic
follows the new weights while old enrolments keep theirs; a second judgement
holds; another tenant cannot see the journey.

## Not in this slice

Multi-step variants (arms on every message), Thompson sampling instead of the
threshold rule, and tuning of send time. Each is a change to the rule paragraph
above, not to the desk.
