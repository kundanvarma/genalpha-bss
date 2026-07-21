# P2 hardening — plan

*2026-07-21. P0 made scale-out safe, P1 made it fast and observable. P2 is
the compliance ledger: the rights a European subscriber actually holds, the
retention the law actually demands, and honest documentation for the three
things only a real engagement can prove (PCI attestation, regional DR, a
third-party penetration test).*

## 1. The right of access — a data passport (GDPR Art. 15/20)

**Design.** `GET /privacy/v1/export` on party-account aggregates a person's
data across the fleet — and the privilege stance is the CSR-copilot one:
the fan-out rides the CALLER's own token. A customer exporting themselves
presents their own credentials to every service, and each service returns
only what that token could read anyway — the right of access implemented
as *no new authority at all*. A DPO (role `roles:admin`, the user-admin
standing in as data-protection officer — documented) may export any party.
Each participating service exposes its own `/privacy/v1/export` slice;
the aggregate is one portable JSON document with a category per service.

## 2. The right to erasure — with the law's own exceptions (Art. 17)

**Design.** Erasure is a fan-out with two honest refusals built in:

- **Active services refuse erasure** (409): you cannot erase a subscriber
  who still has a line — terminate first. That is Art. 17(3)(b)
  (performance of contract), not a loophole.
- **Bills, payments, orders, usage and agreements are RETAINED** under
  bookkeeping law (5-year class) — the erasure REPORT names each retained
  category and its legal basis, so the answer to "was I erased?" is a
  document, not a shrug.

What actually goes: interactions, messages, campaign enrollments, carts,
tickets, appointments, insight profiles — deleted; the party profile —
anonymized in place (name "Erased", contact media gone, id row kept for
referential integrity); the login — disabled at the IdP via user-roles.
Every service's `/privacy/v1/erase` (DPO only) reports
`{category, deleted, retained, reason}`; party-account orchestrates and
stores the full report as an immutable `erasure_record` row (the audit of
the erasure outlives the erased).

## 3. Retention as data (the clock that empties shelves)

Per-tenant retention dials in the registry (`retention-interactions-days`,
`retention-tickets-days`; 0 = keep forever, the dev default so no suite
loses its timeline), enforced by TickGuard-guarded sweeps in
party-interaction and trouble-ticket (closed tickets only). The suite
proves it with a dev clock: seconds instead of days, one interaction
provably gone on schedule. Richer policy-engine-authored retention rules
are noted as the follow-up — the sweep is the hard part; where the number
lives is config either way.

## 4. Documented honestly, not pretended

- **PCI scope** (docs/privacy.md §PCI): card numbers never enter the BSS —
  the storefront exchanges them with the PSP; the vault stores tokens.
  Verified against the payment-method entity, stated as an SAQ-A-shaped
  posture, with the caveat that a QSA attests, not a README.
- **DR**: the restore drill gains a timed mode → measured RTO/RPO on this
  stack; regional DR is deployment topology (managed DB replicas +
  object-store replication), runbook in hardening.md.
- **Lawful intercept**: the BSS half is warrant-gated subscriber
  disclosure (ETSI X1-shaped); designed, not built — stated in
  docs/privacy.md with the boundary (network LI lives in the network).
- **Pen test**: the suites' adversarial checks (404-not-403, tenant walls,
  scope probes) are regression armor, not an assessment — a third-party
  test remains on the ledger.

## The proof (suite #58, privacy_test.js)

1. A customer with data in many shelves (interaction, cart, message) but
   no active service — EXPORTS themselves: one JSON, categories present.
2. A second customer WITH an active plan — erasure REFUSED 409.
3. The first customer erased by the DPO: profile anonymized (name gone
   from the API), interactions/carts/messages gone, LOGIN DEAD (the
   password grant fails), and the erasure report names every category —
   deleted counts and retained-with-reason both.
4. Retention: party-interaction recreated with a seconds-scale dev clock,
   an interaction provably swept; restored after.
5. The erasure audit row exists and says who, when, what.

## Order of work

1. Explore the nine services' entities/repos (fan-out map).
2. Per-service `/privacy/v1/*` endpoints (9 services) + party-account
   orchestration + erasure_record migration + gateway route.
3. Retention sweeps (party-interaction, trouble-ticket) + TickGuard in
   both + registry dials.
4. docs/privacy.md + hardening.md P2 flip; storefront My-page privacy
   card if it fits the budget.
5. Suite #58, regressions (storefront, csr, martech at minimum), docs,
   commit.

## Shipped

Suite #58 (`ops/e2e/privacy_test.js`) green on its FIRST run — all seven
checks:

- The data passport: self-service export, the caller's own token walking
  eight services, legal-hold categories named beside the data.
- The honest refusal: an active subscriber's erasure answered 409.
- The eraser: interactions/carts/messages/marketing deleted; retained
  categories each carrying their legal basis; profile anonymized in
  place; the login dead at the IdP (the erased person's password simply
  stops working); the audit row outliving the erased.
- Retention as a clock: a probe interaction swept on a 5-second dev dial
  by the TickGuard-guarded sweep, then the dial restored to keep-forever.

Landed: `/privacy/v1/*` in nine services + the party-account orchestrator
(fan-out on the caller's bearer, fail-closed active check, `erasure_record`
V19/V20), `IdpAdminClient.eraseUser` (disable + scrub at Keycloak),
retention sweeps + TickGuard in party-interaction (V6) and trouble-ticket
(V4), the gateway `/privacy` route (+ routing test), `docs/privacy.md`
(including verified PCI scope and the LI boundary statement), hardening.md
P2 flip. One build fix en route: a prefix-matched string check claimed
`findByTenantIdAndPartyId` existed when only its `AndStatus` cousin did —
exact-signature checks from now on. Regressions green: csr, martech,
storefront. Deferred honestly (P2.5): third-party pen test, regional DR
on real topology, Art. 30 register/DPIA, per-tenant retention dials,
the warrant-disclosure endpoint.
