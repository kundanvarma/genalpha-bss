#!/usr/bin/env python3
"""Lifecycle-ready product model: make broadband speed and TV a configurable
CHARACTERISTIC of the subscribed product, not a value baked into an offering name.

Once speed lives as a productSpecCharacteristic, upgrade/downgrade is just a
characteristic-value change on the running product (TMF622 action=modify) — no new
product, no migration — and the TMF760 configurator surfaces the tiers for free.
TV gains configurable screens + an entertainment-points allowance the same way.

Idempotent: run it as often as you like. Part of the file+live doctrine — it is
in the seed set for fresh stacks and is applied live against a running catalog.
"""
import json, os, sys, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
CATALOG = "/tmf-api/productCatalogManagement/v4"


def tok():
    data = "grant_type=password&client_id=bss-demo&username=demo&password=demo".encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC, data=data)))["access_token"]


T = tok()


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Authorization": "Bearer " + T}
    if data:
        headers["Content-Type"] = "application/json"
    r = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        return json.load(urllib.request.urlopen(r))
    except urllib.error.HTTPError as e:
        print("ERR", method, path, e.code, e.read().decode()[:300]); raise


def offering(name):
    offs = req("GET", f"{CATALOG}/productOffering?limit=100")
    return next(o for o in offs if o.get("name") == name)


def set_spec_characteristics(spec_id, chars):
    """Merge characteristics into a spec by name (idempotent)."""
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")
    existing = {c.get("name"): c for c in (spec.get("productSpecCharacteristic") or [])}
    for c in chars:
        existing[c["name"]] = c
    req("PATCH", f"{CATALOG}/productSpecification/{spec_id}",
        {"productSpecCharacteristic": list(existing.values())})
    print(f"spec {spec.get('name')}: characteristics {[c['name'] for c in chars]}")


def mbps(v, default=False):
    row = {"value": v, "valueType": "number", "unitOfMeasure": "Mbit/s"}
    if default:
        row["isDefault"] = True
    return row


def num(v, default=False):
    row = {"value": v, "valueType": "number"}
    if default:
        row["isDefault"] = True
    return row


def ensure_conditioned_price(off, name, delta, char_name, char_value):
    """A price component that applies only when a characteristic has a value —
    the mechanism the configurator already prices bundles with. Negative = a
    lower-tier discount off the flagship speed."""
    prices = req("GET", f"{CATALOG}/productOfferingPrice?limit=100")
    p = next((x for x in prices if x.get("name") == name), None)
    if p is None:
        p = req("POST", f"{CATALOG}/productOfferingPrice", {
            "name": name,
            "priceType": "recurring",
            "recurringChargePeriodType": "month",
            "price": {"unit": "EUR", "value": delta},
            "prodSpecCharValueUse": [{
                "name": char_name,
                "productSpecCharacteristicValue": [{"value": char_value}],
            }],
            "lifecycleStatus": "Active",
        })
        print(f"price: {name} {delta:+.2f} EUR/mo when {char_name}={char_value}")
    refs = off.get("productOfferingPrice") or []
    if not any(r.get("id") == p["id"] for r in refs):
        refs.append({"id": p["id"], "name": name, "@referredType": "ProductOfferingPrice"})
        req("PATCH", f"{CATALOG}/productOffering/{off['id']}", {"productOfferingPrice": refs})
        print(f"offering {off['name']}: linked {name}")
    return p


# ---- Broadband: download/upload speed as a configurable tier characteristic ----
fiber = offering("GenAlpha Fiber 1000")
fiber_spec = fiber["productSpecification"]["id"]
set_spec_characteristics(fiber_spec, [
    {"name": "downloadSpeed", "valueType": "number", "configurable": True,
     "description": "Download speed (Mbit/s) — upgrade or downgrade any time your line supports it",
     "productSpecCharacteristicValue": [mbps(100), mbps(300), mbps(500), mbps(1000, True)]},
    {"name": "uploadSpeed", "valueType": "number", "configurable": True,
     "description": "Upload speed (Mbit/s)",
     "productSpecCharacteristicValue": [mbps(100), mbps(300), mbps(500), mbps(1000, True)]},
])
# flagship 1000 is the base price; lower tiers are conditioned discounts off it
ensure_conditioned_price(fiber, "Fiber 500 tier", -5.00, "downloadSpeed", 500)
ensure_conditioned_price(fiber, "Fiber 300 tier", -10.00, "downloadSpeed", 300)
ensure_conditioned_price(fiber, "Fiber 100 tier", -15.00, "downloadSpeed", 100)

# ---- TV: configurable simultaneous screens + an entertainment-points allowance ----
tv = offering("GenAlpha TV Max")
tv_spec = tv["productSpecification"]["id"]
set_spec_characteristics(tv_spec, [
    {"name": "screens", "valueType": "number", "configurable": True,
     "description": "Simultaneous streams",
     "productSpecCharacteristicValue": [num(1), num(2, True), num(4)]},
    {"name": "entertainmentPoints", "valueType": "number", "configurable": True,
     "description": "Monthly points to spend on premium channels & rentals",
     "productSpecCharacteristicValue": [num(0, True), num(50), num(100)]},
])
ensure_conditioned_price(tv, "TV extra screens (4)", 5.00, "screens", 4)
ensure_conditioned_price(tv, "TV points 50", 4.00, "entertainmentPoints", 50)
ensure_conditioned_price(tv, "TV points 100", 7.00, "entertainmentPoints", 100)

print("\nlifecycle characteristics seeded — speed & TV are now configurable, upgrade/downgrade-ready")
