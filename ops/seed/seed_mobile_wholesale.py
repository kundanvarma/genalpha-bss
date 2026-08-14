#!/usr/bin/env python3
"""W-M1 — mobile wholesale / MVNE: the host MNO, the agreement, the rate card.

The platform plays a light MVNO: its subscribers ride a host MNO's network, and
the traffic they burn is owed to the host at a per-unit wholesale rate card. We
model the host as an Organization party in role hostMno (a TMF668 "Mobile
wholesale" partnership permitting hostMno/mvno, carried by a TMF651 agreement),
the per-unit rates as a wholesale rate card on the usage service, the lent IMSI
range as a modeled resource, and the MVNO tier as a catalog product.

The host is FICTIONAL (NordMobile) — never a real brand. Idempotent; file+live.
"""
import json, os, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
PARTY, PT, AGR = "/tmf-api/party/v4", "/tmf-api/partnershipTypeManagement/v4", "/tmf-api/agreementManagement/v4"
USAGE = "/tmf-api/usageManagement/v4"
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


def by_name(coll, name, path):
    for o in req("GET", f"{path}?limit=100"):
        if o.get("name") == name:
            return o
    return None


# 1. host MNO party
host = by_name("organization", "NordMobile", f"{PARTY}/organization")
if not host:
    host = req("POST", f"{PARTY}/organization",
               {"name": "NordMobile", "tradingName": "NORDMOBILE", "@type": "Organization"})
    print(f"host MNO: NordMobile ({host['id'][:8]})")

# 2. partnership type (hostMno / mvno)
pt = by_name("partnershipType", "Mobile wholesale", f"{PT}/partnershipType")
if not pt:
    pt = req("POST", f"{PT}/partnershipType", {
        "name": "Mobile wholesale", "status": "active",
        "description": "A host MNO wholesaling network capacity to MVNOs",
        "roleType": [{"name": "hostMno", "description": "Owns the network, sells wholesale capacity"},
                     {"name": "mvno", "description": "Runs a retail mobile brand on the host's network"}]})
    print(f"partnershipType: Mobile wholesale ({pt['id'][:8]})")

# 3. agreement (the host is hostMno; the wholesale terms ride the characteristic)
if not by_name("agreement", "Mobile wholesale — NordMobile", f"{AGR}/agreement"):
    req("POST", f"{AGR}/agreement", {
        "name": "Mobile wholesale — NordMobile", "agreementType": "partnership", "status": "active",
        "engagedParty": [{"id": host["id"], "role": "hostMno", "@referredType": "Organization"}],
        "characteristic": {"partnershipTypeId": pt["id"], "hostCode": "NORDMOBILE",
                           "mvnoTier": "light", "settlement": "usage-metered monthly"}})
    print("agreement: Mobile wholesale — NordMobile")

# 4. wholesale rate card (per usage type) — what we pay the host per unit
for spec, rate, unit in [("Mobile data", 2.50, "GB"), ("Mobile voice", 0.02, "min"), ("Mobile SMS", 0.01, "sms")]:
    if not any(c.get("usageSpecName") == spec for c in req("GET", f"{USAGE}/wholesaleRateCard")):
        req("POST", f"{USAGE}/wholesaleRateCard",
            {"usageSpecName": spec, "wholesaleRate": rate, "unit": unit,
             "hostPartyId": host["id"], "hostName": "NordMobile (host MNO)"})
        print(f"rate card: {spec} @ {rate}/{unit}")

# 5. IMSI range the host lends the MVNO (modeled resource)
if not any(r.get("prefix") == "242011" for r in req("GET", f"{USAGE}/imsiRange")):
    req("POST", f"{USAGE}/imsiRange",
        {"hostPartyId": host["id"], "hostName": "NordMobile (host MNO)", "prefix": "242011",
         "fromImsi": "242011000000000", "toImsi": "242011000999999", "capacity": 1000000,
         "note": "Light-MVNO allocation from NordMobile"})
    print("IMSI range: 242011… (1,000,000)")

# 6. MVNO tier as a catalog product (shop-excluded "Wholesale mobile" category)
cats = req("GET", f"{CAT}/category?limit=100")
cat = next((c for c in cats if c.get("name") == "Wholesale mobile"), None)
if not cat:
    cat = req("POST", f"{CAT}/category", {"name": "Wholesale mobile", "lifecycleStatus": "Active"})
    print("category: Wholesale mobile")
if not by_name("productOffering", "Light MVNO (NordMobile host)", f"{CAT}/productOffering"):
    price = req("POST", f"{CAT}/productOfferingPrice", {"name": "Light MVNO wholesale — usage-metered",
        "priceType": "usage", "lifecycleStatus": "Active"})
    req("POST", f"{CAT}/productOffering", {
        "name": "Light MVNO (NordMobile host)", "lifecycleStatus": "Active",
        "description": "Light-MVNO tier: own BSS + brand on NordMobile's network; usage-metered wholesale.",
        "category": [{"id": cat["id"], "name": cat["name"], "@referredType": "Category"}],
        "productOfferingPrice": [{"id": price["id"], "name": price["name"], "@referredType": "ProductOfferingPrice"}]})
    print("product: Light MVNO (NordMobile host)")

# 7. PROVIDER face (W-M7): we ALSO host external MVNOs who run their own BSS.
#    Two of them, on different tiers — the per-MVNO rate card is the SLA lever.
for mvno_name, code, prem in [("Aurora Mobile", "AURORA", True), ("Fjord Mobile", "FJORD", False)]:
    org = by_name("organization", mvno_name, f"{PARTY}/organization")
    if not org:
        org = req("POST", f"{PARTY}/organization",
                  {"name": mvno_name, "tradingName": code, "@type": "Organization"})
        print(f"MVNO party: {mvno_name} ({org['id'][:8]})")
    if prem:  # a per-MVNO override (premium tier pays more)
        cards = req("GET", f"{USAGE}/providerRateCard")
        if not any(c.get("mvnoPartyId") == org["id"] and c.get("usageSpecName") == "Mobile data" for c in cards):
            req("POST", f"{USAGE}/providerRateCard",
                {"mvnoPartyId": org["id"], "mvnoName": mvno_name, "usageSpecName": "Mobile data",
                 "rate": 2.50, "unit": "GB"})
            print(f"provider rate (premium): {mvno_name} Mobile data @ 2.50/GB")
# default provider rates (any MVNO without an override)
for spec, rate, unit in [("Mobile data", 2.00, "GB"), ("Mobile voice", 0.015, "min"), ("Mobile SMS", 0.008, "sms")]:
    cards = req("GET", f"{USAGE}/providerRateCard")
    if not any(c.get("mvnoPartyId") is None and c.get("usageSpecName") == spec for c in cards):
        req("POST", f"{USAGE}/providerRateCard", {"usageSpecName": spec, "rate": rate, "unit": unit})
        print(f"provider rate (default): {spec} @ {rate}/{unit}")

print("mobile-wholesale seed complete.")
