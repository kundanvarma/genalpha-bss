# Guyana — market brief for operating this BSS there

Researched 2026-09-02 from public sources (regulator publications, ITU, operator sites,
national press). Facts marked *uncertain* could not be confirmed from a primary source.
This is a country brief; it names operators and institutions, never individuals.

## What is different from the Nordic reference market

| Topic | Norway (reference) | Guyana | What the platform does about it |
|---|---|---|---|
| Population register | Folkeregisteret, queryable | None. Civil registration is paper-based at the General Register Office; no third-party API | Registry seam stays unbound for GY → address verification honestly answers `unavailable` |
| e-ID | BankID | Digital Identity Card Act 2023 in force since 2026-03-31; Veridos chip card with a lifelong GIN; 36,500 enrolled by Aug 2026 (target 200,000); a validation API is promised, not yet a login | No step-up identity for GY offerings; ID capture at SIM sale is a manual/ID-document norm |
| SIM sale | Self-service, eSIM | Government photo ID recorded at sale (national ID, passport, driver's licence); eSIM offered by all three operators | eSIM remains the default; physical SIM = store collection |
| Postcodes | 4 digits, universal | Guyana Post Office 7-digit code (region, locality, district, post office, sub-locality), adoption still low | Country-specific address form: *Lot and street*, *village/town*, *region* select, GPOC postcode with a lookup hint; `\d{7}` rule |
| Written address | Street + number | "Lot 12 Camp Street, Georgetown"; villages with area descriptors (East Bank Demerara), landmarks; couriers use phone + WhatsApp pins | Region field (informational); postcode region digit drives serviceability (4 = Georgetown/Demerara, 7 = Bartica) |
| Currency | NOK with øre | GYD, coins $1/$5/$10, cents withdrawn 1992 → whole-dollar prices; written "$", "G$", "GY$" | Tenant `price-decimals: 0`, `currency-display: narrowSymbol`, Intl locale `en-GY` |
| VAT | 25%, prices incl. | 14% standard; **residential internet data zero-rated**; handsets exempt; voice taxable; VAT Act s.90 requires advertised prices to be tax-inclusive with a "VAT included" statement | Tenant default 14% (`tax` posting mapping) plus **per-price VAT** the standard way: TMF620 `productOfferingPrice.tax[].taxRate` → TMF678 `appliedTax` on the bill line → per-line posting in the ledger; fibre monthly prices carry 0, everything else 14; a statutory `price-note` beside prices |
| Number plan | +47, 8 digits | +592, closed 7-digit, no area codes; mobile ranges span 6xx **and** 7xx (ENet: 635, 710–720, 730–742, 760–767) | Number pool on an ENet range; porting rule `^\+592[67]\d{6}$` |
| Number portability | NRDB, next day | Live since 2025-02-10 (PUC), clearinghouse Porting XS; free; mobile ≤1 business day, fixed ≤5; needs photo ID + latest bill; 14-day port-back window, then 60 days minimum | Clearinghouse chosen by the TENANT (`porting-gateway: portingxs` / `nrdb`), then by the number's country, then the deployment fallback — never one global switch; shopper copy carries the PUC conditions |
| Payments | Vipps, Klarna, card, AvtaleGiro, eFaktura | Cash and agents dominate; **MMG** mobile wallet (works on every network, 3,195 agents); SurePay and Bill Express aggregators; bank online bill-pay and standing orders; Fast Pay real-time bank transfers since 2026-06; **Stripe and PayPal do not serve Guyana**; card penetration low | Card (bank acquiring) + **MMG wallet adapter**; PayPal removed from the tenant; pay-at-agent is a follow-up |
| Bills | EHF/Peppol e-invoice | No e-invoicing mandate; GRA "Tax Invoice" fields (VAT reg. no., serial, VAT, total); in-app/e-bill with paper opt-in; utilities disconnect after due date; reconnection fees common (ENet: none) | Bill distribution off (in-app), dunning policy row for GY/GYD |
| Delivery | Posten/Bring, Helthjem, pickup points | No operator home-delivers SIMs or phones; store/dealer collection is the rail; same-day couriers are WhatsApp-dispatched with cash-or-MMG on delivery; hinterland by boat/air, days to weeks, seasonal | Carrier seam = operator stores as pickup points (region-filtered) + courier; delivery tiers per postcode prefix in the carrier config (`etaByPrefix`): Georgetown same day, coast 1–2 days, Linden 2–3, interior regions 5–10 days by boat or air |
| Installation | Technician visit, 2h windows | Fibre install 3–7 days coastal (One: ≤72 h free; Digicel: G$20,000 often waived); coverage checks via WhatsApp location pin | Per-tenant calendar in America/Guyana, Mon–Sat, roster-derived capacity; field-service seam for the operator's own workforce system |
| Power | Stable | Grid-wide blackouts through 2025–26; GPL SAIFI 86 / SAIDI 84 min (2025); Georgetown floods | Expect "no service" tickets that are power; an ONT Battery Backup device (stocked, one-time, demo price) sits in the catalog |
| Support channel | Email/app | WhatsApp is the standard institutional channel (utilities, banks, operators) | WhatsApp channel is a follow-up in the communication service |
| Data protection | GDPR | Data Protection Act 2023 assented, **not commenced** as of mid-2026; Commissioner appointed | Consent norms kept; nothing to relax |
| Regulator | Nkom | Telecommunications Agency (licences, spectrum, numbering) + Public Utilities Commission (tariffs, QoS, complaints, MNP); Telecommunications Act 2016 in force 2020-10-05; Pricing Regulations 2020 (plans notified to PUC); complaint path operator → PUC (21+10 days) | Nothing to build; informs dunning and terms copy |

## Market snapshot (2025–26)

- Operators: One Communications (ex-GTT; ATN 80 % / Government 20 %; owns copper and the main fibre), Digicel Guyana, ENet (locally owned; FTTH + first 4G/5G VoLTE network 2023; Guyana–Barbados cable; Bartica submarine link 2025), Starlink residential since 2025-04. No MVNOs.
- Mobile subscriptions passed 1,000,000 in 2025 (84 % penetration); fixed broadband above 130,000; median fixed speed ~95 Mbps.
- Typical prices: prepaid 30-day mobile plans G$3,500–7,000; fibre 300 Mbps G$8,900–11,400; TV packages G$3,600–6,000. Prepaid dominates; postpaid migration under way.
- Hinterland (Regions 1, 7, 8, 9) is served mostly by LEO satellite and government ICT hubs.

## Sources
Telecommunications Agency numbering notice in ITU Operational Bulletin 1336 (2026-02); PUC number-portability FAQ (2025-02) and approved-rates schedule (2024-12); GRA VAT Policy 26 and VAT-inclusive-pricing policy; Guyana Post Office postcode explainer and UPU addressing sheet (2025-08); e-id.gov.gy and press on the Digital Identity Card Act; Bank of Guyana NPS FAQ and Fast Pay launch coverage (2026-05/06); MMG FAQs and business pages; operator sites (One, Digicel, ENet) for plans, stores and installation terms; DataReportal Digital 2025 Guyana; GPL reliability reporting (2026-06).
