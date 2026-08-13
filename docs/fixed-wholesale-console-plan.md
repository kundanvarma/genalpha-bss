# Fixed wholesale console — the owner's authoring desk

*Design doc. The fibre open-access arc
([wholesale-open-access-plan.md](wholesale-open-access-plan.md)) shipped the buyer's
side — a partner portal where an access seeker checks coverage and orders L2/L3.
This completes the **seller/owner** side: a console where an operator **authors and
manages the wholesale business as data** — owners, access products, footprint and
rate cards — instead of running seed scripts.*

## 1. The gap this closes

Today the wholesale supply side exists only as **seed scripts**:
- `seed_wholesale_partners.py` — the access owners (parties + TMF668 partnershipType
  + TMF651 agreement).
- `seed_wholesale_access_products.py` — the L2/L3 SKUs (a TMF620 offering + a spec
  carrying `accessLayer`/`accessOwner` + a price), in a shop-excluded "Wholesale
  access" category.
- `seed_wholesale_coverage.py` / `seed_novafibre_owner.py` — the footprint in the
  qualification `coverage_map` (owner + layer + postcode-prefix + bandwidth).

There is **no console surface** to create or change any of it, and the earlier arc
explicitly **deferred the console Coverage tab**. So an operator cannot onboard an
owner, publish a wholesale product, paint a footprint or set a wholesale rate without
editing Python. That is the hole: the *buyer* has a portal; the *seller* has a shell
prompt.

## 2. Research — the TM Forum way (and where we already sit)

- **TM Forum Wholesale Broadband Project** standardises *selling, buying and managing
  wholesale products*, fibre-first, for **B2B2X** markets — exactly this surface's
  problem. It leans on the catalog + qualification + agreement + ordering APIs rather
  than a bespoke wholesale schema.
- **TMF633 Service Catalog** (`ServiceSpecification`) and **TMF634 Resource Catalog**
  (`ResourceSpecification`) are the SID-correct home for a wholesale access product:
  a **CFS** (Customer-Facing Service — the access the seeker consumes) realised over
  an **RFS** (Resource-Facing Service — the owner's port/bitstream), with the sellable
  **TMF620 ProductOffering** *referencing* the service spec. Our fibre audit already
  flagged that we model fibre as a flat retail SKU with "no ServiceSpecification /
  ResourceSpecification entities" — this is where that debt gets paid, optionally.
- **Serviceability = MEF LSO Sonata**, whose qualification side is **MEF 79** (Address,
  Service Site, and **Product Offering Qualification**) plus **MEF 121** (Address
  Management). Our `queryAccessOptions` on the qualification service is a TMF645-shaped
  cousin of MEF 79's POQ — the coverage authoring should stay aligned to that shape so
  a real Sonata owner slots in.
- **TMF651 Agreement Management** models partnership terms as **agreement
  specifications** (templates) → agreements, machine-consumable into quoting and
  ordering. Our wholesale agreements already use TMF651 + TMF668; the console just
  needs to author them.

**Takeaway:** we do not invent a wholesale schema. The console authors into the
standard catalog / qualification / agreement APIs we already run — and we *optionally*
introduce the TMF633 CFS/RFS layer to model access "properly" and make it
lifecycle-ready, exactly as the retail bundle arc introduced configurable characteristics.

## 3. What exists / reuse

| Piece | Status | Reuse |
|---|---|---|
| Admin console with role-scoped tabs | ✅ | add a **Wholesale** tab (`apps/admin-console`), gated by a new `wholesale:admin` role |
| Catalog authoring (offering/spec/price) | ✅ generic | the Catalog tab already POSTs TMF620 — the Wholesale tab is a wholesale-*aware* view over the same API |
| Coverage map (owner+layer+prefix+bw) | ✅ data | `qualification` already stores + serves it (`queryAccessOptions`); needs write endpoints + a UI |
| Owners (party + partnershipType + agreement) | ✅ data | TMF632 + TMF668 + TMF651 already model them; needs authoring |
| Rate cards | ✅ client | `WholesaleRateCardClient` + `wholesaleSettlement` exist; needs an editable store + UI |
| Settlement views | ✅ endpoints | `wholesaleSettlement` (seeker) / `wholesaleProviderSettlement` (owner) exist; console renders them |
| Role administration (TMF672) | ✅ | grant/revoke `wholesale:admin` like every other staff area |

The one genuinely new data surface is **write access to the coverage map** (today it is
seed-only); everything else is a console view over an API that already exists.

## 4. Target — a "Wholesale" tab, four panes

A single role-gated tab in the admin console (`/console`, `wholesale:admin`), mirroring
how Catalog / Campaigns / Rules already work:

1. **Owners** — list/add an access owner: name, the partnership type (which roles it
   permits, TMF668), the TMF651 agreement + wholesale terms. This is the "who we buy
   from / sell as" registry.
2. **Access products** — author an L2/L3 wholesale SKU: pick owner + layer
   (L2-VULA / L3-activated) + bandwidth, set the wholesale price, publish into the
   "Wholesale access" category. *Option A (ship first):* a TMF620 offering + spec as
   today, just via a form. *Option B (the TMF-proper upgrade, phaseable):* the form
   also mints a **TMF633 CFS** (the access service) the offering references, so speed/
   layer become service characteristics and a tier change is a spec edit, not a new SKU.
3. **Coverage** — the deferred tab, finally built: paint footprint by owner × layer ×
   postcode-prefix × max bandwidth (longest-prefix-wins, the rule the engine already
   applies). A map/table editor over new `coverage_map` write endpoints, MEF 79-aligned.
4. **Settlement** — the owner's book: which retailers ordered what, aggregate per owner,
   what is owed and the margin — rendering the existing settlement endpoints, with CSV
   export for the finance desk (the pattern the revenue subledger already uses).

## 5. Phases (each built + proven)

- **FC1 — Coverage authoring (highest value).** `qualification` gains guarded write
  endpoints for the coverage map (`coverage:write` / `wholesale:admin`, RLS-scoped) +
  the console **Coverage** pane; the seed script becomes a first-run convenience, not
  the only door. Prove: paint a prefix live → `queryAccessOptions` reflects it → the
  partner portal shows the new footprint.
- **FC2 — Owners + agreements pane.** Author an access owner (party + TMF668
  partnershipType + TMF651 agreement) from a form; a roleless partnership is refused
  (the existing gate). Prove: onboard an owner in the console → it can back a product.
- **FC3 — Access-product authoring (Option A).** A wholesale-aware form over TMF620:
  owner + layer + bandwidth + price → offering in the "Wholesale access" category,
  shop-excluded. Prove: author an L2 and an L3 SKU → they appear in the partner portal's
  catalogue and are orderable over Sonata.
- **FC4 — Settlement pane + CSV.** Render `wholesaleProviderSettlement` per owner/period
  with margin, plus CSV export. Prove: after an order + activation, the owner's book
  shows the owed amount matching the ledger.
- **FC5 — (optional, TMF-proper) CFS/RFS modeling.** Introduce a **TMF633
  ServiceSpecification** (CFS) for wholesale access that the offering references, so
  layer/bandwidth are service characteristics and lifecycle changes are spec edits.
  Aligns to the TM Forum Wholesale Broadband model; decide with the operator whether the
  extra fidelity earns its keep before building.
- **FC6 — Suite + proof.** `ops/e2e/fixed_wholesale_console_test.js`: an operator with
  `wholesale:admin` onboards an owner, paints coverage, publishes an L2/L3 SKU and reads
  settlement — all in the browser — then a partner buys the just-authored product and
  the owner's book shows the margin. Re-prove the seed path still works (idempotent).

## 6. Boundaries (honest)

- **Not a second catalog.** The Wholesale tab writes into the *same* TMF620/633/645/651
  APIs the rest of the BSS uses — it is a wholesale-*shaped view*, not a parallel store.
- **Seeds don't die.** The seed scripts stay as idempotent first-run/demo convenience;
  the console becomes the day-2 authoring surface. (Same doctrine as every other seed.)
- **Coverage is the operator's word, not a network probe.** Painting a footprint is a
  commercial declaration of where an owner sells — not a live GIS/OSS lookup. A real
  MEF 79 address/site integration is a later seam, behind the same qualification face.
- **One new role, RLS throughout.** `wholesale:admin` gates every write; the coverage
  and product data stay tenant-isolated exactly like retail catalog data.

## 7. Effort

Small-to-medium. FC1 (coverage write + pane) is the only net-new data surface; FC2–FC4
are console views over endpoints that already exist. FC5 (CFS/RFS) is the one real
modeling investment and is optional. Estimate ~3–4 days for FC1–FC4 + the suite, plus
~2 days if FC5 is taken. **Recommendation: build FC1–FC4 + FC6 now, defer FC5** until
the operator confirms they want the CFS/RFS fidelity.
