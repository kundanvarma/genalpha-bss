# 0006 — Seams for vendor- and country-specific systems

**Status:** accepted, 2026 (first per-tenant seams shipped 2026-07-11 to 2026-07-14: PSP, CMS, PIM, OCS; recorded as an ADR 2026-09-22)

## Context

An operator already has a PSP, a CMS, an OCS, an ACS, an HSS, a print house,
a national registry, a number-porting clearinghouse. The BSS must reach them
without becoming one operator's integration project, and a demo must run on
a laptop with none of them present. Country rules (registries, e-invoice
formats, porting) differ per market served.

## Decision

Anything vendor- or country-specific sits behind a **seam** with one shape:

1. **One interface** (a port) in the owning component — `OcsProvisioningClient`,
   `FinancingProvider`, `AucClient`, `RateLimitStore`.
2. **One adapter per vendor**, registered by name (`http`, `sigscale`,
   `sanity`, `stripe`, `mock-bank`).
3. **A stand-in in the fleet**: a `mock-*` container (`mock-ocs`, `mock-acs`,
   `mock-hss`, `mock-sonata`, `mock-freg`) so every leg is provable offline.
4. **Selection per tenant** in `infra/tenants/tenants.yml` (`ocs-provider`,
   `ocs-base-url`, a `secret_ref` naming an env var, never a key), with a
   deployment default so an unbound tenant behaves as before. **Today that
   is intent, not fact:** most credential keys hold the value itself as a
   `${ENV:default}` placeholder (`ocs-password`, `esp-api-key`,
   `social-access-token`, `bank-token`, `ai-api-key`, with dev defaults such
   as `bss-secret` committed); only `whatsapp-token-ref` is a reference. Five
   seams — PSP, national registry, carrier, field service, CMS — are bound by
   DB rows written over REST (`base_url` + `secret_ref`), so a binding can be
   repointed by an API call under the right role, not only by a file change.
   Follow-up: `*-ref` keys throughout, a secret scan that refuses any
   `*-password`/`*-token`/`*-key` value that is not a `${…}` reference, and
   a receipt on every seam-binding write.
5. **Fail soft**: a quiet vendor never blocks the customer's action; the gap
   is logged and reconciled later (an unreachable OCS never blocks
   activation; a dead legacy catalog never breaks the native list).

Two deliberate exceptions: **money has no generic connector** — PSPs are
named adapters only, with idempotency and webhook verification; and where a
seam is *absent* (blank `CPE_BASE_URL`) the feature disappears rather than
degrades.

## Consequences

- Costs: a mock per seam to maintain; an adapter registry per component; the
  real adapter is often a configuration line nobody has run against the
  real vendor yet (said in each doc's honest limits).
- Buys: one fleet charges tenant A on the mock, B on SigScale, C on a vendor
  gateway; a new country is an adapter, not a fork; every suite runs green
  with no external account.

## Enforced by

Every seam has a `mock-*` container and a suite that runs against it
(#123 SigScale OCS, #124 entitlement, #129 equipment, #112 field service,
`norway_rails_test.js`); the suite asserts the action completes with the
stand-in down where fail-soft is the contract. Nothing yet refuses a
literal credential in `tenants.yml`.

## Related

`docs/ocs-seam.md`, `docs/equipment-seam.md`, `docs/entitlement-seam.md`,
`docs/field-service-seam.md`, `docs/architecture.md` "Boundary notes",
`docs/engineering-conventions.md` §6.

Corrected 2026-09-22 after the threat model (docs/threat-model/README.md).
