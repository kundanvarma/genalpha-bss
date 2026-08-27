# Norway rails — the reference geography grows its missing adapters — plan

*2026-08-27. The doctrine is settled: country-specific capability =
country-keyed adapter seams, Norway ships built-in as the reference
(freg address verification, Vipps, Klarna, the BankID step-up gate).
But the reference geography is still missing the rails an operator
lives on daily: bill distribution into the banks, a digital mailbox
fallback, a production eID broker, the directory-services obligation,
ongoing registry sync with protected-address handling, and credit
decisions at signup. This arc fills the seams — each one an adapter
behind a generic port, never a hard dependency.*

## Research findings

- **eFaktura + AvtaleGiro** (Mastercard Payment Services, coordinated
  by Bits) are the default consumer billing rails: since 2022 the
  consumer gives one general consent ("Ja takk til alle") and the
  biller does an **alias lookup** to find the eFaktura address to
  stamp on each request-for-payment; AvtaleGiro is the companion
  direct-debit with bank-side mandates keyed on account + KID,
  mandate add/delete files in, claim files out, OCR settlement back.
  Test environment + certification process exist. Key consequence:
  **delivery is never guaranteed** — no consent means you need a
  fallback channel by design.
- **Digipost** is that fallback: clean REST API, letters addressed by
  national id, invoice metadata enabling pay-from-mailbox,
  print-fallback for non-users, free test environment. e-Boks exists
  as a second driver behind the same port, on demand.
- **BankID in production is always a broker** (Signicat, Vipps): plain
  OIDC authorization-code with `acr_values` for step-up level, acr/amr
  claims + national id back. Our token-claim gate is already the
  right shape — production is an extra OIDC IdP in Keycloak plus an
  acr→step-up-claim mapping, not new code paths.
- **Directory services are an obligation, not a feature**: providers
  must deliver subscriber data to number-directory services daily
  (industry standard agreement, bulk delta files) and honor
  reservations — full, partial, and **secret number** (mandatory free
  service: total directory suppression + CLIR, never-recycled
  number). The regulator audits leaks of reserved numbers. The data
  model must exist before any export does.
- **Registry sync**: the population-registry API exposes an event
  feed (address changes, deaths, name changes) polled with
  Maskinporten auth; private companies with a customer relationship
  qualify for access; a re-sync service stores the person-id link,
  polls, re-fetches on relevant events, and reuses the existing
  address-verified event. **Protected addresses (kode 6/7)** simply
  return no address — the BSS obligation is graceful address-absence:
  never require or cache a street address for those parties, suppress
  in consoles and every export (directories included!), deliver to
  pickup points, credit-check on national id without address match.
- **Credit checks**: five licensed bureaus offer synchronous APIs
  returning score + payment remarks (+ debt-register-informed
  scores); telecom postpaid signup is a recognized legitimate
  purpose; must handle the voluntary **credit freeze** (offer prepaid
  or ask the customer to lift it) and store the decision, not the
  report. Direct debt-register access is lenders-only — skip.
- **Carrier billing (CPA/Strex-style content charges)** is
  host-operator-mediated for MVNOs: the BSS consumes a third-party
  charge feed onto the bill and enforces the statutory spending
  limits (see the usage-policy plan) rather than initiating payments.
  No self-service sandbox; needs a real host-operator relationship.

## Design

### Bill distribution seam (billing + new adapters)

A `BillDistributor` port with an ordered channel strategy per party:
`eFaktura → digital mailbox → print/PDF`. Drivers: **efaktura**
(alias lookup at party create/update + per-bill request-for-payment
emission, KID discipline from the existing bill numbering),
**avtalegiro** (mandate file ingest → stored payment authorization;
per-cycle claim emission; OCR settlement ingest closing bills — a new
settlement source next to the PSP flow), **digipost** (letter-out
REST driver, also used by collections notices and migration notices —
the other arcs' legally required letters get their channel here),
**mock drivers** for e2e. Channel choice and consent state live on
the party billing profile; every send records the channel actually
used (the audit story collections needs).

### eID broker seam (identity)

Register the broker as an OIDC IdP in Keycloak per deployment; map
broker `acr` to the existing verified-identity claim. Ship a
**mock-broker** container for demos/e2e (mirrors the existing
mock-registry pattern) so the step-up flow is drivable end-to-end
without a commercial agreement.

### Directory obligation (party + new `directory-export`)

Per-subscription directory state: `exposure full | partial |
reserved` + `secretNumber` flag (free, suppresses everything, CLIR
on, number never recycled — number-inventory interaction noted for
the future). Console + selfcare toggles with the legally required
defaults (minors hidden by default). The exporter itself is a small
batch adapter (daily delta file per the industry agreement) built
against a mock endpoint now, a real 1881-style agreement per tenant
later. **Every export path honors the protected-address flag.**

### Registry sync (extends the freg seam)

`registry-sync` worker: stores person-id links, polls the event feed
(Maskinporten auth in real deployments, mock-freg feed in dev),
re-fetches on address/name/death events, updates party, emits the
existing address-verified event (CDP re-home already listens).
Death events open a care task instead of blind updates. New party
flag `addressProtected`: set when the registry returns no address —
gates address display in consoles, address requirements in checkout
(pickup-point delivery), and all exports.

### Credit decision seam (ordering + new adapter)

`CreditDecisionPort`: `assess(nationalId, purpose) → {decision,
score-band, remarks-present, frozen}` with one bureau driver + mock.
Slots into the existing TMF696 risk assessment as an external signal
(fail-open stays the policy default; tenants flip to fail-closed).
Credit-freeze returns a distinct state → checkout offers the prepaid
path. Store the decision + timestamp only.

### Deliberately deferred

Carrier-billing charge ingest (needs a host-operator feed; the
statutory spending-limit machinery lands independently in the
usage-policy arc), e-Boks driver, real directory-export agreements,
real broker/bureau contracts — all per-tenant deployment concerns
behind ports that mock drivers keep honest in CI.

### Events

`BillDistributedEvent {channel}`, `MandateRegistered/Cancelled`,
`SettlementReceivedEvent`, `PartyAddressProtectedEvent`,
`CreditDecisionRecordedEvent`, `DirectoryExportedEvent` — wired to
insight traits, campaign topics, and bridge mappings per the
standing rule.

### Proof

`norway_rails_test.js`: bill distribution falls eFaktura → mailbox →
print as consent flags flip, and the collections notice rides the
same seam; AvtaleGiro mandate + claim + OCR settlement closes a
bill; secret-number subscription vanishes from the directory export;
protected-address party checks out with pickup-point delivery and no
street address anywhere in consoles; credit-freeze at checkout
offers prepaid; mock-broker step-up satisfies the verified-identity
gate end-to-end.

## Phases

- **P1** — registry sync + protected-address flag (the cross-cutting
  compliance gate everything else must honor).
- **P2** — bill distribution seam: eFaktura + Digipost drivers, KID,
  fallback chain.
- **P3** — AvtaleGiro mandates/claims/settlement; credit-decision
  port + checkout wiring.
- **P4** — directory data model + mock exporter; mock eID broker
  federation.

Docs upkeep on ship: manual + architecture + README per house rule.
