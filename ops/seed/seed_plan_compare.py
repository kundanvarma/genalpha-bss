#!/usr/bin/env python3
"""Plan comparison spec — real, per-plan characteristics the storefront compares.

The mobile plans only carried a chargingSpecId; the shop's comparison table was
inferring Data/5G from the NAME. This seeds honest per-plan characteristics onto
each plan's productSpecification (creating the spec if missing) so the table
reads truthful values, not guesses:

  Data · Network · EU roaming · Calls & texts

Idempotent. Run after the catalog is seeded (or via ops/demo-reset.sh).
"""
import json
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
KC = "http://localhost:8085/realms/bss/protocol/openid-connect/token"

# Truthful comparison data per plan (name -> ordered [(char, value)]).
COMPARE = {
    "GenAlpha Mobile 10 GB":        [("Data", "10 GB"), ("Network", "4G/5G-ready"), ("EU roaming", "Add-on"),   ("Calls & texts", "Unlimited")],
    "GenAlpha Mobile 30GB 5G":      [("Data", "30 GB"), ("Network", "5G"),          ("EU roaming", "Included"), ("Calls & texts", "Unlimited")],
    "GenAlpha Mobile 50 GB":        [("Data", "50 GB"), ("Network", "4G/5G-ready"), ("EU roaming", "Included"), ("Calls & texts", "Unlimited")],
    "GenAlpha Mobile 60 GB 5G":     [("Data", "60 GB"), ("Network", "5G"),          ("EU roaming", "Included"), ("Calls & texts", "Unlimited")],
    "GenAlpha Mobile Unlimited 5G": [("Data", "Unlimited"), ("Network", "5G"),      ("EU roaming", "Included"), ("Calls & texts", "Unlimited")],
    "GenAlpha Postpaid Mobile (ID verified)": [("Data", "Unlimited"), ("Network", "5G"), ("EU roaming", "Included"), ("Calls & texts", "Unlimited")],
    "Kids Plan 2 GB":               [("Data", "2 GB"),  ("Network", "4G"),          ("EU roaming", "Add-on"),   ("Calls & texts", "Unlimited")],
}


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=data)) as r:
        return json.load(r)["access_token"]


TOK = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp) if resp.length != 0 else None


def char(name, value):
    return {"name": name, "valueType": "string", "configurable": False,
            "productSpecCharacteristicValue": [{"value": value, "isDefault": True}]}


offs = req("GET", f"{CATALOG}/productOffering?limit=100")
by_name = {o.get("name"): o for o in offs}
done = 0
for name, rows in COMPARE.items():
    o = by_name.get(name)
    if not o:
        continue
    spec_id = (o.get("productSpecification") or {}).get("id")
    if not spec_id:
        spec = req("POST", f"{CATALOG}/productSpecification", {
            "name": name + " spec", "lifecycleStatus": "Active", "productSpecCharacteristic": []})
        req("PATCH", f"{CATALOG}/productOffering/{o['id']}", {
            "productSpecification": {"id": spec["id"], "name": spec["name"],
                                     "@referredType": "ProductSpecification"}})
        spec_id = spec["id"]
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")
    chars = spec.get("productSpecCharacteristic") or []
    # keep any existing non-compare chars (e.g. chargingSpecId), replace compare ones
    keep = [c for c in chars if c.get("name") not in {r[0] for r in rows}]
    merged = keep + [char(n, v) for n, v in rows]
    req("PATCH", f"{CATALOG}/productSpecification/{spec_id}", {"productSpecCharacteristic": merged})
    print(f"  {name}: {', '.join(f'{n}={v}' for n, v in rows)}")
    done += 1

print(f"\ndone — comparison specs seeded on {done} plan(s).")
