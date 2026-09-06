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

## Not built

Skills-based slice selection per app (a gaming slice only for gaming traffic), slice SLAs and assurance metrics, wholesale slice-as-a-service. Verify any competitor's product name and price before quoting it.
