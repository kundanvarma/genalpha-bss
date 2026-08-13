#!/usr/bin/env python3
"""W3 — wholesale coverage: where third parties own the fibre and we buy access.

Our own footprint (seed_coverage_map) stays as it is — postcodes 111/222/333 are
our own network. This adds an OPEN-ACCESS area (Bergen, postcode 50xx) where we own
no fibre, but two owners do:

  • NordAccess  L3 activated, up to 1000 Mbit/s
  • FjordFiber  L2 VULA,      up to  500 Mbit/s

so a fibre sale there must be REALIZED upstream — and the retailer picks the owner.
Idempotent by (technology, prefix, accessOwner). File+live.
"""
import json, os, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
SQ = "/tmf-api/serviceQualificationManagement/v4/coverageMap"


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


existing = {(r.get("technology"), r.get("postcodePrefix"), r.get("accessOwner"))
            for r in req("GET", SQ)}

rows = [
    {"technology": "fiber", "postcodePrefix": "50", "maxDownMbps": 1000, "maxUpMbps": 1000,
     "accessOwner": "NORDACCESS", "accessLayer": "L3-activated",
     "note": "Bergen open access — NordAccess activated bitstream"},
    {"technology": "fiber", "postcodePrefix": "50", "maxDownMbps": 500, "maxUpMbps": 500,
     "accessOwner": "FJORDFIBER", "accessLayer": "L2-VULA",
     "note": "Bergen open access — FjordFiber VULA"},
]

for row in rows:
    key = (row["technology"], row["postcodePrefix"], row["accessOwner"])
    if key in existing:
        print(f"exists: {row['accessOwner']} {row['technology']} @ {row['postcodePrefix']}xx")
        continue
    req("POST", SQ, row)
    print(f"covered (wholesale): {row['accessOwner']} {row['accessLayer']} "
          f"{row['technology']} in {row['postcodePrefix']}xx at {row['maxDownMbps']} Mbps")

# Open access EXPANDS our sellable footprint: because owners serve 50xx, we can
# sell retail fibre there — so the TMF679 commercial gate opens 50xx too.
CATALOG = "/tmf-api/productCatalogManagement/v4"
POQ = "/tmf-api/productOfferingQualification/v4"
fiber = next((o for o in req("GET", f"{CATALOG}/productOffering?limit=100")
              if o.get("name") == "GenAlpha Fiber 1000"), None)
if fiber:
    gates = req("GET", f"{POQ}/serviceableArea?limit=100")
    have = any((g.get("productOffering") or {}).get("id") == fiber["id"]
               and str(g.get("postcodePrefix")) == "50" for g in (gates if isinstance(gates, list) else []))
    if not have:
        req("POST", f"{POQ}/serviceableArea", {
            "name": "GenAlpha Fiber 1000 — Bergen open access",
            "productOffering": {"id": fiber["id"], "name": fiber["name"], "@referredType": "ProductOffering"},
            "postcodePrefix": "50"})
        print("gate: GenAlpha Fiber 1000 now sellable at 50xx (via wholesale access)")

print("\nW3 seeded — open-access area 50xx served by NordAccess (L3) + FjordFiber (L2).")
