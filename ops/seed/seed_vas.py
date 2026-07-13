#!/usr/bin/env python3
"""Value-added services (idempotent): the category decides FULFILMENT.

- Partner services  -> the SOM activates an ENTITLEMENT with the partner
                       (activation code), never a phone number.
- Security          -> a feature service on the account: active, no resources.
- Insurance         -> billing-only: a priced product, no service at all.

Seeds Netflix Standard, GenAlpha Secure Net and GenAlpha Device Care, and
drops all three onto Family Max as an optional "Peace of mind & entertainment"
choice group (0..3) — included in the high-end package story, opt-in priced
individually everywhere else.
"""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        f"{API}/{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def ref(entity, rt):
    return {"id": entity["id"], "href": entity.get("href"),
            "name": entity["name"], "@referredType": rt}


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
cats = {c["name"]: c for c in req("GET", "category?limit=100")}

for name in ("Partner services", "Security", "Insurance"):
    if name not in cats:
        cats[name] = req("POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")


def ensure_price(name, value):
    if name not in prices:
        prices[name] = req("POST", "productOfferingPrice", {
            "name": name, "priceType": "recurring", "lifecycleStatus": "Active",
            "recurringChargePeriodType": "month",
            "price": {"unit": "EUR", "value": value}})
        print(f"price: {name}")
    return prices[name]


def ensure_vas(name, category, price_name, value, description):
    price = ensure_price(price_name, value)
    if name in offerings:
        return offerings[name]
    offerings[name] = req("POST", "productOffering", {
        "name": name, "description": description,
        "lifecycleStatus": "Active", "isBundle": False,
        "category": [{"id": cats[category]["id"], "name": category, "@referredType": "Category"}],
        "productOfferingPrice": [ref(price, "ProductOfferingPrice")]})
    print(f"offering: {name} ({category})")
    return offerings[name]


netflix = ensure_vas(
    "Netflix Standard", "Partner services", "Netflix Standard Monthly", 12.99,
    "Netflix on your GenAlpha bill. Activation code delivered instantly — "
    "redeem it with Netflix, manage the account there.")
secure = ensure_vas(
    "GenAlpha Secure Net", "Security", "Secure Net Monthly", 3.99,
    "Network-level protection: scam-call shield, phishing and malware "
    "filtering on every line — switched on the moment you order.")
insurance = ensure_vas(
    "GenAlpha Device Care", "Insurance", "Device Care Monthly", 7.99,
    "Screen and damage cover for your phone. No activation needed — "
    "you're covered from the first bill.")

# Family Max gains an optional VAS group (0..3) — the high-end package story.
fm = offerings.get("GenAlpha Family Max")
if fm:
    full = req("GET", f"productOffering/{fm['id']}")
    groups = full.get("bundledProductOffering") or []
    if not any(g.get("name") == "Peace of mind & entertainment" for g in groups):
        groups.append({
            "@type": "BundledProductOfferingChoice",
            "name": "Peace of mind & entertainment",
            "numberRelOfferLowerLimit": 0, "numberRelOfferUpperLimit": 3,
            "options": [ref(netflix, "ProductOffering"), ref(secure, "ProductOffering"),
                        ref(insurance, "ProductOffering")]})
        req("PATCH", f"productOffering/{fm['id']}", {"bundledProductOffering": groups})
        print("Family Max: + 'Peace of mind & entertainment' (pick up to 3)")

print("VAS seeded: partner entitlement, security feature, billing-only insurance.")
