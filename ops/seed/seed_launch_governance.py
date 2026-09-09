#!/usr/bin/env python3
"""Launch-governance demo data for a tenant (default: taranga; `enet` also works).

Seeds:
  * two pre-approved ENVELOPES as policy rules (domain "launch", effect "allow") —
    the structured picker values ride in `experience.envelope` so the console can
    show them as pickers, the JSON-logic condition is what the engine evaluates;
  * a draft offer INSIDE an envelope (launches by itself when requested) and a
    draft OUTSIDE every envelope (waits for the approver) — with Data/Validity
    spec characteristics so the envelope has something to read.

Idempotent: re-running finds what exists.
"""
import json
import os
import sys
import urllib.request
import urllib.error
import urllib.parse

TENANT = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TENANT", "taranga")).lower()
API = os.environ.get("API", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK", f"http://localhost:8085/realms/{TENANT}/protocol/openid-connect/token")
CAT = "/tmf-api/productCatalogManagement/v4"
POL = "/tmf-api/policyManagement/v4"
CUR = {"taranga": "NOK", "enet": "GYD"}.get(TENANT, "EUR")
# the approver (demo) seeds; the product persona is who REQUESTS in the demo
USER, PASS = "demo", "demo"


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo", "username": USER, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None, ok=(200, 201)):
    r = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        print(f"  ! {method} {path} -> {e.code} {e.read()[:200]!r}")
        return None


def by_name(path, name):
    items = req("GET", f"{path}?name={urllib.parse.quote(name)}&limit=50") or []
    return next((i for i in items if i.get("name") == name), None)


# ---- envelopes ---------------------------------------------------------------------------
def envelope(name, description, picks, condition, message, priority=100):
    existing = [r for r in (req("GET", f"{POL}/policyRule?limit=200") or []) if r.get("name") == name]
    if existing:
        print(f"envelope exists: {name}")
        return existing[0]
    made = req("POST", f"{POL}/policyRule", {
        "name": name, "description": description, "domain": "launch", "effect": "allow", "priority": priority,
        "enabled": True, "condition": json.dumps(condition), "message": message,
        "experience": {"envelope": picks}})
    print(f"envelope: {name}")
    return made


if TENANT == "enet":
    ENVELOPES = [
        ("Prepaid top-ups under G$3,000", "Any prepaid top-up in the everyday price band, 30 days or shorter, on the retail channels.",
         {"category": ["top-ups"], "priceMin": 0, "priceMax": 3000, "priceType": "oneTime", "allowanceMaxGb": 100, "validityMaxDays": 30,
          "channel": ["web", "app", "store", "telesales", "care"], "zeroRatedApps": "any"},
         {"and": [{"in": ["top-ups", {"var": "category"}]}, {"<=": [{"var": "price"}, 3000]}, {">=": [{"var": "price"}, 0]},
                  {"<=": [{"var": "validityDays"}, 30]}, {"none": [{"var": "channel"}, {"in": [{"var": ""}, ["business", "partner", "agent-acp", "agent-mcp", "agent-a2a"]]}]}]},
         "top-ups up to G$3,000 for up to 30 days, retail channels only"),
        ("Match-day passes", "Short priority or data passes for events: a day or less, under G$1,500.",
         {"category": ["top-ups", "passes"], "priceMin": 0, "priceMax": 1500, "priceType": "oneTime", "validityMaxDays": 1,
          "channel": ["web", "app"]},
         {"and": [{"<=": [{"var": "price"}, 1500]}, {"<=": [{"var": "validityDays"}, 1]},
                  {"none": [{"var": "channel"}, {"in": [{"var": ""}, ["store", "telesales", "care", "business", "partner", "agent-acp", "agent-mcp", "agent-a2a"]]}]}]},
         "event passes of a day or less under G$1,500, app and web only"),
    ]
else:
    ENVELOPES = [
        ("Mobile plans 149–399 NOK", "Everyday mobile plans in the standard price band with up to 50 GB, sold on every channel.",
         {"category": ["mobile plans"], "priceMin": 149, "priceMax": 399, "priceType": "recurring", "allowanceMaxGb": 50,
          "channel": ["web", "app", "store", "telesales", "care", "business", "partner", "agent-acp", "agent-mcp", "agent-a2a"]},
         {"and": [{"in": ["mobile plans", {"var": "category"}]}, {">=": [{"var": "price"}, 149]}, {"<=": [{"var": "price"}, 399]},
                  {"==": [{"var": "priceType"}, "recurring"]},
                  {"or": [{"!": {"var": "allowanceGb"}}, {"<=": [{"var": "allowanceGb"}, 50]}]}]},
         "mobile plans between 149 and 399 NOK a month with up to 50 GB"),
        ("Top-ups and passes under 199 NOK", "One-off data top-ups and short passes under 199 NOK, valid 30 days or less, app and web.",
         {"category": ["top-ups"], "priceMin": 0, "priceMax": 199, "priceType": "oneTime", "validityMaxDays": 30,
          "channel": ["web", "app"]},
         {"and": [{"in": ["top-ups", {"var": "category"}]}, {"<=": [{"var": "price"}, 199]}, {"==": [{"var": "priceType"}, "oneTime"]},
                  {"or": [{"!": {"var": "validityDays"}}, {"<=": [{"var": "validityDays"}, 30]}]},
                  {"none": [{"var": "channel"}, {"in": [{"var": ""}, ["store", "telesales", "care", "business", "partner", "agent-acp", "agent-mcp", "agent-a2a"]]}]}]},
         "one-off top-ups under 199 NOK, 30 days or less, app and web only"),
    ]

for name, desc, picks, cond, msg in ENVELOPES:
    envelope(name, desc, picks, cond, msg)


# ---- two drafts: one inside an envelope, one outside -----------------------------------
def category(name):
    c = by_name(f"{CAT}/category", name)
    if not c:
        c = req("POST", f"{CAT}/category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")
    return {"id": c["id"], "name": c["name"], "@referredType": "Category"}


def spec(name, chars):
    s = by_name(f"{CAT}/productSpecification", name)
    if s:
        return s
    s = req("POST", f"{CAT}/productSpecification", {"name": name, "lifecycleStatus": "Active", "productSpecCharacteristic": [
        {"name": k, "configurable": False, "productSpecCharacteristicValue": [{"value": v}]} for k, v in chars.items()]})
    print(f"spec: {name}")
    return s


def price(name, value, price_type):
    p = by_name(f"{CAT}/productOfferingPrice", name)
    if p:
        return p
    body = {"name": name, "priceType": price_type, "price": {"unit": CUR, "value": value}, "lifecycleStatus": "Active"}
    if price_type == "recurring":
        body["recurringChargePeriodType"] = "month"
    p = req("POST", f"{CAT}/productOfferingPrice", body)
    print(f"price: {name} {value} {CUR}")
    return p


def draft(name, description, cat, sp, pr, channels):
    o = by_name(f"{CAT}/productOffering", name)
    if o:
        print(f"draft exists: {name} ({o.get('lifecycleStatus')})")
        return o
    o = req("POST", f"{CAT}/productOffering", {
        "name": name, "description": description, "lifecycleStatus": "In design", "isSellable": True,
        "category": [cat], "productSpecification": {"id": sp["id"], "name": sp["name"], "@referredType": "ProductSpecification"},
        "productOfferingPrice": [{"id": pr["id"], "name": pr["name"], "@referredType": "ProductOfferingPrice"}],
        "channel": [{"id": c} for c in channels]})
    print(f"draft: {name}")
    return o


if TENANT == "enet":
    topups = category("Top-ups")
    inside = draft("Social 10 GB", "10 GB for 30 days — WhatsApp, Instagram and TikTok never count against it.", topups,
                   spec("Social 10 GB Service", {"Data": "10 GB", "Validity": "30 days", "zeroRatedApps": "WhatsApp, Instagram, TikTok"}),
                   price("Social 10 GB", 1500, "oneTime"), ["web", "app", "store"])
    outside = draft("Business Unlimited 5G", "Unlimited 5G data for a company line with priority support.", category("Business"),
                    spec("Business Unlimited 5G Service", {"Data": "Unlimited", "Validity": "30 days"}),
                    price("Business Unlimited 5G monthly", 12000, "recurring"), ["business", "telesales"])
else:
    mobile = category("Mobile plans")
    inside = draft("Taranga Social 30 GB", "30 GB on 5G — WhatsApp, Instagram, TikTok and Snapchat never count. No binding.", mobile,
                   spec("Taranga Social 30 GB Service", {"Data": "30 GB", "Validity": "30 days", "zeroRatedApps": "WhatsApp, Instagram, TikTok, Snapchat"}),
                   price("Taranga Social 30 GB monthly", 349, "recurring"), ["web", "app", "store"])
    outside = draft("Taranga Unlimited Pro", "Unlimited 5G at full speed with 100 GB EU/EEA roaming and a priority slice all month.", mobile,
                    spec("Taranga Unlimited Pro Service", {"Data": "Unlimited", "Validity": "30 days", "sliceProfile": "priority"}),
                    price("Taranga Unlimited Pro monthly", 599, "recurring"), ["web", "app"])

print(f"done: {TENANT} — inside='{inside and inside['name']}' outside='{outside and outside['name']}'")
