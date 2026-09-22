# 0015 — Proof by numbered browser suites; docs end with honest limits

**Status:** accepted, 2026 (the practice since the first storefront suite; recorded as an ADR 2026-09-22)

## Context

One person and an agent built forty components. Unit tests prove a class;
they do not prove that a customer can register, configure a bundle, pay,
get a bill and pay it again through the real gateway with a real token.
Public claims about the system need a receipt anyone can re-run, and the
docs need to say where the receipt stops.

## Decision

- **A feature exists when its numbered suite is green.** Each arc adds
  `ops/e2e/<arc>_test.js` (Playwright, Node), numbered in sequence, that
  drives the fleet **through the gateway with real Keycloak tokens** and
  the stub AI provider. No suite, no feature — a demo is not proof.
- Suites run against the demo fleet (`fleet.sh demo`, then refresh), never
  against a half-started `up`.
- `ops/run-all-suites.sh` runs every suite serially with a readiness gate,
  one retry pass and one written receipt (`ops/e2e/.proof-run/`); the
  published proof run lists attempts on record.
- `mvn test` keeps the component-level proofs (real-Postgres migrations,
  RLS, DTO round-trips); the CTKs keep the standard's (ADR 0001).
- **Every arc document ends with an honest-limits section** naming what is
  not built, not proven, or only proven on a laptop. The README carries the
  same voice ("any cloud is two invoices, not a claim").
- Regeneration is never a substitute for maintenance where a suite exists:
  the suite is the contract, a regenerated file is a regression surface.

## Consequences

- Costs: ~40 minutes for the full run on a warm fleet; suites depend on
  seeded personas and are brittle to copy changes ("Paula Family" substring);
  the RAM wall on a laptop shapes what can be proven at once.
- Buys: every claim in the README has a script; a refactor (the type
  ratchet, the React migration) is safe exactly as far as the suites reach;
  a buyer can run the proof themselves.

## Enforced by

`ops/run-all-suites.sh`; CI builds and tests every service on each push.
The honest-limits rule is held by review: the conventions name an
`ops/arch/ratchet.sh --docs` check, but the script does not carry that flag
today and only some docs use the exact heading (others say "What is
deliberately not here" or "Boundary (honest)") — a follow-up.

## Related

`docs/engineering-conventions.md` §7–8, `README.md` "Verification",
`docs/proof-run-findings.md`, `docs/ctk-conformance.md`.
