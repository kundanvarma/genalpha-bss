#!/usr/bin/env python3
"""AI-slice PoC seed (idempotent): the sellable side of 'Autonomy Accelerated' —
a stadium 5G slice, edge AI inferencing with token-based pricing, and the
edge GPU pool that makes the low-latency intent feasible."""
import json
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
GATEWAY = "http://localhost:8080"
CATALOG = f"{GATEWAY}/tmf-api/productCatalogManagement/v4"
POOLS = f"{GATEWAY}/tmf-api/resourcePoolManagement/v4"
USAGE = f"{GATEWAY}/tmf-api/usageManagement/v4"


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
        url, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def ref(entity, referred_type):
    return {"id": entity["id"], "href": entity.get("href"), "name": entity["name"],
            "@referredType": referred_type}


# --- 1. Edge GPU pool: physics turns into inventory here.
pools = req("GET", f"{POOLS}/resourcePool")
if not any(p["resourceType"] == "edge-gpu" and "stadium-north" in p["name"] for p in pools):
    req("POST", f"{POOLS}/resourcePool", {
        "name": "edge-gpu stadium-north",
        "resourceType": "edge-gpu",
        "prefix": "gpu-stadium-north-",
    })
    print("pool: edge-gpu stadium-north")
else:
    print("exists: edge-gpu stadium-north")

# --- 2. Offerings: the slice and the token-metered AI extension.
offerings = {o["name"]: o for o in req("GET", f"{CATALOG}/productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", f"{CATALOG}/productOfferingPrice?limit=100")}


def price(name, value, period=None):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": "recurring" if period else "oneTime",
            "price": {"unit": "EUR", "value": value}, "lifecycleStatus": "Active"}
    if period:
        body["recurringChargePeriodType"] = period
        body["recurringChargePeriodLength"] = 1
    created = req("POST", f"{CATALOG}/productOfferingPrice", body)
    prices[name] = created
    print(f"price: {name} {value} EUR{'/' + period if period else ''}")
    return created


def offering(name, description, spec_name, price_refs):
    if name in offerings:
        print(f"exists: {name}")
        return offerings[name]
    spec = req("POST", f"{CATALOG}/productSpecification",
               {"name": spec_name, "brand": "GenAlpha", "lifecycleStatus": "Active"})
    created = req("POST", f"{CATALOG}/productOffering", {
        "name": name, "description": description, "lifecycleStatus": "Active",
        "isSellable": True,
        "productSpecification": ref(spec, "ProductSpecification"),
        "productOfferingPrice": price_refs,
    })
    offerings[name] = created
    print(f"offering: {name}")
    return created


slice_price = price("Stadium 5G Slice monthly", 4900, "month")
ai_base = price("Edge AI Inferencing platform fee", 990, "month")

slice_offering = offering(
    "Stadium 5G Slice",
    "SLA-backed 5G network slice: guaranteed bandwidth and sub-10ms round trip "
    "for a venue. Feasibility decided by the intent-driven OSS.",
    "5G Network Slice (venue)",
    [ref(slice_price, "ProductOfferingPrice")])

ai_offering = offering(
    "Edge AI Inferencing",
    "GPU inference at the network edge, next to where the data is born. "
    "Token-metered: 50M tokens included, then pay per million.",
    "Edge GPU Inference Service",
    [ref(ai_base, "ProductOfferingPrice")])

# --- 3. Token metering: TMF635 rates AI usage like any other consumption.
allowances = {(a["productOffering"]["id"], a["usageType"])
              for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}
if (ai_offering["id"], "AI inference tokens") not in allowances:
    req("POST", f"{USAGE}/usageAllowance", {
        "productOffering": ref(ai_offering, "ProductOffering"),
        "usageType": "AI inference tokens",
        "allowance": {"value": 50, "units": "Mtokens"},
        "overagePrice": {"unit": "EUR", "value": 4.00},
    })
    print("allowance: Edge AI Inferencing -> 50 Mtokens incl, 4 EUR/Mtoken over")
else:
    print("exists: AI token allowance")

print("done")
