# Collections — the ladder between a missed payment and a lost customer — plan

*2026-08-27. The repo audit was blunt: `DunningService` sends exactly
one reminder and then breaks the installment plan. There is no path
from an overdue bill to a restricted service, no promise-to-pay, no
cure-and-reconnect. That is not just competitively thin — under
Norwegian ekom law it is not even a lawful way to suspend a
non-paying subscriber. Every incumbent billing product ships a
collections module; TM Forum ships none. This arc builds the ladder.*

## Research findings

- **Ekomloven § 4-11 (LOV-2024-12-13-76) is the load-bearing rule**:
  measures on non-payment must be proportionate and
  non-discriminatory; at least one payment demand
  (betalingsoppfordring) before any restriction; suspension or
  termination at the earliest **one month** after demand + advance
  warning; **no subscription charges may be billed during a
  non-payment block**; only the affected service is blocked where
  technically possible; **emergency numbers stay reachable** even
  under outgoing-call barring.
- **Inkassoloven/inkassoforskriften gates the fees**: a reminder fee
  (purregebyr, 38 kr in 2026, adjusted annually) only if the reminder
  is sent ≥ 14 days after due date; at most 2 fee-bearing reminders
  (or 1 purring + 1 inkassovarsel); the collection warning must give a
  14-day deadline before referral. Forsinkelsesrenteloven late
  interest = policy rate + 8pp (12.00 % / 12.25 % p.a. through 2026);
  the fixed recovery compensation applies to **B2B debtors only**.
  Ekom practice also floors the actionable amount (≤ 250 kr minimum
  threshold per subscription in the forskrift — being renumbered under
  the 2024 act, so model it as a configurable statutory floor).
- **Industry shape is consistent** (BSCS dunning models per credit
  class, Amdocs Collections, Zuora/Chargebee/Stripe retry engines):
  an account-level escalation ladder over AR aging buckets, each rung
  = communication + optional enforcement + optional fee, with a
  payment-retry stage *before* the ladder for on-file payment methods,
  and promise-to-pay / dispute / hardship holds orthogonal to it.
- **TMF has no Dunning API** — the surrounding state is TMF666
  (account/AR), TMF678 (bill state), TMF676 (payments, refunds;
  ODA TMFC029 explicitly owns payment plans). SID GB922's Customer
  Bill Collection ABE supplies the entity names (collection case,
  dunning step, promise-to-pay). The policy resource itself is
  ours — proprietary in every vendor, so we expose it TMF-styled.
- **Repo recon**: the primitives all exist — AR + rate lines + credit
  notes in billing, PSP refund path in payment, suspend/resume and SIM
  block in service-orchestration, templates + opt-outs in
  communication, TMF696 risk hook in ordering. Collections is
  orchestration over what we already own.

## Design

### The case and the policy (billing)

`collection_case` — one per financial account (schema + RLS pair in
billing migrations): state machine
`CURRENT → REMINDED → WARNED → RESTRICTED → SUSPENDED → TERMINATED →
WRITTEN_OFF`, with orthogonal holds `PROMISE_TO_PAY`, `DISPUTE_HOLD`
(freezes the disputed amount only — the rest of the balance still
ages), `HARDSHIP_HOLD` (manual), and `CURED` (any settlement that
brings the actionable balance under threshold, from any state →
resume + reset). Aggregates **all** overdue bills on the account —
rung actions fire once per account, not once per invoice.

`dunning_policy` — per tenant, selectable per credit class / segment:
ordered steps `{offsetDays, templateId, enforcement:
none|restrict|suspend|terminate, feeType}`, entry threshold,
aging-bucket definitions, on-file payment retry schedule
(pre-ladder), promise-to-pay allowance per rolling period,
reconnection fee, write-off threshold + approval role,
small-overpayment auto-refund threshold. A **country statutory pack**
(geography-keyed, same doctrine as the registry/PSP seams) sits under
the tenant policy and cannot be undercut: the Norway pack pins the
14-day fee gate, the fee cap, the 2-reminder cap, the one-month
restriction notice clock, and the minimum actionable amount.

### Enforcement (service-orchestration + billing)

- `RESTRICTED` = a new **barring profile** on the service (outgoing
  barred / data throttled, emergency whitelist always on) — a lighter
  primitive than today's vacation `suspend`, added to the SOM
  controller alongside it.
- `SUSPENDED` = existing suspend with reason `nonpayment`; billing
  **stops recurring charges for the duration** (statutory — the
  billing run skips MRC accrual while a case holds the service in
  nonpayment suspension).
- Cure → automatic resume within the run, reconnection fee (tenant
  config — contractual, not statutory, so tenants may zero it),
  confirmation notification.
- Ordering gate: the TMF696 risk signals gain the collection state,
  so new postpaid orders for an account in `WARNED`+ get flagged/held
  by existing policy machinery rather than a new bespoke check.

### Money movements

Purregebyr and reconnection fees post as rate lines through existing
AR machinery, gated by the statutory pack. Overpayments above the
auto-refund threshold flow back via the existing PSP refund path;
below it they carry as account credit. Late-interest accrual is an
optional P4 line item (B2C tenants in Norway rarely charge it).

### Events (and the standing wiring rule)

`DunningStepReachedEvent`, `PromiseToPayCreated/Kept/Broken`,
`ServiceRestrictedForNonPaymentEvent`,
`ServiceSuspendedForNonPaymentEvent`, `CollectionCuredEvent`,
`DebtWrittenOffEvent` on the billing topic — each wired into the
insight trait listener, campaign topics, and bridge mappings (a
broken promise is a churn-risk trait; a cure is a win-back moment).

### Faces

Console (billing:admin): collections pane — case list by state and
bucket, hold/release, promise-to-pay entry, write-off with approval,
policy editor showing the statutory floor read-only. Selfcare:
outstanding-balance banner with pay-now (existing PSP checkout),
promise-to-pay request, and the legally required advance warnings
rendered from the notification templates.

### Proof

`collections_test.js`: age an unpaid bill → reminder only after the
fee gate → warning → restrict (assert emergency whitelist + MRC still
billed) → suspend (assert MRC stops) → pay → auto-resume +
reconnection fee. Promise-to-pay pauses the ladder and a broken one
resumes it. Dispute hold freezes only the disputed amount. Statutory
pack refuses a tenant policy that undercuts the floor.

## Phases

- **P1** — case + policy + sweeper + events (notifications only).
- **P2** — enforcement: barring profile, nonpayment suspend, MRC
  stop, cure/resume, reconnection fee.
- **P3** — promise-to-pay, dispute/hardship holds, pre-ladder payment
  retry, ordering risk gate.
- **P4** — console/selfcare faces, overpayment auto-refund, optional
  late interest, write-off approvals.

Docs upkeep on ship: manual + architecture + README per house rule.
