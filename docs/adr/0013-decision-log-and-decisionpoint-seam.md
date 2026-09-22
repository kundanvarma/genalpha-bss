# 0013 — Decision log and DecisionPoint seam; learning contracts as tenant configuration

**Status:** accepted, 2026-09-10 (phases 1–4 of the continuous-learning architecture)

## Context

The BSS makes adaptive choices: who lands in a holdout, which message
variant is dealt, which journey speaks this tick, what the advisor proposes,
what a desk is nudged to change. Clicks alone cannot teach a better policy;
learning needs the choice that was *available* at the time and the
probability with which one was taken. An auditor needs the reason.

## Decision

- Every adaptive choice goes through one **DecisionPoint seam**
  (`DecisionPoints.decide(point, subject, context, candidates, constraints, fallback)`).
  Constraints remove candidates first and are named; the point's registered
  `DecisionPolicy` (name + version) picks; a fallback answers when nothing
  is eligible or the policy fails.
- Each decision lands as one row in insight's **decision log**: identifiers,
  the eligible set, the chosen action, policy and version, propensity, one
  sentence of reason, and — when it arrives — the outcome joined by id.
  Idempotent by decision id; RLS per tenant.
- Autonomy class is declared per point: high for reversible message
  choices, medium for recommendations and traffic shifts, low for money,
  rights or statute (none automated in this slice).
- The ontology's receipts are decisions on `ontology.*` points (ADR 0011).
- **Learning contracts** are tenant configuration, one per point:
  objective, guardrails, allowed actions (a constraint), exploration cap,
  autonomy override, fallback, enabled. Every save is a new version and
  every record cites `contract@version`. Paused = the fallback answers.
- Experimentation stays where it was: the holdout measures lift; the log
  never becomes its own judge. Reinforcement learning is not on any buyer
  roadmap.

## Consequences

- Costs: a decision row for every enrolment and treatment; a policy that is
  not registered as a `DecisionPolicy` cannot be used at a point; rows from
  before the log have no outcome and none is invented.
- Buys: off-policy evaluation of a new policy on yesterday's decisions
  before any customer meets it; "why did this customer get this" answered
  in one row; a change of intent is as attributable as a change of code.

## Enforced by

The decision-log suites (#121, #122): a decision is recorded with eligible
set and propensity, an outcome joins by id, a contract's constraint is
named on the receipt, a paused contract answers with the fallback.

## Related

`docs/decision-log.md`, `docs/journey-auto-tuning.md`,
`docs/desk-learning.md`, `docs/ontology-seam.md`.
