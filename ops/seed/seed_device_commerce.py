#!/usr/bin/env python3
"""Device-commerce demo data (idempotent):

- trade-in residual rows for every shop phone model (the Devices category),
  one row per age band 0/12/24 months, EUR values descending with age — the
  instant-estimate widget quotes off this table;
- one OPERATOR_BOOK device agreement for paula (subsidy > 0 so the IFRS 15
  contract-asset posting shows in the revenue pane; TCO on the offer face).

SKIPs gracefully when the device-commerce service or the personas are not
up yet (the service ships behind the catalog/party fleet).
"""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
DEVICE = "/tmf-api/deviceCommerce/v1"
PARTY = "/tmf-api/party/v4"
KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"


def token(username="demo", password="demo"):
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": username, "password": password,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def jwt_sub(tok):
    payload = tok.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def find_party(email, username, password):
    """Party id by email search (the way the consoles find people), with the
    persona's own token subject as the fallback."""
    try:
        q = urllib.parse.quote(email.split("@")[0])
        for p in req("GET", f"{PARTY}/individual?q={q}&limit=50"):
            for m in p.get("contactMedium") or []:
                if (m.get("characteristic") or {}).get("emailAddress") == email:
                    return p["id"]
    except (urllib.error.HTTPError, urllib.error.URLError):
        pass
    try:
        return jwt_sub(token(username, password))
    except (urllib.error.HTTPError, urllib.error.URLError):
        return None


# model -> baseValue (EUR) per age band; unknown Devices models take _generic
RESIDUALS = {
    "Samsung Galaxy S26": {0: 520, 12: 380, 24: 240},
    "Apple iPhone 17 Pro": {0: 640, 12: 480, 24: 330},
    "Apple iPhone 17": {0: 450, 12: 330, 24: 210},
    "_generic": {0: 300, 12: 200, 24: 120},
}

# ---- 1. residual table: one row per shop phone model per age band ----------
try:
    existing_rows = {(row["deviceRef"], int(row["ageMonths"]))
                     for row in req("GET", f"{DEVICE}/tradeInResidual")}
except (urllib.error.HTTPError, urllib.error.URLError) as e:
    print(f"SKIP: device-commerce not reachable through the gateway ({e})")
    raise SystemExit(0)

try:
    offerings = req("GET", f"{CATALOG}/productOffering?limit=100")
except (urllib.error.HTTPError, urllib.error.URLError) as e:
    print(f"SKIP: catalog not reachable ({e})")
    raise SystemExit(0)

devices = [o for o in offerings
           if ((o.get("category") or [{}])[0]).get("name") == "Devices"]
if not devices:
    print("SKIP: no Devices offerings in the catalog (run seed_content first)")
    raise SystemExit(0)

for o in devices:
    bands = RESIDUALS.get(o["name"], RESIDUALS["_generic"])
    for age, value in sorted(bands.items()):
        if (o["name"], age) in existing_rows:
            print(f"exists: residual {o['name']} @ {age} mo")
            continue
        req("POST", f"{DEVICE}/tradeInResidual", {
            "deviceRef": o["name"], "ageMonths": age,
            "baseValue": value, "currency": "EUR"})
        print(f"residual: {o['name']} @ {age} mo -> {value} EUR")

# ---- 2. paula's operator-book agreement (subsidised, TCO on the face) ------
ORDER_REF = "seed-device-demo-paula"
paula = find_party("paula@family.example", "paula@family.example", "paula")
if not paula:
    print("SKIP: paula@family.example not found — no demo agreement seeded")
    raise SystemExit(0)

agreements = req("GET", f"{DEVICE}/deviceAgreement?relatedPartyId={paula}")
if any(a.get("orderRef") == ORDER_REF for a in agreements):
    print("exists: paula's operator-book device agreement")
    raise SystemExit(0)

model = next((o["name"] for o in devices if o["name"] == "Samsung Galaxy S26"),
             devices[0]["name"])
created = req("POST", f"{DEVICE}/deviceAgreement", {
    "financingModel": "OPERATOR_BOOK",
    "principal": 720, "termMonths": 24,
    "totalCostOfOwnership": 720, "subsidyAmount": 240,
    "currency": "EUR", "deviceRef": model,
    "orderRef": ORDER_REF,
    "upgradeRule": {"paidSharePct": 50},
    "relatedParty": [{"id": paula, "role": "customer"}]})
print(f"agreement: {created['id'][:8]}… OPERATOR_BOOK {model} for paula — "
      f"24 x {created['monthlyAmount']} EUR, subsidy 240 (watch sourceRef "
      f"device-activation:{created['id'][:8]}… in revenue)")
print("done")
