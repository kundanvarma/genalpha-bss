#!/usr/bin/env python3
"""Benefit Choices (#354), idempotent: plans that grant Choices, benefits that cost them.

- GenAlpha Mobile Unlimited 5G grants 2 Choices; GenAlpha Mobile 60 GB 5G grants 1.
- Two partner benefits, 1 Choice each, in "Partner services" (so the orchestrator
  activates them with the partner) and "Choices" (so a shelf can find them).

The partner brands are invented on purpose: the repo names no real partner.
Choices are on for a tenant only where infra/tenants/tenants.yml says so.
"""
import json
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"

GRANTS = {"GenAlpha Mobile Unlimited 5G": 2, "GenAlpha Mobile 60 GB 5G": 1}
BENEFITS = [
    ("StreamCo Standard", "StreamCo Standard Monthly", 9.99,
     "Films and series from StreamCo, picked with one of your Choices. "
     "Your activation code arrives the moment you pick it."),
    ("Lydbok+ Audiobooks", "Lydbok+ Monthly", 8.99,
     "Unlimited audiobooks and podcasts from Lydbok+, picked with one of your Choices."),
]


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "demo", "password": "demo"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(f"{API}/{path}",
                               data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                               method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def ref(entity, rt):
    return {"id": entity["id"], "href": entity.get("href"), "name": entity["name"], "@referredType": rt}


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
cats = {c["name"]: c for c in req("GET", "category?limit=100")}
for name in ("Partner services", "Choices"):
    if name not in cats:
        cats[name] = req("POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")

for plan, n in GRANTS.items():
    o = offerings.get(plan)
    if not o:
        print(f"skip: no plan named {plan}")
        continue
    if o.get("choicesGranted") != n:
        req("PATCH", f"productOffering/{o['id']}", {"choicesGranted": n})
        print(f"plan: {plan} grants {n} Choice(s)")

for name, price_name, value, description in BENEFITS:
    if price_name not in prices:
        prices[price_name] = req("POST", "productOfferingPrice", {
            "name": price_name, "priceType": "recurring", "lifecycleStatus": "Active",
            "recurringChargePeriodType": "month", "price": {"unit": "EUR", "value": value}})
    if name in offerings:
        if offerings[name].get("choiceCost") != 1:
            req("PATCH", f"productOffering/{offerings[name]['id']}", {"choiceCost": 1})
        continue
    offerings[name] = req("POST", "productOffering", {
        "name": name, "description": description, "lifecycleStatus": "Active", "isBundle": False,
        "choiceCost": 1,
        # the first category decides fulfilment (a partner activation); the second shelves it
        "category": [{"id": cats["Partner services"]["id"], "name": "Partner services", "@referredType": "Category"},
                     {"id": cats["Choices"]["id"], "name": "Choices", "@referredType": "Category"}],
        "productOfferingPrice": [ref(prices[price_name], "ProductOfferingPrice")]})
    print(f"benefit: {name} (1 Choice)")

print("Choices seeded: two granting plans, two partner benefits.")
