#!/usr/bin/env python3
"""Seed TMF687 stock for the three phones (idempotent by offering id)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
STOCK = "http://localhost:8080/tmf-api/productStockManagement/v4"


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

LEVELS = {
    "Apple iPhone 17 Pro": 25,
    "Apple iPhone 17": 40,
    "Samsung Galaxy S26": 3,
}

for name, amount in LEVELS.items():
    offering = offerings.get(name)
    if not offering:
        print(f"SKIP missing offering: {name}")
        continue
    existing = req("GET", f"{STOCK}/productStock?productOfferingId={offering['id']}")
    if existing:
        print(f"exists: {name} (stocked {existing[0]['stockedQuantity']['amount']})")
        continue
    created = req("POST", f"{STOCK}/productStock", {
        "name": f"{name} stock",
        "productOffering": {"id": offering["id"], "name": name, "@referredType": "ProductOffering"},
        "stockedQuantity": {"amount": amount, "units": "unit"},
    })
    print(f"stocked: {name} -> {created['availableQuantity']['amount']} available")
print("done")
