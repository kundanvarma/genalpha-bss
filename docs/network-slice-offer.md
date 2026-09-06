# Network slicing as a product — the boost pass and the priority tier

On a 5G standalone core, which slice a subscriber's traffic rides is a policy
decision in the core (3GPP PCF and NSSF, or a vendor's slice manager: Mavenir,
Ericsson, Nokia, Samsung). Charging is a different system: the online charging
system (Mavenir CCS, Matrixx, Amdocs, CSG, Ericsson, Huawei) rates the traffic
wherever it rides. So the BSS talks to two seams, and never confuses them:

```
order ──▶ SOM ──▶ OcsProvisioningClient   (rate plan, buckets)     — money
              └─▶ SliceProvisioningClient (profile, until)         — priority
```

## Three commercial shapes, one mechanism

The catalog declares slice intent on the product specification:

| Characteristic | Meaning |
|---|---|
| `sliceProfile` | the core's profile name (`priority`, `gaming`, `live-video` …) |
| `boostHours` | present = a time-boxed pass; absent = as long as the plan lasts |

- **Priority tier** — a mobile plan whose spec names a `sliceProfile`. The line rides it from activation, open-ended.
- **Boost pass** — a Top-ups offering with `sliceProfile` + `boostHours`. Buying it puts the customer's active mobile line on the profile for those hours; a second pass extends from the current expiry; the same order re-delivered never doubles the window.
- **Monthly add-on** — the tier mechanism on an add-on offering (same code path, no expiry).

## What happens

1. The order completes; the SOM reads the offering's slice intent from the catalog.
2. `SliceProvisioningClient.apply(tenant, serviceId, profile, until)` tells the core. Fail-open: an unreachable core never blocks the order; the record still carries the intent and the expiry sweep re-applies.
3. The TMF638 service record carries `sliceProfile` and `sliceUntil` as characteristics; the storefront shows a ⚡ Priority badge with the expiry in the tenant's zone; CSR sees the same record.
4. `ServiceSliceChangeEvent` rides the bus on apply and on lapse, so a journey can say "your boost is on" or "your boost ended — buy another".
5. The sweep (`bss.som.slice-tick-ms`) releases lapsed passes; the core reverts on its own clock regardless.

## The seam

`SLICE_BASE_URL` blank = no slicing in this deployment; every call is a logged no-op. The dev stand-in `mock-5gc` (:8154) knows four profiles with S-NSSAI and QoS shapes (`GET /profiles`), applies `PUT /subscribers/{id}/slice {profile, until}`, and expires on its own clock. A production adapter implements the same four calls against the operator's PCF / slice manager API.

## What the standards and the market say (checked 2026-09-06)

- **Who decides, who rates.** GSMA NG.116 (v10, 2024): a device may use a slice when its S-NSSAI is in the subscriber's subscription data (UDM/UDR); the PCF sets QoS and URSP policy (TS 29.512), the NSSF selects the slice (TS 23.501), and the charging function rates it over Nchf (TS 32.291), slice-aware through CEF/NWDAF triggers. That is exactly the split above: the BSS states intent to the core's policy side and provisions the charging side separately.
- **Live consumer products.** VodafoneThree "SuperMobile" (UK, Sep 2026): slicing on 5G SA sold as a £3 to £12 monthly uplift or add-on with a 15 Mbps guarantee. EE "Fast Lane" £5 a month. Singtel "5G+ Priority": a 48-hour Priority Pass at S$5 or S$19.90 monthly — the time-boxed pass shape this arc ships. Orange FR, OTE and WindTre sell equivalents. T-Mobile and Verizon run first-responder slices. Verify current prices before quoting.
- **Mavenir.** Its Converged Charging System is a 3GPP CHF (Nchf, Diameter, Release 15/16 charging models) with "network slice charging" via CEF and NWDAF triggers, tenants for slicing, and REST OpenAPIs northbound; Mavenir Digital Enablement holds TM Forum Platinum Open API status. Its packet core lists PCF, UDM and NSSF. No public per-subscriber slice-assignment API is documented, and whether a given operator's core is 5G SA is not public — ask.
- **Other charging systems** behind the same seam: Amdocs Charging (which absorbed Matrixx in January 2026), CSG Ascendon with CHF support in its mediation layer, Ericsson Charging, Huawei CBS.

## Slice-aware charging, a measured guarantee, device eligibility

- **Charging.** The spec's `sliceChargingSpecId` names the OCS rate plan the line rides while on the slice. At apply the SOM moves the subscriber there (`changeRatePlan`) and remembers the base plan; at lapse it moves it back. The OCS never hears the word slice, only a plan — exactly how a CHF works, where the S-NSSAI on each charging request selects the rating group. GB that ride the slice come back through the OCS's northbound door (`/internal/ocs/priorityUsage`) and are rated as a "Priority data" uplift line, `gb × upliftPerGb`, so a pass can carry a per-GB price beside its one-time price. `mock-ocs` ships `RG-DATA-60-PRIO`, `RG-DATA-50-PRIO`, `RG-UNL-PRIO`.
- **A sold guarantee is measured.** `guaranteedDlMbps` on the spec rides to the line; a sweep (`bss.som.slice-assurance-tick-ms`) reads the core's delivered KPI (`/subscribers/{id}/quality`, a stand-in for NWDAF / slice assurance) and, on a shortfall, declares a TMF656 service problem on the line, once per window. The assurance component's SLA ledger takes it from there: a pre-agreed credit note against the customer's agreement, capped per month. Selling "25 Mbit/s guaranteed" without this is a claim, not a product.
- **Device eligibility.** Only 5G standalone handsets ride a slice. The SOM records the model the network last saw on the line (`DeviceDetectedEvent` from the EIR seam) and refuses a pass on a non-capable device with a `ServiceSliceRefusedEvent` — a journey can tell the customer why, and the pass is not sold silence. The capability rule is a conservative built-in (iPhone 12+, Galaxy S21+, Pixel 6+, anything self-declared 5G); production reads the operator's device database.

## Not built

Per-app slice selection (URSP delivery to the handset), wholesale slice-as-a-service, the operator's real core adapter (Mavenir publishes no per-subscriber slice API — a joint task), a device database beyond the built-in rule. Verify any competitor's product name and price before quoting it.
