#!/usr/bin/env python3
"""Device entitlement demo data (GSMA TS.43): the mobile plans' specs gain the
characteristics the entitlement server decides from — volte, vonr, vowifi,
smsoip, companionEsim, esimTransfer, dataPlanType — and the demo personas'
lines are bound to IMSIs the HSS mock knows, so a simulated phone can walk
the EAP-AKA relay and read its entitlements. Idempotent.

Realm/tenant via REALM (default bss = genalpha)."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REALM = os.environ.get("REALM", "bss")
GATEWAY = "http://localhost:8080"
HSS = os.environ.get("HSS_URL", "http://localhost:8157")
CATALOG = "/tmf-api/productCatalogManagement/v4"
ENT = "/tmf-api/deviceEntitlement/v1"
# a Norwegian PLMN shape for the demo IMSIs, one MNC per demo operator so the
# shared HSS mock keeps them apart
MCC_MNC = os.environ.get("IMSI_PREFIX", {"bss": "24205", "taranga": "24206", "nova": "24207"}.get(REALM, "24208"))

# plan name -> entitlement characteristics (the product decides, the ECS answers)
PLANS = {
    "GenAlpha Mobile 10 GB":       {"volte": "true", "vonr": "false", "vowifi": "true", "smsoip": "true", "companionEsim": "false", "esimTransfer": "true", "dataPlanType": "Metered"},
    "GenAlpha Mobile 50 GB":       {"volte": "true", "vonr": "false", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Metered"},
    "GenAlpha Mobile 30GB 5G":     {"volte": "true", "vonr": "true", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Metered"},
    "GenAlpha Mobile 60 GB 5G":    {"volte": "true", "vonr": "true", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Metered"},
    "GenAlpha Mobile Unlimited 5G": {"volte": "true", "vonr": "true", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Unmetered"},
    "Kids Plan 2 GB":              {"volte": "true", "vonr": "false", "vowifi": "false", "smsoip": "true", "companionEsim": "false", "esimTransfer": "false", "dataPlanType": "Metered"},
    "Taranga Mobile 5 GB":         {"volte": "true", "vonr": "false", "vowifi": "true", "smsoip": "true", "companionEsim": "false", "esimTransfer": "true", "dataPlanType": "Metered"},
    "Taranga Mobile 20 GB":        {"volte": "true", "vonr": "true", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Metered"},
    "Taranga Mobile Unlimited 5G": {"volte": "true", "vonr": "true", "vowifi": "true", "smsoip": "true", "companionEsim": "true", "esimTransfer": "true", "dataPlanType": "Unmetered"},
}


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "demo", "password": "demo"}).encode()
    url = f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


TOK = token()


def req(method, path, body=None, base=GATEWAY, auth=True):
    headers = {"Content-Type": "application/json"}
    if auth:
        headers["Authorization"] = f"Bearer {TOK}"
    r = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                               headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        print(f"  {method} {path} -> HTTP {e.code} {e.read()[:200]!r}")
        return None


# ---- 1. the plans decide: stamp the entitlement characteristics on each spec ----
offerings = req("GET", f"{CATALOG}/productOffering?limit=100") or []
stamped = 0
for o in offerings:
    chars_wanted = PLANS.get(o["name"])
    if not chars_wanted:
        continue
    spec_id = (o.get("productSpecification") or {}).get("id")
    if not spec_id:
        continue
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")
    if not spec:
        continue
    chars = spec.get("productSpecCharacteristic") or []
    have = {c["name"]: c for c in chars}
    changed = False
    for name, value in chars_wanted.items():
        current = (have.get(name) or {}).get("productSpecCharacteristicValue") or []
        if current and str(current[0].get("value")) == value:
            continue
        if name in have:
            have[name]["productSpecCharacteristicValue"] = [{"value": value}]
        else:
            chars.append({"name": name, "valueType": "string", "configurable": False,
                          "productSpecCharacteristicValue": [{"value": value}]})
        changed = True
    if changed:
        req("PATCH", f"{CATALOG}/productSpecification/{spec_id}", {"productSpecCharacteristic": chars})
        stamped += 1
        print(f"{o['name']}: entitlements stamped ({', '.join(k for k, v in chars_wanted.items() if v == 'true')})")
print(f"plans stamped: {stamped}")

# ---- 2. bind the demo personas' lines to IMSIs the HSS knows --------------------
PERSONAS = {"bss": ["Paula", "Sonny"], "taranga": ["Mira", "Olav"]}.get(REALM, [])
n = 0
for given in PERSONAS:
    people = req("GET", f"/tmf-api/party/v4/individual?givenName={given}&limit=5") or []
    person = next((p for p in people if p.get("givenName") == given), None)
    if not person:
        print(f"{given}: no party — skipped")
        continue
    services = req("GET", f"/tmf-api/serviceInventory/v4/service?relatedPartyId={person['id']}&state=active&limit=50") or []
    mine = [s for s in services if any(p.get("id") == person["id"] and p.get("role") == "customer"
                                       for p in (s.get("relatedParty") or []))]
    line = next((s for s in mine if "mobile" in str(s.get("name", "")).lower()), None) \
        or next((s for s in mine if any(c.get("name") == "category" and str(c.get("value")).lower() == "mobile"
                                        for c in (s.get("serviceCharacteristic") or []))), None)
    if not line:
        print(f"{given}: no active mobile line — skipped")
        continue
    n += 1
    imsi = f"{MCC_MNC}{n:010d}"
    # the line's number rides as its supporting resource
    number = next((r.get("value") for r in (line.get("supportingResource") or []) if r.get("value")), None)
    msisdn = "".join(ch for ch in str(number) if ch.isdigit()) if number else None
    # the offering the line was sold under: the service order carries it
    offering_id = None
    if line.get("serviceOrderId"):
        so = req("GET", f"/tmf-api/serviceOrdering/v4/serviceOrder/{line['serviceOrderId']}") or {}
        offering_id = so.get("offeringId") or (so.get("productOffering") or {}).get("id") \
            or next((((i.get("service") or {}).get("productOffering") or {}).get("id")
                     for i in (so.get("serviceOrderItem") or []) if i.get("service")), None)
    if not offering_id:
        # the line is named after the plan it was sold as
        offering_id = next((o["id"] for o in offerings if o.get("name") == line.get("name")), None)
    body = {"imsi": imsi, "partyId": person["id"], "serviceId": line["id"], "msisdn": msisdn,
            "offeringId": offering_id, "imsProvisioned": True}
    body = {k: v for k, v in body.items() if v is not None}
    req("PUT", f"{HSS}/subscribers/{imsi}", {"msisdn": msisdn, "iccid": f"8947{imsi[-11:]}0000"}, base="", auth=False)
    out = req("PUT", f"{ENT}/subscriber", body)
    if out:
        print(f"{given}: line {line['id'][:8]}… bound to IMSI {imsi} ({(out.get('entitlements') or {}).get('plan')})")

print("done")
