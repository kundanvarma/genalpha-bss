#!/usr/bin/env python3
"""Benefit Choices (#354), idempotent: plans that grant Choices, benefits that cost them.

- GenAlpha Mobile Unlimited 5G grants 2 Choices; 60 GB 5G and 50 GB grant 1 each.
- Two partner benefits, 1 Choice each, in "Partner services" (so the orchestrator
  activates them with the partner) and "Choices" (so a shelf can find them).
- The swap limit (#356) as a policy rule, data the operator can change: 3 swaps
  a quarter; the refusal names the day the next one is possible.

The partner brands are invented on purpose: the repo names no real partner.
Choices are on for a tenant only where infra/tenants/tenants.yml says so.
"""
import json
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"

GRANTS = {"GenAlpha Mobile Unlimited 5G": 2, "GenAlpha Mobile 60 GB 5G": 1, "GenAlpha Mobile 50 GB": 1}
BENEFITS = [
    ("StreamCo Standard", "StreamCo Standard Monthly", 9.99,
     "Films and series from StreamCo, picked with one of your Choices. "
     "Your activation code arrives the moment you pick it.", 0, "StreamCo", 4.00),
    ("Lydbok+ Audiobooks", "Lydbok+ Monthly", 8.99,
     "Unlimited audiobooks and podcasts from Lydbok+, picked with one of your Choices.", 0, "Lydbok+", 3.00),
    # a subsidised device offer HOLDS its Choice (#357): capped by the law of the
    # tenant's country — 24 months in the EU, 0 in Norway until bindingstid is answered
    ("PhoneCo Upgrade Pass", "PhoneCo Upgrade Pass Monthly", 0.01,
     "Upgrade your phone every year with PhoneCo — it holds one Choice for 12 months.", 12, "PhoneCo", 0.00),
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

for name, price_name, value, description, hold, partner, wholesale in BENEFITS:
    if price_name not in prices:
        prices[price_name] = req("POST", "productOfferingPrice", {
            "name": price_name, "priceType": "recurring", "lifecycleStatus": "Active",
            "recurringChargePeriodType": "month", "price": {"unit": "EUR", "value": value}})
    if name in offerings:
        o = offerings[name]
        if (o.get("choiceCost") != 1 or (o.get("choiceHoldMonths") or 0) != hold
                or o.get("choicePartner") != partner or o.get("choiceWholesaleCost") != wholesale):
            req("PATCH", f"productOffering/{o['id']}", {"choiceCost": 1, "choicePartner": partner,
                                                        "choiceWholesaleCost": wholesale,
                                                        **({"choiceHoldMonths": hold} if hold else {})})
        continue
    offerings[name] = req("POST", "productOffering", {
        "name": name, "description": description, "lifecycleStatus": "Active", "isBundle": False,
        "choiceCost": 1,
        **({"choiceHoldMonths": hold} if hold else {}),
        # the partner sells; the operator pays them this a month per active benefit (#360)
        "choicePartner": partner, "choiceWholesaleCost": wholesale,
        # the first category decides fulfilment (a partner activation); the second shelves it
        "category": [{"id": cats["Partner services"]["id"], "name": "Partner services", "@referredType": "Category"},
                     {"id": cats["Choices"]["id"], "name": "Choices", "@referredType": "Category"}],
        "productOfferingPrice": [ref(prices[price_name], "ProductOfferingPrice")]})
    print(f"benefit: {name} (1 Choice)")

# the swap limit is a rule, not code: ordering hands it the facts, the operator sets the number
POLICY = "http://localhost:8080/tmf-api/policyManagement/v4"
SWAP_RULE = "Choices: 3 swaps a quarter"


def policy(method, path, body=None):
    r = urllib.request.Request(f"{POLICY}/{path}",
                               data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                               method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


if not any(r.get("name") == SWAP_RULE for r in policy("GET", "policyRule?limit=200") or []):
    policy("POST", "policyRule", {
        "name": SWAP_RULE, "domain": "order", "effect": "deny", "priority": 50, "enabled": True,
        "description": "A household may swap a benefit for another three times a calendar quarter.",
        "condition": json.dumps({"and": [{"==": [{"var": "choiceSwap"}, True]},
                                         {">=": [{"var": "choiceSwapsThisQuarter"}, 3]}]}),
        "message": "You have used this quarter's 3 swaps. The next swap is possible from {nextQuarterStarts}."})
    print(f"rule: {SWAP_RULE}")

print("Choices seeded: three granting plans, three benefits (one held 12 months), the swap limit.")
