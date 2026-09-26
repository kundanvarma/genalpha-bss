# The charging seam: who answers "how much data does this line have left, right now?"

## What the industry does

Real-time charging is a network function, not a billing one. 3GPP puts it in
the **Online Charging System** (OCS, TS 32.240/32.296): the packet core asks
it for quota over **Diameter Gy** (TS 32.299; Ro for IMS voice) before it lets
traffic flow, debits what was used, and cuts the session when the balance is
gone. 5G standalone renames the same job the **Converged Charging System**
(CHF, TS 32.290/32.291) behind the `Nchf_ConvergedCharging` service-based
interface. TM Forum's ODA places it in the Core Commerce / Production
boundary as **TMFC039 Balance Management** (the balance face, TMF654) with
rating and charging behind it, and the ODA component directory lists real OCS
products against exactly that shape.

Vendors converge on one operating model: the OCS owns the **rate plans**
(built in its own tooling), the **subscriber's counters**, the **reservation
and debit** arithmetic and the **rollover policy**; the BSS owns the
**lifecycle** (a line activates → a subscriber and its allowance must exist
there; a plan change swaps the rate plan; a hold pauses charging) and the
**customer conversation** (balance pages, top-ups, "running low"). Ericsson
Charging, Huawei CBS, Nokia CCS, Amdocs, Matrixx, Totogi, Mavenir CCS all
expose that split; the open-source world has one production-grade OCS in the
same shape — **SigScale OCS** (Apache-2.0, Erlang/OTP, Diameter Ro/Gy/Gx,
RADIUS, TM Forum APIs native) — and one with a VoIP-switch lineage, CGRateS
(AGPL-3.0, JSON-RPC).

## What genalpha-bss does

The BSS never sits in the charging path. It **references** charging from the
catalog and **provisions** it at activation:

- **Catalog.** A mobile plan's product specification carries
  `chargingSpecId` — the id of a rate plan that lives in the OCS. The catalog
  never contains the plan.
- **Orchestration (`service-orchestration`).** At activation the SOM calls
  the seam `OcsProvisioningClient`: provision (with the apps the plan
  zero-rates), change rate plan, suspend, resume, transfer. Fail-open by
  contract: an unreachable OCS is logged and reconciled by ops; activation is
  never blocked.
- **Usage (`usage`).** The TMF654 Prepay Balance facade *projects* the OCS's
  counters (remaining / used / rollover) and forwards top-up credits through
  `OcsClient`. The OCS's own "running low" line arrives at
  `/internal/ocs/…` and becomes the tenant-stamped
  `UsageThresholdBreachedEvent` the growth engine listens for.
- **The northbound door is signed.** An OCS holds no BSS token, so
  `/internal/ocs/**` takes a per-tenant shared secret
  (`ocs-notify-secret` in the tenant fleet file) the way a PSP webhook does:
  `x-ocs-signature: t=<epoch ms>,v1=<base64url>` over `"<t>.<raw body>"`,
  HMAC-SHA256, constant-time, refused outside a five-minute window. The
  tenant the body names is believed *because* the body verifies against that
  tenant's own secret; an operator with no secret has a shut door.

**Routed per tenant.** Both services resolve the OCS from the tenant fleet
file (`infra/tenants/tenants.yml`): `ocs-provider`, `ocs-base-url`,
`ocs-username`, `ocs-password` per operator, deployment defaults
(`OCS_PROVIDER`, `OCS_BASE_URL`, …) for the rest. A registry of named
adapters (`OcsProviderAdapter` / `OcsBalanceAdapter`) sits behind one
`@Primary` router in each service, the same shape the CMS seam uses — one
fleet can charge tenant A on the bundled mock, tenant B on SigScale and
tenant C on a vendor gateway. Two adapters ship:

| adapter | speaks | stands in for |
|---|---|---|
| `http` | the subscriber / rate-plan / bucket REST shape of `integrations/mock-ocs` | a vendor's integration gateway in front of Ericsson / Huawei / Matrixx |
| `sigscale` | SigScale OCS's own TM Forum APIs | a real, open-source, 3GPP Diameter OCS — bundled in the fleet |

**The SigScale adapter, concretely.**

| BSS intent | SigScale call | note |
|---|---|---|
| rate plan exists | `GET /productCatalogManagement/v2/productOffering/{chargingSpecId}` | the offering IS the rate plan: a monthly recurring price whose *alteration* grants the allowance, a usage price that rates overage. Seeded by the OCS's own tooling (`ops/seed/seed_sigscale_ocs.py`); missing = logged, not invented |
| provision | `POST /productInventoryManagement/v2/product` on the offering, then `POST /serviceInventoryManagement/v2/service` with `id` = the line's MSISDN and `product` = the new product | SigScale creates the allowance bucket the moment the product exists and re-grants it monthly. Our tenant / party / service ids and the plan allowance ride as product characteristics |
| balance | `GET …/product/{id}` → `balance[octets].totalBalance` | one projected bucket per product (id = product id); *used = granted − remaining*, granted = plan allowance + TMF654 top-ups the facade recorded |
| top-up | `POST /balanceManagement/v1/product/{id}/balanceTopup` (octets) | |
| plan change / transfer | new product on the new offering, service `product` re-pointed by JSON Patch, old product deleted | SigScale products are **never patched** — a patch rewrites the record and drops its bucket links (verified 3.4.73). Transfer carries the remaining octets onto the new product |
| hold / resume | JSON Patch `isServiceEnabled` on the service | Gy refuses a disabled identity |
| running low | usage subscribes each SigScale-bound tenant's **TMF654 balance hub** at boot (`callback` = `/internal/ocs/sigscale/{tenant}/{token}` — a TMF hub registration is a bare URL with nowhere to put a header, so the credential is the last segment, derived from that tenant's `ocs-notify-secret` and never logged — `query` = below `OCS_THRESHOLD_BYTES`) | SigScale posts `AccumulatedBalanceCreationNotification` while a product sits under the line; relayed once per low episode, re-armed when the balance climbs back |

**The bundled OCS.** `integrations/sigscale-ocs` builds SigScale OCS 3.4.x
from the upstream release files on Debian's own Erlang/OTP packages — the
upstream image is amd64-only and its JIT segfaults under emulation on
Apple-silicon hosts — with the two EAP native libraries rebuilt from
SigScale's sources. The entrypoint initialises the mnesia tables on first
boot, bootstraps the REST admin user, registers the Diameter clients named
in `OCS_DIAMETER_CLIENTS` and sets the OCS's running-low line. Port 8155 is
its REST + GUI (HTTP Basic), 3868 its Diameter door. Keep `hostname:`
fixed: the Erlang node is named after it and the database is bound to that
name.

**Proof.** `ops/e2e/sigscale_ocs_test.js` (#123) on the Taranga tenant:
order → SigScale product on the plan's rate plan with a 60 GB allowance and
the line's MSISDN as the charging identity → TMF654 shows 60 GB → a **real
3GPP Gy credit-control session** (SigScale's own Diameter test client,
CCR-I/U/T, Result-Code 2001) debits the balance → TMF654 shows the usage →
a TMF654 top-up lands as a bucket → the balance is driven under the OCS's
2 GB line and its hub fires the tenant's *transactional* "running low"
journey → TMF622 modify moves the product to the new rate plan and retires
the old one → vacation hold disables the identity, resume re-enables it →
the default tenant, on the mock, sees none of it. The four mock-OCS suites
(`ocs_test`, `journey_ocs_threshold_test`, `slice_boost_test`,
`diagnostics_test`) prove the `http` adapter unchanged.

**A side-effect worth its own line.** "Running low" is a service notice, not
marketing. Journeys now carry a `category`: a `transactional` journey is
never parked by marketing quiet hours, never spends the marketing frequency
budget, and its messages reach communication stamped `category:
transactional` (no marketing footer). Opt-outs still apply per channel.

## Adding a third: an operator who already runs Matrixx or CSG

The seam is the stable part. `OcsSeam` never changes, no product changes, no
other tenant is touched, and nothing in the catalog moves — a product names the
*step*, never the vendor. Two cases, in order of cost.

### Case A — no code at all

Most vendor deployments sit behind an integration gateway that already speaks
the plain subscriber / rate-plan REST shape. If it does, the shipped `http`
adapter is the adapter, and onboarding is four lines in `infra/tenants/tenants.yml`:

```yaml
        ocs-provider: ${OCS_PROVIDER_NORDFONE:http}
        ocs-base-url: ${OCS_BASE_URL_NORDFONE:https://ocs-gw.internal.nordfone.example}
        ocs-username: ${OCS_USERNAME_NORDFONE:bss}
        ocs-password: ${OCS_PASSWORD_NORDFONE:change-me-in-the-secret-store}
```

Credentials are environment overrides on purpose: the file ships the shape, the
secret store ships the value. **Try this first.** Writing an adapter for an API
the generic one already speaks is work nobody needs.

### Case B — one class

When the vendor's API is its own shape, you write one adapter. It implements
`OcsProviderAdapter`, declares the name the tenant file selects, and translates
six intents. Spring finds it by annotation; nothing registers it by hand.

```java
@Component
public class MatrixxOcsProvisioningClient implements OcsProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(MatrixxOcsProvisioningClient.class);

    private final RestClient.Builder builder;
    private final OcsSettings settings;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public MatrixxOcsProvisioningClient(RestClient.Builder builder, OcsSettings settings) {
        this.builder = builder;
        this.settings = settings;
    }

    /** The name tenants.yml selects: ocs-provider: matrixx */
    @Override
    public String name() {
        return "matrixx";
    }

    /** No base URL for this tenant = no charging here; every call a logged no-op. */
    @Override
    public boolean enabledFor(String tenantId) {
        return settings.forTenant(tenantId).enabled();
    }

    private RestClient client(String tenantId) {
        OcsSettings.Binding b = settings.forTenant(tenantId);
        return b.enabled() ? clients.computeIfAbsent(b.baseUrl(),
                url -> builder.clone().baseUrl(url).build()) : null;
    }

    @Override
    public void provision(String tenantId, String partyId, String serviceId, String chargingSpecId,
            List<String> zeroRatedApps) {
        RestClient rc = client(tenantId);
        if (rc == null) {
            return;
        }
        try {
            // whatever this vendor calls a subscriber on a rate plan
            rc.post().uri("/v1/subscriptions")
                    .header("Content-Type", "application/json")
                    .body(Map.of("externalId", serviceId, "owner", partyId, "offer", chargingSpecId))
                    .retrieve().toBodilessEntity();
            log.info("OCS(matrixx): subscription for service {} on offer {}", serviceId, chargingSpecId);
        } catch (RuntimeException e) {
            // fail open: activation is not held hostage by charging; reconcile later
            log.warn("OCS(matrixx) provisioning failed for service {} ({}) — activation proceeds",
                    serviceId, e.getMessage());
        }
    }

    // provision(…4 args), changeRatePlan, suspend, resume, transfer — the same shape.
    // pushOverageTiers has a default: override it only if this OCS holds tiers.
}
```

Then the tenant file selects it:

```yaml
        ocs-provider: ${OCS_PROVIDER_NORDFONE:matrixx}
        ocs-base-url: ${OCS_BASE_URL_NORDFONE:https://matrixx.internal.nordfone.example}
```

### The third thing, which is not optional

**A stand-in in the fleet.** A seam without one cannot be run by a developer or
a browser suite, and an integration nobody can exercise is an integration
nobody can trust. Either point the tenant's dev binding at the bundled
`integrations/mock-ocs`, or add a mock of the vendor's own shape beside it.
This is why `sigscale-ocs` is in the compose fleet rather than a diagram.

### Six intents, and that is the whole contract

| BSS intent | when |
|---|---|
| `provision` | a line activates |
| `changeRatePlan` | an upgrade or downgrade |
| `pushOverageTiers` | a spec carries usage tiers (has a default — skip it if the OCS does not hold tiers) |
| `suspend` / `resume` | a pause, or a collections hold lifted |
| `transfer` | the line moves to a new owner |

If a vendor is expected to do something outside that set — balance enquiry,
policy control — the interface grows, and that is a wider change than one
class.

### What it costs the second time

Nothing. Adapters are keyed by **vendor**, not by customer: the next operator
already running Matrixx sets one configuration line and writes no code. That is
the property that makes integration work converge instead of multiply — and the
reason an adapter is never rewritten for a new customer.

## What is deliberately not here

- **5G Nchf / CHF.** SigScale ships a separate CHF (`sigscale/chf`, thin as of
  2026) fronting the OCS over its `Nrf_Rating` Re interface; no open-source
  CHF is production-grade today. The seam does not change — the BSS talks TM
  Forum to whatever charges — so a commercial CHF or SigScale's, when it
  matures, is the same adapter. Open5GS has no CHF and its 5G-SA SMF does not
  speak Gy; 4G/EPC and 5G NSA are where the bundled OCS is real.
- **Zero-rating and priority slices** are rating-group rules in the OCS's
  tariff, referenced by the offering; the adapter passes the app list along
  as a characteristic and the `-PRIO` rate plans exist, but the tariff rows
  are the operator's OCS work.
- **Voice / SMS** rate plans and the CGRateS adapter (VoIP-switch shops:
  Kamailio / FreeSWITCH / Asterisk) — same registry, one more adapter when a
  prospect's voice platform asks for it.
- **A per-party index in SigScale.** Its characteristic filter matches on
  presence, not value, so the balance face pages the inventory and matches
  our ids itself — right at operator scale, an index at millions of lines.
- **CDR export into billing** from SigScale's IPDR/CDR logs — the usage
  component already rates its own CDR feed; wiring SigScale's export is a
  mediation task, not a seam change.
