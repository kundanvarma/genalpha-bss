# Mobile wholesale / MVNE — hosting MVNOs on the network we run

*Design doc. The mobile sibling to the fibre open-access arc
([wholesale-open-access-plan.md](wholesale-open-access-plan.md)): sell **wholesale
mobile** access to virtual operators (MVNOs) and settle it usage-by-usage —
operator-to-operator, from CDRs, not per flat line.*

## 1. The opportunity

The fibre arc made the platform an **access seeker and provider** for fixed
broadband. Mobile is the other half of a converged wholesaler's book, and its
commercials are different in one decisive way: **fibre wholesale settles per flat
line; mobile wholesale settles per unit of traffic.** A host mobile network sells
capacity to MVNOs (Mobile Virtual Network Operators), the MVNO sells retail SIMs on
top, and every megabyte, minute and SMS the MVNO's subscribers burn is **rated at a
wholesale rate and settled back to the host**.

The platform is already, structurally, an **MVNE** — a Mobile Virtual Network
*Enabler*: a multi-tenant BSS an MVNO runs its whole retail operation on. What it
does **not** yet model is the wholesale relationship *underneath* that: the host↔MVNO
rate card, the IMSI/SIM range the host lends the MVNO, and the usage-based
settlement that turns the MVNO's CDRs into what it owes the host. This arc adds
exactly that layer, reusing the fibre arc's seeker/provider/settlement scaffolding.

Two roles, mirroring the fibre plan:

- **Wholesale seeker = the MVNO.** We (or a tenant) run a retail mobile brand on a
  host's radio network, buying wholesale capacity and owing the host per unit of use.
- **Wholesale provider = the host MNO / MVNE.** We are the network (or the enabler):
  we allocate IMSI ranges, publish wholesale rate cards, rate each MVNO's aggregate
  traffic, and invoice it wholesale — while the MVNO bills its own subscribers retail.

## 2. The taxonomy (research)

**MVNO tiers** — how much of the stack the virtual operator runs itself, from least
to most independent:

- **Reseller / branded reseller** — brands and sells; the host provides network,
  core *and* usually BSS. Lowest control, lowest opex.
- **Light MVNO (service provider)** — own BSS, marketing and care, own retail rating
  and often its own IMSI, but rides the host's **core network + charging (OCS)**. This
  is the natural shape for a tenant on *this* platform.
- **Full MVNO** — own core elements (HLR/HSS), own IMSI ranges and SIM, own numbering
  and roaming agreements; uses only the host's radio access. Most control, heaviest.
- **MVNE** — the enabler that sits *between* host MNO and MVNOs, providing the BSS/OSS
  platform the MVNOs run on. **genalpha-bss's multi-tenancy already is this** — the
  wholesale layer is what makes it an MVNE with settlement, not just multi-tenant SaaS.

**Wholesale commercial models** the rate card must express:

- **Per-unit** — a wholesale rate per MB / per minute / per SMS. The default.
- **Capacity / tiered** — a committed volume at a blended price, overage above it.
- **Revenue share** — the host takes a percentage of the MVNO's retail revenue.

**SIM / IMSI provisioning.** The IMSI is the subscriber identity on the SIM; a host
lends an MVNO an **IMSI range**. The modern shape is **GSMA RSP (Remote SIM
Provisioning, SGP.32)** — eSIM profiles pushed OTA, with multi-IMSI routing — rather
than batch physical-SIM files. We model IMSI-range *allocation* as a resource (like the
MSISDN pool) and keep real SIM personalisation / RSP behind a seam, the same honest
boundary the retail SIM layer already draws.

**Wholesale reconciliation / revenue assurance.** The point every MVNO platform stresses:
the wholesale settlement must **cross-check the MVNO's usage against what the host
billed** — reconcile CDRs to the settlement statement, flag discrepancies, prove the
numbers. So W-M4 is not just "sum the ledger and invoice"; it carries a reconciliation
view (rated wholesale units vs settled amount, anomalies surfaced), the same
receipts-first discipline the revenue subledger already applies.

**Standards, honestly.** Unlike fibre — where **MEF LSO Sonata** gives a clean
inter-operator ordering API — mobile wholesale has **no single equivalent**. There is no
TM Forum "MVNO wholesale" API; the platform reuses the standards it already runs —
**TMF635/677 usage** for CDR intake + rating, **TMF678 billing**, **TMF651 agreement**
for the wholesale terms, **TMF632/668** for the host/MVNO parties. Host↔MVNO deals are
bilateral rate cards plus **CDR-based settlement**; the nearest standardised cousin is
GSMA **TAP3 / BCE** for *roaming* settlement, a different domain. So the adapter targets
a **generic wholesale-settlement shape** (rate card in, reconciled CDR statement out),
not a named wire — and we say so.

- flolive, *What is an MVNE (2026)*; *5 MVNO types compared*
- Tridens, *MVNO Billing Software (2026)*; Spenza, *OSS/BSS Checklist for 2026 MVNO Launches*
- CelloIP, *MVNO BSS/OSS Platform Architecture*; Yozzo, *MVNO Wholesale Models*; GSMA RSP (SGP.32)

## 3. Where we stand today (honest audit)

**Mobile wholesale is not modeled** — but far more of the machinery already exists
than did for fibre, because mobile *usage* is a first-class citizen here.

| Capability | Status | Evidence / reuse |
|---|---|---|
| Wholesale party + agreement (host / MVNO roles) | ⚠️ pattern exists | fibre arc's TMF668 partnershipType + TMF651 agreement — add roles `hostMno` / `mvno` |
| Cross-tenant host↔seeker path | ✅ proven | fibre seeker↔provider cross-tenant via gateway + `X-Tenant-Id` (never around RLS) — reuse verbatim |
| Wholesale rate card | ⚠️ partial | fibre `WholesaleRateCardClient` is flat per-line; mobile needs **per-unit** (MB/min/SMS) + capacity/rev-share |
| **Usage mediation + rating** | ✅ real | `usage` (TMF635/677): CDR-shaped intake, rating, allowance meters — the retail engine; wholesale = a **second rating pass at wholesale rates** |
| OCS seam (real-time charging) | ✅ seam | `chargingSpecId` references an external OCS; a light MVNO rides the host's, a full MVNO its own — bring-your-own |
| SIM / MSISDN / ICCID resources | ✅ real | SOM already mints these per subscriber; add **IMSI-range allocation** to an MVNO |
| Usage-based wholesale settlement | ❌ absent | the genuinely new substance — aggregate an MVNO's rated CDRs per period → wholesale statement + invoice + COGS |
| Settlement → revenue GL | ✅ pattern | fibre `WholesaleEventListener` → `postWholesaleCogs` (DR COGS / CR payable) — reuse for mobile wholesale cost |
| Wholesale desk (portal) | ✅ pattern | `partner-console /partner` — extend with a mobile wholesale tab |

**The one-line difference from fibre:** fibre wholesale is a **flat monthly per-line**
settlement; mobile wholesale is **usage-metered** — so the new work is a
wholesale-rating pass over CDRs and a period settlement, not a new ordering protocol.

## 4. Target model

An MVNO's retail subscriber generates usage exactly as today. That usage is rated
**twice**: once at the MVNO's **retail** rates (the subscriber's bill, unchanged) and
once at the host's **wholesale** rates (what the MVNO owes the host). The wholesale
pass aggregates per MVNO per period into a settlement statement.

```
 MVNO retail subscriber
   │  usage (CDRs: data / voice / SMS)
   ▼
 usage (TMF635/677) ──retail rating──▶ subscriber bill        (exists)
   │
   └──WHOLESALE rating (host rate card)──▶ wholesale usage ledger
                                              │  per MVNO, per period
                                              ▼
                                   wholesale settlement statement
                                     │              │
                          MVNO owes host    revenue GL: DR mobile-wholesale-COGS
                          (partner portal)              CR AP-wholesale (host side: AR)
```

- **Product** = an MVNO tier (reseller / light / full) as a wholesale offering, its
  rate card as config-as-data (per-unit + optional capacity/rev-share).
- **Resource** = an IMSI range allocated to the MVNO (modeled, like MSISDN pools).
- **Settlement** = a periodic wholesale-rating pass; the host invoices the MVNO, the
  MVNO books the cost (COGS), both sides visible in their own tenant.

## 5. Phases (each built + proven, mirroring the fibre M-arc)

- **W-M1 — Parties, tiers, agreements.** Host MNO + MVNO as parties; TMF668
  partnershipType roles `hostMno`/`mvno`; the MVNO tier (reseller/light/full) as a
  wholesale product in a "Wholesale mobile" category (shop-excluded). *Reuse fibre W1/W2.*
- **W-M2 — Wholesale rate card + IMSI allocation.** Per-unit rate card (MB/min/SMS)
  as config-as-data, with capacity/rev-share variants; allocate an IMSI range to the
  MVNO (a pool resource beside MSISDN). *Extends `WholesaleRateCardClient`.*
- **W-M3 — Wholesale usage rating (the core).** A second rating pass in `usage` that
  rates an MVNO's CDRs at the host's wholesale rates into a **wholesale usage ledger**,
  keyed by MVNO + period. Idempotent, replay-safe (the ledger is the checkpoint).
- **W-M4 — Settlement, reconciliation + books.** Aggregate the ledger per period → a
  wholesale settlement statement **with a reconciliation view** (rated wholesale units
  vs settled amount, discrepancies flagged — revenue assurance, not just a sum); the
  host invoices the MVNO; the MVNO books COGS (DR mobile-wholesale-COGS / CR AP), the
  host books the receivable — reusing the fibre `WholesaleEventListener` → revenue-GL
  pattern. Cross-tenant host↔MVNO like fibre.
- **W-M5 — The desks.** Partner-console gains a **mobile wholesale** view (the MVNO:
  my traffic this period vs what I owe; statements). The host/MVNE gets a provider
  view (my MVNOs, their aggregate traffic, what each owes). *Extends `partner-console`.*
- **W-M6 — Suite + proof.** `ops/e2e/mobile_wholesale_test.js`: an MVNO tenant's
  subscriber burns data → retail bill unchanged → wholesale ledger rates the same CDRs
  at host rates → period settlement → the MVNO owes the host, booked both sides,
  cross-tenant. Re-prove the retail path is untouched.

## 6. What it is NOT (honest boundaries)

- **Not a real mobile core.** No HLR/HSS, no SIM personalisation, no radio. IMSI
  allocation is a **modeled resource** (like the MSISDN pool), not a provisioned
  network element — the network/OSS layer stays out of scope, as with fibre's BNG.
- **Not GSMA TAP3/BCE roaming settlement.** That's inter-operator *roaming*, a
  separate domain; this is host↔MVNO wholesale, bilateral by nature.
- **Not a real-time wholesale OCS.** Real-time prepaid wholesale charging is the OCS
  seam's job; this arc does **periodic** CDR-based wholesale settlement (the standard
  postpaid-wholesale shape). A real-time wholesale meter is a later refinement.
- **The MVNO's retail side already works.** This arc adds only the layer *beneath* it —
  what the MVNO owes the host. Its subscribers, bills, SIMs and OCS charging are the
  existing retail BSS, unchanged.

## 7. Effort

Smaller than the fibre arc's net-new, because usage rating, the OCS seam, the
SIM/number resource layer, the cross-tenant path, and the settlement→GL pattern all
exist. The genuinely new substance is **W-M3 (wholesale-rate CDR pass) + W-M4
(usage-based settlement)**; the rest is reuse. Estimate ~1 week, one new E2E suite,
re-prove the retail suites.
