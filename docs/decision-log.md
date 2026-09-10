# Decision log and DecisionPoint seam — the BSS remembers why it chose

**What it is.** Every adaptive choice the BSS makes — which customer lands in a
holdout, which message variant they are dealt, which of two journeys speaks
this tick, whether the tuner shifts traffic, what the product advisor proposes,
what a desk is nudged to change — now goes through one **DecisionPoint seam**
and lands as one row in insight's **decision log**: the context the policy
saw, the actions that were eligible, the one it chose, the policy and its
version, the probability with which it chose (the *propensity*), one sentence
of reason, and — when it arrives — the outcome that followed, joined by id.

**Why.** Learning needs more than clicks; it needs the choice that was
available at the time. With the eligible set and the propensity on record,
a future policy can be judged on yesterday's decisions before a single
customer meets it (off-policy evaluation), and an auditor can read why any
customer got what they got. This is phases 1 and 2 of the continuous-learning
architecture: the log and the seam. Experimentation stays where it was — the
holdout still measures lift — and the log never becomes its own judge.

## The seam (`services/campaign/.../decision`)

```
DecisionPoints.decide(point, subjectId, context, candidates, constraints, fallback)
   → constraints remove actions first (and are named in the record)
   → the point's registered DecisionPolicy picks among what is left
   → a fallback answers when nothing is eligible or the policy fails
   → DecisionRecordedEvent {decisionId, …} on the service's own topic
DecisionPoints.outcome(decisionId, "conversion", value)
   → DecisionOutcomeEvent, joined in the log by id
```

`DecisionPolicy` is the one contract every algorithm implements: a name, a
version, `decide(request) → Decision{action, propensity, reason, evidence}`.
Rules, hashed splits and threshold tuners implement it today; a bandit or a
learned policy implements the same interface tomorrow, and the process that
calls the point does not change.

| DecisionPoint | Subject | Policy today | Autonomy | Outcome |
|---|---|---|---|---|
| `journey.enrolment` | party | `holdout-then-weighted-hash` v1 — holdout by hash, then the arm by the current weights; propensity = holdout % or (1 − holdout %) × weight | high | `conversion` |
| `campaign.treatment` | party | same policy, uniform arms | high | `conversion` |
| `journey.nextBestAction` | party | `priority-first` v1 — the higher-priority journey speaks (deterministic) | medium | — |
| `journey.armWeights` | journey | `z-threshold-tuner` v1 — waiting / hold / shift, the rule from `journey-auto-tuning.md` | medium | — |
| `catalog.advisorProposal` | offering | `advisor-arithmetic` v1 (intelligence) | medium | `adopted` |
| `desk.suggestion` | desk target | `desk-rules` v1 (insight) | medium | `accepted` / `dismissed` |

The hashes are the ones every existing enrolment was dealt by, so a customer
keeps their bucket; rows from before the log have no decision id and no
outcome is invented for them. Autonomy classes are declared per point:
**high** for reversible message choices, **medium** for recommendations and
traffic shifts, **low** (none in this slice) for money, rights or statute.

## The log (`services/insight`, table `decision_log`)

Insight consumes `DecisionRecordedEvent` / `DecisionOutcomeEvent` from
`bss.insight.decision-topics` (default `bss.campaign.events,bss.intelligence.events`;
its own desk decisions are written directly). Idempotent by decision id — the
bus is at-least-once. RLS per tenant like every insight table.

What a record holds: identifiers, the eligible set, numbers and one sentence.
Never a name, an address or message text — the context is what a policy may
look at, and the whole request is written down.

| Call | What |
|---|---|
| `GET /insight/v1/decisions?decisionPoint=&subjectId=&limit=` | newest first, filtered |
| `GET /insight/v1/decisions/{id}` | the **receipt**: the record plus the sentences an auditor reads first (context used, eligible actions, chosen by which policy with what probability, why, autonomy, outcome) |
| `GET /insight/v1/decisions/summary` | per point and policy: decisions, with propensity, with outcome, fallbacks, outcome rate |
| `GET /tmf-api/campaignManagement/v4/decisionPoint` | the registry: every point, its policy and version, its autonomy class |

Every journey enrolment and campaign execution keeps its `decision_id`
(campaign migration V26); the tuner's ledger entries and NBA arbitration
records carry theirs; an advisor finding and the proposal it hands to
`/adopt` carry theirs.

## Proof

`ops/e2e/decision_log_test.js` (#121): the registry; sixty enrolments logged
one decision each with the eligible set, policy, version and a propensity that
equals (1 − holdout) × weight; the holdout decision at holdout %; a conversion
joins back by id and the receipt reads it; the tuner's judgement is a
deterministic decision with rows and evidence; the summary's propensity
coverage; no message text in the log; the ENet tenant reads nothing.
Unit: `DecisionPointsTest` — determinism against the historical hashes,
constraints before policy, fallback on failure, the tuner rule.

## Not in this slice

The Decision Receipt page in the console (phase 3), Learning Contracts as
tenant configuration (phase 4), a contextual bandit behind the same seam
(phase 5), and replay / off-policy evaluation over the log (phase 6).
