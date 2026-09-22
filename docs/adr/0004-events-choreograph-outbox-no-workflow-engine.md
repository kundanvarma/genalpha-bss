# 0004 — Events choreograph: outbox, one topic per component, idempotent consumers

**Status:** accepted, 2026-07-09 (transactional outbox landed; recorded as an ADR 2026-09-22)

## Context

An order becomes a bill by passing through a dozen components; notifications,
campaigns, churn scoring, the decision log and Live Flow all want to know.
Publishing to Kafka inside a business transaction can lose an event or emit
one for a rolled-back change. A central orchestrator (BPM engine, saga
coordinator) would know every component's rules and become the one thing
nobody can replace.

## Decision

- Every state change that matters writes an **outbox row in the same
  transaction** as the business change. A relay (every 2 s) publishes it to
  Kafka. Delivery is at-least-once; the relays are deliberately not
  single-instance guarded.
- One topic per component: `bss.<component>.events`. Envelope:
  `{eventId, eventTime, eventType, tenantId, event{...}}`. The tenant rides
  the envelope so consumers stay partitioned without knowing tenancy rules.
- Consumers are **idempotent** per `(tenant, eventId)` and act as the
  envelope's tenant.
- Components choreograph through events; there is **no central workflow
  engine**. Where a process needs an auditable timeline (launch governance)
  it is *mirrored* into a TMF701 process flow — a record, not a driver.
- Any change to a domain event updates its consumers (insight, campaign,
  bss-bridge) in the same change; the martech sweep is re-run.

## Consequences

- Costs: eventual consistency between components; every consumer carries a
  dedup table; a duplicate relay is waste; 27 outbox relays to watch; no
  single place to read "where is this order in its process".
- Buys: no event lost across a crash; a component can be replaced or added
  without touching the publisher; an MVNO's events are already its own.

## Enforced by

The suite of every arc asserts the event it introduces; the martech sweep
after any event change; `hardening_test.js` (#56) proves one bill cut under
two billing replicas; review of the consumer list on any event change.

## Related

`docs/architecture.md` §4 (event backbone), `docs/hardening.md` ("One
replica speaks at a time"), `docs/launch-governance.md` (TMF701 mirror),
`docs/engineering-conventions.md` §3.
