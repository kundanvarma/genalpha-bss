#!/usr/bin/env python3
"""W1 — wholesale / open-access parties + agreements.

Open access makes the fibre owner a SUPPLIER we buy access from, not a competitor.
We model that with the seams the fleet already has: the owner is an Organization
party in the role accessProvider, we are the accessSeeker, and the wholesale
relationship is a TMF668 partnership type ("Wholesale access", permitting exactly
those two roles) carried by a TMF651 agreement that also holds the commercial terms
— the per-line wholesale rate and the access layer each owner sells.

The owners are FICTIONAL (NordAccess, FjordFiber) — never real brands. Idempotent;
part of the file+live doctrine.
"""
import json, os, urllib.request, urllib.error

API = os.environ.get("BSS_API", "http://localhost:8080")
KC = os.environ.get("BSS_KC", "http://localhost:8085/realms/bss/protocol/openid-connect/token")
PARTY = "/tmf-api/party/v4"
PT = "/tmf-api/partnershipTypeManagement/v4"
AGR = "/tmf-api/agreementManagement/v4"


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


def find_org(name):
    for o in req("GET", f"{PARTY}/organization?limit=100"):
        if o.get("name") == name:
            return o
    return None


def ensure_org(name, trading):
    o = find_org(name)
    if o:
        return o
    o = req("POST", f"{PARTY}/organization", {"name": name, "tradingName": trading, "@type": "Organization"})
    print(f"org: {name} ({o['id'][:8]})")
    return o


def ensure_partnership_type():
    for t in req("GET", f"{PT}/partnershipType?limit=100"):
        if t.get("name") == "Wholesale access":
            return t
    t = req("POST", f"{PT}/partnershipType", {
        "name": "Wholesale access",
        "description": "An open-access fibre relationship: an access provider sells "
                       "wholesale access (L2 VULA or L3 activated) to an access seeker.",
        "roleType": [
            {"name": "accessProvider", "description": "owns the fibre, sells wholesale access"},
            {"name": "accessSeeker", "description": "retail ISP buying access to sell on top"},
        ],
        "status": "active",
    })
    print(f"partnershipType: Wholesale access ({t['id'][:8]}) roles=accessProvider,accessSeeker")
    return t


def ensure_agreement(name, type_id, seeker_id, owner_id, owner_code, layer, rate, bandwidth):
    for a in req("GET", f"{AGR}/agreement?limit=100"):
        if a.get("name") == name:
            return a
    a = req("POST", f"{AGR}/agreement", {
        "name": name,
        "agreementType": "partnership",
        "status": "active",
        "engagedParty": [
            {"id": owner_id, "role": "accessProvider"},
            {"id": seeker_id, "role": "accessSeeker"},
        ],
        # keyed characteristics = the commercial terms the wholesale side reads
        "characteristic": {
            "partnershipTypeId": type_id,
            "accessOwnerCode": owner_code,
            "accessLayer": layer,               # L2-VULA | L3-activated
            "wholesaleRatePerLine": rate,       # EUR / active line / month
            "maxDownMbps": bandwidth,
        },
    })
    print(f"agreement: {name} — {owner_code} {layer} @ {rate} EUR/line/mo up to {bandwidth} Mbit/s")
    return a


# --- us: the retail arm that buys access ---
seeker = ensure_org("GenAlpha Retail", "GenAlpha")
pt = ensure_partnership_type()

# --- the fibre owners we can buy from (fictional) ---
nord = ensure_org("NordAccess", "NordAccess Fibre")
fjord = ensure_org("FjordFiber", "FjordFiber")

# --- the wholesale agreements: one owner sells L3 activated, one sells L2 VULA ---
ensure_agreement("Wholesale access — NordAccess (L3)", pt["id"], seeker["id"], nord["id"],
                 "NORDACCESS", "L3-activated", 24.00, 1000)
ensure_agreement("Wholesale access — FjordFiber (L2)", pt["id"], seeker["id"], fjord["id"],
                 "FJORDFIBER", "L2-VULA", 16.00, 500)

print("\nW1 seeded — wholesale parties + partnership + agreements in place.")
