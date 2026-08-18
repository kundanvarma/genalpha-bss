# Registry-verified delivery (Folkeregisteret) — anti-fraud address plan

**Status:** F-P1 SHIPPED 2026-08-18 (registry seam live: `registry_config`/`registry_lookup_log` V5+V6, `RegistryAdapter`/`Registry`/`Router`, built-in `freg` adapter, `mock-freg` :8141, `seed_registry.py`, `registryMatch` on TMF673 validation — authenticated callers only; all six paths proven live) · F-P2–F-P4 PLANNED · **Depends on:** TMF673 `geographicAddressValidation` (geographic-address), TMF696 risk (intelligence `RiskService` + ordering `RiskClient` → policy context), the carrier/delivery-place rails (carrier-choice-plan), the mock-provider seam pattern (`integrations/mock-*`)

**The gap.** Home delivery ships to whatever address the shopper types. The address rides the
order (TMF622 `place`, role `shipping`) and fulfilment books it faithfully — but nothing ever
checks the person *lives* there. That's the classic Nordic fraud pattern: a stolen identity +
postpaid handset order + a drop address. Norwegian practice closes it with the
**folkeregistrert adresse** — verify the shopper's claimed address against the national
population register, and treat a mismatch as a risk signal, not a hard stop.

---

## 1. What we found (grounded in the code)

- **The shop DOES capture an address** — `apps/storefront/src/address.js` (street1/postCode/
  city/country), saved on the TMF632 individual as a `postalAddress` contactMedium, riding each
  physical order item as a TMF622 place. It is **self-declared and unverified** — that's the
  hole, not a missing form.
- **TMF673 validation endpoint already exists** — `GeographicAddressController` exposes
  `POST /geographicAddressValidation` (the standard resource for "is this address real /
  standardize it"). It's the natural home for the registry seam.
- **The risk rail is live end-to-end** — intelligence exposes TMF696
  (`partyRiskAssessment` / `productOrderRiskAssessment`, persisted with signals); ordering's
  `RiskClient` fetches an assessment per order and injects `riskScore` / `riskLevel` into the
  **policy context**, where the OPERATOR's rules decide thresholds. Fail-open, enforcement
  stays data. A new signal slots in with zero new plumbing.
- **The choice-rides-the-order pattern** (simType, deliveryMethod) means "deliver to registered
  address" is one more characteristic on the delivery place, read at the same fulfilment point.
- **The provider seam pattern is proven** — carriers/PSPs are per-tenant config rows
  (`baseUrl` + `secretRef`, PUT-upserted, mocks for offline proof, real credentials =
  config-only swap). The registry provider is the same shape.

## 2. Best practice (researched, cited)

- **Freg is API-accessible to private companies.** Skatteetaten's sharing services let private
  enterprises apply for a **rettighetspakke** (rights package); the **"Privat virksomhet"**
  package (no statutory basis needed, but a GDPR processing basis required) returns
  **non-confidential data including current residence address and moving date**. Addresses under
  protection (kode 6/7, beskyttelsesinstruksen) are **never returned** — the system must treat
  "no data" as a first-class answer, not an error.
  ([rettighetspakker](https://www.skatteetaten.no/en/deling/folkeregisteret/intro/finne-data/rettighetspakker/),
  [distribusjonsmodell](https://www.skatteetaten.no/en/deling/folkeregisteret/distribusjonsmodell/),
  [API-dokumentasjon](https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/om-tjenestene/))
- **Access routes:** direct API integration (free, after approved application), or via a
  **system vendor / data processor** (Signicat, Dun & Bradstreet, Experian, Infotorg…) under a
  databehandleravtale — which is exactly our `baseUrl`+`secretRef` seam: mock for demo, real
  distributor or direct Freg for production, no code change.
- **Telco practice:** Norwegian operators credit-check postpaid signups (the credit report is
  keyed to the registry identity); prepaid skips it — Telenor/Telia both market "uten
  kredittsjekk" prepaid for exactly this reason, and Telenor auto-syncs customer names from
  Folkeregisteret monthly. First postpaid *device* order to the registered address (or a
  pickup point, where the carrier checks photo ID at handover) is the standard fraud posture.
  ([Telenor](https://www.telenor.no/mobilabonnement/abonnement-uten-kredittsjekk/),
  [Telia](https://www.telia.no/mobilabonnement/kontantkort/mobilabonnement-uten-kredittsjekk/))
- **GDPR footing:** fraud prevention is a textbook **legitimate interest** (GDPR recital 47);
  fødselsnummer use requires *saklig behov* (personopplysningsloven §12); every lookup must be
  logged with purpose; store only the match outcome + minimal address, never the raw registry
  response. Protected addresses must never leak — the UI shows "could not verify", nothing more.

## 3. Decision

**The seam is "national registry", not "Folkeregisteret".** Registry verification is a
geography rule, so the abstraction is **country-keyed and pluggable** — a `RegistryAdapter`
per country, resolved by the delivery country + tenant config, exactly like carriers resolve
per tenant. **Norway ships built-in** (`freg` adapter, the reference implementation); Sweden
(SPAR), Denmark (CPR), Finland (DVV) or a global KYC vendor are *new adapter classes + a
config row, not a new flow*. A country with no pluggable registry (e.g. UK — credit-bureau
data only) simply has no adapter bound: validation degrades to postal-wash-only and the risk
signal reads `addressVerified=unavailable`, which the operator's rules can weigh however that
market demands. Same honesty as the carrier seam: the mock proves the flow offline, a real
adapter is config.

**The same doctrine covers carriers and payments** (decided with this plan): Posten/Bring,
Helthjem, PostNord and Klarna/Vipps are the built-in *Norwegian* delivery and payment menus.
Both seams are per-tenant config already; when a second market ships, `carrier_config` and the
PSP config grow the same `country` key so one tenant's menus resolve per market (a DE market
plugs DHL + giropay the way NO ships Posten + Vipps). No code moves — the adapters and routers
are already the right shape; the registry seam here is built country-keyed from day one.

**Verify, don't wall.** The registered address is the *default and the fast lane*, not a cage:

1. A signed-in shopper's address can be **registry-verified** (TMF673 validation, registry
   provider behind it). Verified → stamped on the party, rides orders as
   `addressVerified=registry`.
2. Typing a **different** delivery address stays allowed (students, workplaces, cabins are
   legitimate) — but it lands as a TMF696 signal (`addressMatchesRegistry=false`), and the
   **operator's policy rules** decide: allow / force pickup-point-with-ID / hold for review.
   Enforcement stays data, same as every other gate in this BSS.
3. Pickup points are the safe alternative (carrier checks ID at handover) — already built.

## 4. The phases

### F-P1 — the country-pluggable registry seam (`mock-freg` + TMF673 provider)
- geographic-address: `RegistryAdapter` interface (`match(config, person, address)` →
  normalized `{match|mismatch|no_data, registeredAddress?, movedDate?}`) + `RegistryRegistry`
  (adapter per provider key) + `RegistryRouter` — resolution:
  **delivery country → tenant's `registry_config` row for that country → adapter**; no row =
  no registry for that market, postal-wash only. Mirrors
  `CarrierAdapter`/`CarrierRegistry`/`CarrierRouter` one-for-one.
- `registry_config` (Flyway + RLS): `(tenant_id, country, provider, base_url, secret_ref,
  enabled)` — unique per tenant+country. **`freg` (NO) ships built-in** as the reference
  adapter; `spar` (SE), `cpr` (DK), or a KYC vendor are later adapter classes + config rows.
  Console Integrations card later.
- `integrations/mock-freg` (Node, same skeleton as `mock-bring`): `POST /personer/match` takes
  `{name, birthDate | fnr, address}` → the normalized answer above. Seed the demo personas
  (paula/wilma/sonny at the family address; nils/norah at Nova addresses; one persona with
  `no_data` to prove the protected-address path).
- Extend `geographicAddressValidation` with a `registryMatch` part when the caller supplies a
  party context. **Log every lookup** (who, whose address, purpose, which registry) — the GDPR
  ledger; retention and lawful basis differ per country, so the log row carries the country.

### F-P2 — the party stamp + checkout UX (say the address, gate the change)
- On a match: save the standardized registered address on the party as a second contactMedium
  (`postalAddress`, characteristic `source=folkeregisteret`, `verifiedAt`) — the typed medium
  stays the shopper's own.
- **The delivery block must SAY the address** (demo-found 2026-08-18: the shipping-address form
  prefills silently higher up the page, and the home-delivery row named the carrier but not the
  door — a signed-in shopper picked Helthjem without ever being shown where it would ship).
  Home-delivery rows read **"Delivered to Storgata 1, 0150 Oslo by Helthjem"**; below the menu,
  a one-line **"Delivering to: \<address\> ✓ registered · Change"**. No silent address, ever.
- **"Change" is a step-up, not a free edit** (NO market): tapping Change requires an **eID
  verification (BankID)** before an alternate delivery address is accepted — the person
  redirecting the parcel must prove they are the account holder, not a session-thief. The
  eID gate is a **country-pluggable seam like everything else** (BankID NO/SE, MitID DK,
  FTN FI; `eid_config` per tenant+country; `mock-bankid` for the demo, real = an OIDC
  step-up — Keycloak brokers the eID IdP and the shop asks for the elevated `acr`, so the
  gate is an auth claim, not custom crypto). Markets with no eID bound fall back to the
  F-P3 posture: free change allowed, `addressSource=manual` risk signal, policy decides.
- Guests: manual only (no identity to match, no step-up to offer — risk rules carry it).
- `no_data`/mismatch UX says only "we could not verify this address" — never reveals what the
  registry holds.

### F-P3 — the risk signal + policy gate
- `RiskService` order assessment gains signals: `addressMatchesRegistry`, `addressSource`,
  `firstDeviceOrder`. Ordering's `RiskClient` request grows the same fields (it already sends
  `verifiedIdentity`).
- Policy context gains `addressVerified` — demo rules: unverified address + device in basket →
  `riskLevel=high` → rule forces pickup point or holds the order (console risk pane shows the
  reason). Fail-open on registry outage, like the whole policy seam.
- Fulfilment: `shipping_order.address_source` column; Orders page shows "✓ registered address"
  on the shipment.

### F-P4 — lifecycle + sync (the real-world tail)
- Real mode: Freg **hendelsesliste** (event feed) → monthly/streamed re-sync of moved customers
  (Telenor does monthly); demo: an on-demand "re-verify" button on the CSR 360.
- `PartyAddressVerifiedEvent` on the bus → **martech rule applies**: update insight
  `BssTraitListener` + `BSS_INSIGHT_TRAIT_TOPICS`, campaign listeners — `region` trait gets a
  `verified` provenance, movers re-home between region audiences off the same event.
- E2E suite `registry_address_test.js`: verify→ship-to-registered (green path), typed drop
  address + handset → risk gate forces pickup (fraud path), `no_data` persona degrades
  honestly, and a delivery country with **no registry bound** falls back to postal-wash +
  `addressVerified=unavailable` (the pluggability proof).

## 5. Honesty box

- The demo verifies against **mock-freg**; real Freg access needs an **approved application**
  to Skatteetaten (or a distributor contract) + a documented legitimate-interest assessment.
  The seam makes that config, not code — same claim discipline as carriers/PSP.
- Each country's registry has its **own access regime** (SPAR, CPR, DVV all differ in who may
  query and what comes back) — the adapter normalizes the *answer shape*, never the *legal
  basis*. Shipping an adapter is not shipping the right to use it; the per-country lookup log
  exists so each market can prove its own compliance.
- Registry match ≠ fraud-proof: it binds *identity→address*, not *card→identity* (that's the
  PSP's 3DS/BankID job). Layered posture: registry address + risk rules + pickup-ID.
- Protected addresses (kode 6/7): the register answers nothing, so must we — `no_data` renders
  identically to "not found". A CSR must not be able to tell the difference.
