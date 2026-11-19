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
2. The best arm is compared with the runner-up. The tuner works out the **odds
   that the difference is real** — how much better the numbers fit "these arms
   differ" than "they do not". When the odds reach the bar (20 to 1 for two
   arms), the best arm takes `100 − floor × (arms − 1)` percent and every other
   arm keeps the floor (`journey-tune-floor-percent`, default 10). Decision
   `shift`. The bar holds **however often the tuner looks**: the chance that it
   ever shifts traffic to an arm that is no better stays under
   `journey-tune-alpha` (default 5 %). With more arms the bar rises — 60 to 1
   for three — because the best and the runner-up are picked after the fact.
3. Otherwise nothing moves: decision `hold`, with the odds it saw.
4. A new weight moves **new** enrolments only; a customer keeps the arm they
   were dealt, so per-arm rates stay clean.
5. The ledger (`tuningLog`, last 30) records at / arms / before / after / odds / bar / why.
6. The rule runs as the `always-valid-tuner` policy behind the DecisionPoint
   `journey.armWeights`; every judgement is also a decision-log row and the
   ledger entry carries its `decisionId` (`docs/decision-log.md`).

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

`ops/e2e/journey_autotune_test.js` (#117): waiting on thin data; 240 enrolments
dealt across arms and holdout; the tick speaks in the arm's subject; conversions
per arm; a shift to 90/10 with the odds above the bar and a ledger entry; new traffic
follows the new weights while old enrolments keep theirs; a second judgement
holds; another tenant cannot see the journey.

## Why the rule changed (November 2026)

The first rule was a one-sided z-test at 1.64, "about 95 %". That number is
true for a test read **once**. The tuner reads every ten minutes, and each
read is another chance for luck to clear the bar. Measured, not argued
(`ExperimentStatisticsTest`, 2 000 simulated journeys whose two arms were
identical, read 99 times each): the old rule crowned a winner in **58 %** of
them; the rule above in **2 %**. A real difference (18 % against 10 %) is
still found in 98 % of journeys within a thousand customers per arm.

Two more things were wrong, and are fixed with it:

- **The split was not random across experiments.** A customer's bucket came
  from `String.hashCode()` of the experiment and customer ids joined together,
  which is a sum: every experiment cut the base along the same lines, only
  shifted. Five thousand customers in two experiments used 100 of the 10 000
  possible bucket pairs; an independent split uses about 3 900. The draw is now
  SHA-256 over (experiment, purpose, customer) — policy version 2. A customer
  is dealt once per experiment and the deal is stored, so nobody moved.
- **A lift had no range.** `GET …/stats` on a journey or a campaign now carries
  `liftInterval: {low, high}` in points beside `liftPoints`, valid whenever
  the page is opened, and `note` says so in words while the range still spans
  zero. A campaign's A/B `verdict` uses the same test instead of a z-test at
  1.96.

Tuning: `journey-tune-alpha` (0.05) is the chance of ever crowning luck;
`journey-tune-effect-sd` (0.10) is how large a real difference is expected to
be. `journey-tune-z` is gone and is ignored if set.

## Not in this slice

Multi-step variants (arms on every message), Thompson sampling instead of the
threshold rule, and tuning of send time. Each is a change to the rule paragraph
above, not to the desk.

## Honest limits

- The test is the normal-approximation mixture test, not an exact binomial
  one. It is checked by simulation at 2 %, 10 % and 50 % conversion and held
  at each; below the 20-per-arm minimum it is not consulted at all.
- Honesty costs speed. Small differences at low conversion rates (2 % against
  3 %) need thousands of customers per arm before the odds reach the bar; the
  old rule "found" them sooner because it was mostly finding luck.
- After a shift the split is 90/10, so the losing arm learns slowly. The test
  stays valid; a wrongly demoted arm takes a long time to win its traffic back.
- The simulations deal both arms evenly. A split that moves because of the
  data it has seen is outside what they prove.
- `liftInterval` covers the conversion-rate lift only. Revenue lift per
  customer still has no range.
- Journeys judged before this change keep their old ledger rows, read back as
  written (`z` against `threshold`); those shifts were made by the old rule
  and were not re-judged.
- Experiments running across the change hold customers dealt by both draws.
  Each was dealt once and at random with respect to their outcome, so the
  comparison stands; the two draws are not the same split.
