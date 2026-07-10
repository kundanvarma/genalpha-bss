#!/usr/bin/env python3
"""Link the seeded GenAlpha prices to their offerings via productOfferingPrice refs."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    req = urllib.request.Request(
        f"{API}/{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(req) as r:
        return json.load(r)


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}

LINKS = {
    "GenAlpha Mobile Unlimited 5G": ["Mobile Unlimited 5G Monthly"],
    "Apple iPhone 17 Pro 256GB": ["iPhone 17 Pro Installment (24 months)"],
    "GenAlpha Fiber 1000": ["Fiber 1000 Monthly", "Fiber Installation Fee"],
    "GenAlpha TV Max": ["TV Max Monthly"],
    "GenAlpha One Home & Mobile": [
        "Mobile Unlimited 5G Monthly", "Fiber 1000 Monthly", "TV Max Monthly",
        "GenAlpha One Bundle Discount", "Fiber Installation Fee",
    ],
}

for offering_name, price_names in LINKS.items():
    offering = offerings.get(offering_name)
    if not offering:
        print(f"SKIP missing offering: {offering_name}")
        continue
    refs = []
    for name in price_names:
        p = prices.get(name)
        if not p:
            print(f"SKIP missing price: {name}")
            continue
        refs.append({"id": p["id"], "href": p.get("href"), "name": p["name"],
                     "@referredType": "ProductOfferingPrice"})
    req("PATCH", f"productOffering/{offering['id']}", {"productOfferingPrice": refs})
    print(f"linked {offering_name} -> {len(refs)} price(s)")
print("done")
