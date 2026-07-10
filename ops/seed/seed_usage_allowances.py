#!/usr/bin/env python3
"""Seed TMF635 usage allowances for the bundle (idempotent by spec name)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
USAGE = "http://localhost:8080/tmf-api/usageManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, url, body=None):
    r = urllib.request.Request(
        url,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


offerings = {o["name"]: o for o in req("GET", f"{CATALOG}/productOffering?limit=100")}

# offering name -> (usage type, allowance value, units, overage price per unit, currency)
ALLOWANCES = {
    "GenAlpha One Home & Mobile": ("EU roaming data", 10, "GB", 2.50, "EUR"),
}

existing = {(a["productOffering"]["id"], a["usageType"])
            for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}

for name, (usage_type, allowed, units, price, currency) in ALLOWANCES.items():
    offering = offerings.get(name)
    if not offering:
        print(f"SKIP missing offering: {name}")
        continue
    if (offering["id"], usage_type) in existing:
        print(f"exists: {name} / {usage_type}")
        continue
    created = req("POST", f"{USAGE}/usageAllowance", {
        "productOffering": {"id": offering["id"], "name": name, "@referredType": "ProductOffering"},
        "usageType": usage_type,
        "allowance": {"value": allowed, "units": units},
        "overagePrice": {"unit": currency, "value": price},
    })
    print(f"allowance: {name} / {usage_type} -> {allowed} {units} incl, {price} {currency}/{units} over")
print("done")
