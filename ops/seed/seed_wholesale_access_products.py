#!/usr/bin/env python3
"""W2 — the wholesale access product model (L2/L3), distinct from the retail SKU.

In an open-access world a retail broadband product is REALIZED OVER a wholesale
access input bought from an owner. This seeds that input as its own catalog product,
one per owner, with the access LAYER as a first-class fact:

  • NordAccess  — L3 activated bitstream, up to 1000 Mbit/s  (we resell)
  • FjordFiber  — L2 VULA / bitstream,   up to  500 Mbit/s  (we run our own IP)

These live in a "Wholesale access" category the consumer shop does not render (its
grid only lists retail categories), so they never leak to shoppers. The retail
"Fiber Broadband 1000" spec is marked as delivered-over-access, so ordering knows a
fibre sale must be realized upstream. Idempotent; file+live.
"""
import json, os, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
CAT = "/tmf-api/productCatalogManagement/v4"


def tok():
    data = "grant_type=password&client_id=bss-demo&username=demo&password=demo".encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC, data=data)))["access_token"]


T = tok()


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Authorization": "Bearer " + T}
    if data:
        h["Content-Type"] = "application/json"
    r = urllib.request.Request(API + path, data=data, headers=h, method=method)
    try:
        return json.load(urllib.request.urlopen(r))
    except urllib.error.HTTPError as e:
        print("ERR", method, path, e.code, e.read().decode()[:300]); raise


def by_name(kind, name):
    for x in req("GET", f"{CAT}/{kind}?limit=100"):
        if x.get("name") == name:
            return x
    return None


def fact(name, value):
    """A non-configurable characteristic — a fact about the access, not a picker."""
    return {"name": name, "configurable": False,
            "productSpecCharacteristicValue": [{"value": value}]}


def ensure_category(name):
    c = by_name("category", name)
    if c:
        return c
    c = req("POST", f"{CAT}/category", {"name": name, "lifecycleStatus": "Active"})
    print(f"category: {name}")
    return c


def ensure_access_product(owner_name, owner_code, layer, bandwidth, rate, cat):
    spec_name = f"{owner_name} Wholesale Access"
    spec = by_name("productSpecification", spec_name)
    if not spec:
        spec = req("POST", f"{CAT}/productSpecification", {
            "name": spec_name, "brand": owner_name, "lifecycleStatus": "Active",
            "productSpecCharacteristic": [
                fact("accessLayer", layer),        # L2-VULA | L3-activated
                fact("accessOwner", owner_code),
                fact("maxDownMbps", bandwidth),
            ]})
        print(f"spec: {spec_name} (layer={layer}, owner={owner_code}, {bandwidth} Mbit/s)")
    price_name = f"{owner_name} wholesale per line"
    price = by_name("productOfferingPrice", price_name)
    if not price:
        price = req("POST", f"{CAT}/productOfferingPrice", {
            "name": price_name, "priceType": "recurring", "recurringChargePeriodType": "month",
            "price": {"unit": "EUR", "value": rate}, "lifecycleStatus": "Active"})
    off_name = f"{owner_name} {'Activated Bitstream' if layer.startswith('L3') else 'VULA'} {bandwidth}"
    off = by_name("productOffering", off_name)
    if not off:
        off = req("POST", f"{CAT}/productOffering", {
            "name": off_name,
            "description": f"Wholesale {layer} fibre access from {owner_name}, up to {bandwidth} Mbit/s.",
            "lifecycleStatus": "Active",
            "isSellable": False,   # wholesale input, never a consumer SKU
            "category": [{"id": cat["id"], "name": cat["name"], "@referredType": "Category"}],
            "productSpecification": {"id": spec["id"], "name": spec_name, "@referredType": "ProductSpecification"},
            "productOfferingPrice": [{"id": price["id"], "name": price_name, "@referredType": "ProductOfferingPrice"}],
        })
        print(f"offering: {off_name} @ {rate} EUR/line/mo [{cat['name']}]")
    return off


cat = ensure_category("Wholesale access")
ensure_access_product("NordAccess", "NORDACCESS", "L3-activated", 1000, 24.00, cat)
ensure_access_product("FjordFiber", "FJORDFIBER", "L2-VULA", 500, 16.00, cat)

# mark the retail broadband spec as delivered-over-access — a fibre sale is realized
# upstream, not on our own network
fiber = by_name("productOffering", "GenAlpha Fiber 1000")
if fiber and fiber.get("productSpecification", {}).get("id"):
    sid = fiber["productSpecification"]["id"]
    spec = req("GET", f"{CAT}/productSpecification/{sid}")
    chars = spec.get("productSpecCharacteristic") or []
    if not any(c.get("name") == "deliveredOverAccess" for c in chars):
        chars.append(fact("deliveredOverAccess", True))
        req("PATCH", f"{CAT}/productSpecification/{sid}", {"productSpecCharacteristic": chars})
        print("retail: GenAlpha Fiber 1000 marked deliveredOverAccess=true")

print("\nW2 seeded — wholesale access products (L2/L3) modeled; retail fibre rides them.")
