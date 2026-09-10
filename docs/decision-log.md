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

## Learning Contracts (phase 4) — intent as configuration

One contract per DecisionPoint per tenant, stored with the seam that enforces
it (campaign, table `learning_contract`, V27/V28) and edited in the console
under **AI & Automation › Learning contracts**:

| Field | What the seam does with it |
|---|---|
| `objective` | the outcome the point is optimised for; shown beside the outcome rate |
| `secondaryMetrics`, `guardrails` | recorded intent — what must not degrade, the hard rules in words the constraints enforce |
| `allowedActions` | a candidate outside the list is removed BEFORE the policy, as the `learning-contract` constraint (named on the receipt) |
| `explorationMaxPercent` | caps `holdoutPercent` in the context; the receipt says "holdout capped at N % (asked M %)" |
| `autonomy` | overrides the point's class on every record |
| `fallbackAction` | answers when nothing is eligible, the policy fails — or the point is paused |
| `enabled` | `false` = paused: the fallback answers every decision and the record says "learning contract paused" |

Every save is a new version; every decision record cites `contract: "<id>@<version>"`,
so a change of intent is as attributable as a change of policy. When an arm
is removed by a contract the remaining weights are shared out pro rata, so
the propensity stays a true probability.

| Call | What |
|---|---|
| `GET /tmf-api/campaignManagement/v4/learningContract` | every point with its contract, or the defaults marked `defaults: true` |
| `GET / PUT / DELETE …/learningContract/{decisionPoint}` | read, upsert (version + 1), back to defaults |
| `POST …/learningContract/{decisionPoint}/dryRun {context, candidates}` | what the point WOULD decide under the current contract — nothing recorded |

## Console (phase 3)

**AI & Automation › Decisions**: KPIs (decisions, with outcome, with
propensity, fallbacks), filter by point and subject, one row per decision,
click for the receipt — the five sentences plus the exact context, eligible
set, constraints and evidence. **AI & Automation › Learning contracts**: one
row per point with its state (defaults / version / paused), an inline editor,
Back to defaults, and a dry run. Both pages have a `?` shelf
(`pane:decisions`, `pane:learning-contracts`).

## Proof

`ops/e2e/decision_log_test.js` (#121): the registry; sixty enrolments logged
one decision each with the eligible set, policy, version and a propensity that
equals (1 − holdout) × weight; the holdout decision at holdout %; a conversion
joins back by id and the receipt reads it; the tuner's judgement is a
deterministic decision with rows and evidence; the summary's propensity
coverage; no message text in the log; the ENet tenant reads nothing.
`ops/e2e/learning_contract_test.js` (#122): defaults per point; a contract
with allowed actions, a 10 % cap and autonomy low (a 95 % cap refused); the
dry run applies it without recording; a live journey asking 40 % holdout and
a B arm gets neither, the record cites the contract version and the
propensity is 0.9; pause makes the fallback answer and say so; the console
Decisions page shows the receipt and the Learning contracts page edits to v3
and dry-runs; the ENet tenant sees only its own defaults.
Unit: `DecisionPointsTest` — determinism against the historical hashes,
constraints before policy, fallback on failure, the tuner rule, the contract
(allowed actions, cap, autonomy, pause, citation), preview without recording.

## Not in this slice

A contextual bandit behind the same seam (phase 5), and replay / off-policy
evaluation over the log (phase 6) — both wait for a tenant with live traffic.
