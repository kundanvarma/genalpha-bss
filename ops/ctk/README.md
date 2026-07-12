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

See [`../../docs/ctk-conformance.md`](../../docs/ctk-conformance.md) for the current scorecard.
