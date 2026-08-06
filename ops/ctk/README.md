# TM Forum CTK runner

The official TM Forum Conformance Test Kits (CTKs) live in the `tmforum-rand`
GitHub org as Node-16 / newman-4 era Postman collections. Their bundled runner
mangles URLs and environments on modern Node; `runctk.py` fixes that: it points
a cloned CTK at a live component through the gateway, bakes in a Keycloak bearer
token, normalises the collection's URL objects, and runs it with a modern
newman — printing a real pass/fail summary.

## Prerequisites

- The stack running (`docker compose up -d`) and reachable at `localhost:8080`.
- `newman` on PATH: `npm install -g newman`.
- A CTK's Node deps once (for its `index.js` payload injector):
  `cd <CTK>/ctk && npm install jsonschema newman` (then reuse via `NODE_PATH`).

## Run one kit

```bash
# clone a kit (names: https://api.github.com/orgs/tmforum-rand/repos)
git clone https://github.com/tmforum-rand/CTK-TMF663-ShoppingCart.git

TOKEN=$(curl -s -X POST http://localhost:8085/realms/bss/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bss-demo -d username=demo -d password=demo \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

export NODE_PATH=$(npm root -g)     # so the kit's index.js finds jsonschema/newman
python3 runctk.py CTK-TMF663-ShoppingCart \
  http://localhost:8080/tmf-api/shoppingCart/v4/ "$TOKEN" auto
# → CTK-TMF663-ShoppingCart: requests 8/8 ok | assertions 132/132 ok | 0 failures
```

The 4th arg is the collection's base-URL variable name (`auto` detects it).

## Component → CTK → base path

| Component | CTK repo | base path |
|---|---|---|
| product-catalog | CTK-TMF620-ProductCatalog | `/tmf-api/productCatalogManagement/v4/` |
| product-ordering | CTK-TMF622-ProductOrdering | `/tmf-api/productOrderingManagement/v4/` |
| party-account | CTK-TMF632-Party | `/tmf-api/partyManagement/v4/` |
| product-inventory | CTK-TMF637-ProductInventory | `/tmf-api/productInventory/v4/` |
| party-account | CTK-TMF666-Account | `/tmf-api/accountManagement/v4/` |
| party-account | CTK-TMF669-PartyRole | `/tmf-api/partyRoleManagement/v4/` |
| shopping-cart | CTK-TMF663-ShoppingCart | `/tmf-api/shoppingCart/v4/` |
| party-interaction | CTK-TMF683-PartyInteraction | `/tmf-api/partyInteraction/v4/` |
| product-stock | CTK-TMF687-Stock | `/tmf-api/productStockManagement/v4/` |
| usage | CTK-TMF635-Usage | `/tmf-api/usageManagement/v4/` |
| payment | CTK-TMF676_Payment | `/tmf-api/paymentManagement/v4/` |
| communication | CTK-TMF681-Communication | `/tmf-api/communicationManagement/v4/` |
| billing | CTK-TMF678-CustomerBill | `/tmf-api/customerBillManagement/v4/` |
| usage (prepay facade) | CTK-TMF654-PrepayBalance | `/tmf-api/prepayBalanceManagement/v4/` |
| geographic-address | CTK-TMF674_GeographicSite | `/tmf-api/geographicSiteManagement/v4/` |
| service-orchestration | CTK-TMF639-ResourceInventory | `/tmf-api/resourceInventoryManagement/v4/` |
| assurance | CTK-TMF642-Alarm | `/tmf-api/alarmManagement/v4/` |

## R18-era kits (runctk-r18.py)

The older generation is a RAW Postman collection + environment (no
config.json/index.js). Run those with:

```bash
python3 runctk-r18.py <CTK-dir>/ctk/<collection>.json \
  http://localhost:8080/tmf-api/<base>/<version> "$TOKEN"
```

| Component | CTK repo | base |
|---|---|---|
| agreement | CTK-TMF668-PartnershipTypeManagement-R18-0 | `…/partnershipTypeManagement/v4` |
| agreement | CTK-TMF651-AgreementManagement-R18-0 | `…/agreementManagement/v4` |
| service-orchestration | CTK-TMF638-ServiceInventory | `…/serviceInventory/v4` |
| service-orchestration | CTK-TMF653-ServiceTest | `…/serviceTestManagement/v4` |
| service-orchestration | CTK-TMF641-ServiceOrdering | `…/serviceOrdering/v4` |
| assurance | CTK-TMF656-ServiceProblem | `…/serviceProblemManagement/v4` |
| trouble-ticket | CTK-TMF621-TroubleTicket-R18-0 | `…/troubleTicket/v4` |
| qualification | CTK-TMF645-ServiceQualification | `…/serviceQualificationManagement/v3` |
| qualification | CTK-TMF679-ProductOfferingQualification-R18.0 | `…/productOfferingQualification/v4` |

Note the TMF645 kit targets the **v3 task face** (the v4 resource is named
`checkServiceQualification`; the v3 dialect rides beside it, same engine).

## One-time kit prep

- **TMF674**: the `config.json` example payload's `place[0].id` must reference
  a STORED TMF673 address (`GET /geographicAddress?limit=1` and paste the id) —
  the face refuses free-text places by design.
- **TMF638**: the kit assumes a sandbox inventory — it captures the first two
  list rows and expects name/state filters to return exactly one. Seed two
  probe services with unique names, the newer one in a state nothing else uses:

  ```sql
  INSERT INTO service (id, tenant_id, href, name, state, service_order_id,
    owner_party_id, created_at, last_update) VALUES
   ('ctk-probe-a-<ts>','genalpha','/tmf-api/serviceInventory/v4/service/ctk-probe-a-<ts>',
    'CTK Probe Mobile A <ts>','active','ctk-probe-order-a','op-genalpha',now(),now()),
   ('ctk-probe-b-<ts>','genalpha','/tmf-api/serviceInventory/v4/service/ctk-probe-b-<ts>',
    'CTK Probe Mobile B <ts>','designed','ctk-probe-order-b','op-genalpha',
    now()+interval '1 second',now());
  ```

See [`../../docs/ctk-conformance.md`](../../docs/ctk-conformance.md) for the current scorecard.
