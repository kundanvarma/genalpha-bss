#!/usr/bin/env python3
"""Charging references (OCS seam): each mobile plan's SPEC gains a
chargingSpecId characteristic — the id of the rate plan / counter template
that lives in the OPERATOR'S OCS. The catalog references charging, it never
contains it; the SOM provisions the subscriber onto that plan at activation.
Idempotent."""
import json
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"

# commercial offering -> the OCS's own rate-plan id
CHARGING = {
    "GenAlpha Mobile 10 GB": "RG-DATA-10",
    "GenAlpha Mobile 50 GB": "RG-DATA-50",
    "GenAlpha Mobile 30GB 5G": "RG-DATA-30",
    "GenAlpha Mobile 60 GB 5G": "RG-DATA-60",
    "GenAlpha Mobile Unlimited 5G": "RG-UNL",
    "Kids Plan 2 GB": "RG-DATA-2",
}


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    url = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


TOK = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


offerings = req("GET", f"{CATALOG}/productOffering?limit=100")
for o in offerings:
    rate_plan = CHARGING.get(o["name"])
    spec_id = (o.get("productSpecification") or {}).get("id")
    if not rate_plan or not spec_id:
        continue
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")
    chars = spec.get("productSpecCharacteristic") or []
    if any(c["name"] == "chargingSpecId" for c in chars):
        continue
    chars.append({"name": "chargingSpecId", "valueType": "string", "configurable": False,
                  "productSpecCharacteristicValue": [{"value": rate_plan}]})
    req("PATCH", f"{CATALOG}/productSpecification/{spec_id}",
        {"productSpecCharacteristic": chars})
    print(f"{o['name']} -> {rate_plan}")

print("done")
