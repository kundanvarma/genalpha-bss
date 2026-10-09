# 0025 — An action receipt is written before the action, not after

Status: accepted, 2026-10-09 — **not yet implemented**; "What holds it" names the
checks that must exist before this ADR is true, and today none do.

## What forced it

An outside architecture recommendation asks for one control above the rest: *if
durable evidence capture is unavailable, block or queue the consequential
operation.* The code does the opposite. Measured on 9 October against main and the
running box:

- **Startup fails closed, runtime does not.** `ReceiptPublisher` refuses to start
  without Kafka (`receiptsRequired = true` is the *application* default), but
  `template.send(...)` is asynchronous and on failure `drop()` only logs at ERROR
  and increments a counter. The governed action has already happened.
- **Ontology has no database and no migrations** — nowhere to record intent before
  dispatch, and no local transaction for an outbox row to join. It is the only
  component in the fleet without one.
- **`ExecuteReceipt` carries no timestamp**, and no attempt identity beyond the
  `idempotencyKey` added in #266.
- **The store already exists**: `decision_log` in insight (V38, RLS V39) held
  4,019 ontology rows on the box. An earlier response of mine claimed there was
  none; that was wrong, and it matters here, because the work is not "build a
  store" but "make capture reliable".

Competing ontology products sell the preventive half — invalid actions made
impossible to attempt, scenarios staged for review — and publish nothing about
durable evidence. Prevention is table stakes; evidence is what we claim (ADR 0011)
and have not made true.

## The decision

**A governed action's receipt is written ahead of the action, in ontology's own
database, as two append-only records.**

- **Attempt**, committed *before* dispatch: principal, agent identity and version,
  action and version, resolved inputs, authority and scope, the `Check` verdict,
  target component/capability/route, `attemptNo`, `requestedAt`, `idempotencyKey`.
  If it cannot commit, the action is **refused and never dispatched**.
- **Outcome**, a new linked row appended after the call returns:
  `succeeded | failed | uncertain`, component status, provider reference, effects,
  emits, `completedAt`, compensation reference. Never an `UPDATE`.

Write-ahead rather than write-after, because recording afterwards and failing the
response when the record fails tells the caller the thing did not happen *when it
did*. Refusing before means the side effect never occurs.

**The direction rule is asymmetric, and getting it backwards is the classic
error:** a failed *attempt* write refuses the action; a failed *outcome* write
never fails the caller — it leaves the attempt `uncertain` for reconciliation.

Rows carry `prev_hash`/`row_hash` as a linear per-tenant chain, head hash and count
kept separately so truncation is detectable. A chain, not a Merkle tree: records
are append-only and read in order, so a chain suffices and an auditor can follow it.

**What a receipt does not prove:** attribution, integrity and ordering only — not
that the action was correct, compliant or humanly authorised. Those are the `Check`
and the approval ladder, *recorded in* the receipt, not established by it. The
failure mode of evidence schemes is false assurance.

## What it costs

Ontology stops being stateless: a database, migrations, row-level security and an
outbox relay, following the pattern `agreement` already proves. One local commit of
latency before every governed action.

One honest limit: ontology co-commits **intent and its outbox row**, not business
data. The business change happens in the target component and belongs in *that*
component's outbox (ADR 0004), so the recommendation's "business change and receipt
in one transaction" stays that component's obligation and this ADR does not
discharge it.

## What holds it

None of these exist yet. The ADR is not true until they do:

- a numbered suite that breaks the receipt store on purpose and proves a
  receipt-less path **cannot complete** a consequential action;
- a chain verifier that can go red, called from `ops/arch/claims.sh`;
- a test that a timeout reconciles to an explicit result, not a stuck `uncertain`;
- `ops/security/rls_check.py`, which picks the new table up from the live catalogue;
- a stated retention policy with an assertion — durability resting on the absence
  of a deleter cannot be audited.

## Related

- [0011](0011-operational-ontology-governed-actions.md) — governed actions and the receipt this refines
- [0013](0013-decision-log-and-decisionpoint-seam.md) — the decision log, whose `outcome*` columns are overwritten in place and should become linked rows under this rule
- [0004](0004-events-choreograph-outbox-no-workflow-engine.md) — the outbox this reuses
