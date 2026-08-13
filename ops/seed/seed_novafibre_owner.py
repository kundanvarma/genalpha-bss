#!/usr/bin/env python3
"""X3.4 — NovaFibre: an owner that is ANOTHER OPERATOR on this very platform.

The seeker-side seeds (NordAccess/FjordFiber) are EXTERNAL owners reached through a
mock Sonata server. NovaFibre is different: it is the `nova` tenant, a fibre owner
running on the same platform. This seeds it on the RETAILER (genalpha) side — an
agreement, a wholesale access product, and coverage at Trondheim (postcode 70xx) —
so a genalpha fibre sale there is realized by an access-seeker order placed CROSS-
TENANT to nova's own Sonata provider face. One platform, both sides of the trade.

Idempotent; file+live.
"""
import json, os, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
PARTY, PT, AGR = "/tmf-api/party/v4", "/tmf-api/partnershipTypeManagement/v4", "/tmf-api/agreementManagement/v4"
CAT = "/tmf-api/productCatalogManagement/v4"
SQ = "/tmf-api/serviceQualificationManagement/v4/coverageMap"
POQ = "/tmf-api/productOfferingQualification/v4"

def tok():
    data = "grant_type=password&client_id=bss-demo&username=demo&password=demo".encode()
    return json.load(urllib.request.urlopen(urllib.request.Request(KC, data=data)))["access_token"]
T = tok()

def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Authorization": "Bearer " + T}
    if data: h["Content-Type"] = "application/json"
    r = urllib.request.Request(API + path, data=data, headers=h, method=method)
    try:
        return json.load(urllib.request.urlopen(r))
    except urllib.error.HTTPError as e:
        print("ERR", method, path, e.code, e.read().decode()[:200]); raise

def by_name(kind, name, base=CAT):
    for x in req("GET", f"{base}/{kind}?limit=100"):
        if x.get("name") == name: return x
    return None

OWNER, CODE, LAYER, BW, RATE, PREFIX = "NovaFibre", "NOVAFIBRE", "L3-activated", 1000, 22.00, "70"

# 1. owner org + wholesale agreement (reuse the Wholesale access partnership type)
def ensure_org(name):
    o = next((x for x in req("GET", f"{PARTY}/organization?limit=100") if x.get("name") == name), None)
    return o or req("POST", f"{PARTY}/organization", {"name": name, "tradingName": name, "@type": "Organization"})
nova = ensure_org(OWNER)
seeker = ensure_org("GenAlpha Retail")
pt = next(x for x in req("GET", f"{PT}/partnershipType?limit=100") if x.get("name") == "Wholesale access")
if not any(a.get("name") == f"Wholesale access — {OWNER} (L3)" for a in req("GET", f"{AGR}/agreement?limit=100")):
    req("POST", f"{AGR}/agreement", {"name": f"Wholesale access — {OWNER} (L3)", "agreementType": "partnership",
        "status": "active", "engagedParty": [{"id": nova["id"], "role": "accessProvider"},
        {"id": seeker["id"], "role": "accessSeeker"}], "characteristic": {"partnershipTypeId": pt["id"],
        "accessOwnerCode": CODE, "accessLayer": LAYER, "wholesaleRatePerLine": RATE, "maxDownMbps": BW}})
    print(f"agreement: Wholesale access — {OWNER} (L3) @ {RATE}")

# 2. wholesale access product (so the rate card + billing see NovaFibre)
cat = by_name("category", "Wholesale access")
def fact(n, v): return {"name": n, "configurable": False, "productSpecCharacteristicValue": [{"value": v}]}
spec_name = f"{OWNER} Wholesale Access"
spec = by_name("productSpecification", spec_name) or req("POST", f"{CAT}/productSpecification",
    {"name": spec_name, "brand": OWNER, "lifecycleStatus": "Active",
     "productSpecCharacteristic": [fact("accessLayer", LAYER), fact("accessOwner", CODE), fact("maxDownMbps", BW)]})
price_name = f"{OWNER} wholesale per line"
price = by_name("productOfferingPrice", price_name) or req("POST", f"{CAT}/productOfferingPrice",
    {"name": price_name, "priceType": "recurring", "recurringChargePeriodType": "month",
     "price": {"unit": "EUR", "value": RATE}, "lifecycleStatus": "Active"})
off_name = f"{OWNER} Activated Bitstream {BW}"
if not by_name("productOffering", off_name):
    req("POST", f"{CAT}/productOffering", {"name": off_name, "lifecycleStatus": "Active",
        "description": f"Wholesale {LAYER} fibre access from {OWNER}, up to {BW} Mbit/s.",
        "category": [{"id": cat["id"], "name": cat["name"], "@referredType": "Category"}],
        "productSpecification": {"id": spec["id"], "name": spec_name, "@referredType": "ProductSpecification"},
        "productOfferingPrice": [{"id": price["id"], "name": price_name, "@referredType": "ProductOfferingPrice"}]})
    print(f"offering: {off_name} @ {RATE}")

# 3. coverage at Trondheim 70xx + open the retail fibre gate there
existing = {(r.get("technology"), r.get("postcodePrefix"), r.get("accessOwner")) for r in req("GET", SQ)}
if ("fiber", PREFIX, CODE) not in existing:
    req("POST", SQ, {"technology": "fiber", "postcodePrefix": PREFIX, "maxDownMbps": BW, "maxUpMbps": BW,
        "accessOwner": CODE, "accessLayer": LAYER, "note": f"Trondheim open access — {OWNER}"})
    print(f"coverage: {CODE} {LAYER} fiber in {PREFIX}xx at {BW} Mbps")
fiber = by_name("productOffering", "GenAlpha Fiber 1000")
gates = req("GET", f"{POQ}/serviceableArea?limit=100")
if not any((g.get("productOffering") or {}).get("id") == fiber["id"] and str(g.get("postcodePrefix")) == PREFIX
           for g in (gates if isinstance(gates, list) else [])):
    req("POST", f"{POQ}/serviceableArea", {"name": f"GenAlpha Fiber 1000 — Trondheim ({OWNER})",
        "productOffering": {"id": fiber["id"], "name": fiber["name"], "@referredType": "ProductOffering"},
        "postcodePrefix": PREFIX})
    print(f"gate: GenAlpha Fiber 1000 sellable at {PREFIX}xx (via {OWNER})")

print(f"\nNovaFibre seeded — genalpha fibre at {PREFIX}xx is realized cross-tenant by nova.")
