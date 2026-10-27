#!/usr/bin/env python3
"""Benefit Choices on the Taranga demo tenant (#362), idempotent.

Taranga calls a Choice a "Pick" (tenants.yml `choices-label`) and sells in
Norway, so no benefit holds its Pick: holds are law-as-data and Norway's is 0
until the bindingstid question is answered. Six benefits in three groups, every
partner brand invented — the repo names no real partner.

Taranga runs launch governance, so this signs in as its approver (henrik):
an approver's new benefit goes on sale at once, and one that waits (a draft
left by someone else) is launched through the same governance door a person
would use, so the launch is on record like any other.
"""
import json
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/taranga/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
POLICY = "http://localhost:8080/tmf-api/policyManagement/v4"

GRANTS = {"Taranga Mobile Priority 5G": 3, "Taranga Mobile Unlimited 5G": 2, "Taranga Mobile 20 GB": 1}
# (name, group, list price NOK/month, what the partner charges Taranga a month, partner, what the customer reads)
BENEFITS = [
    ("StreamCo Standard", "Streaming", 129, 45, "StreamCo",
     "Films and series from StreamCo — picked with one of your Picks, no extra on your bill."),
    ("SportsCo Live", "Streaming", 249, 90, "SportsCo",
     "Every match SportsCo carries, live and on demand, with one of your Picks."),
    ("Lydbok+ Audiobooks", "Audio", 179, 60, "Lydbok+",
     "Unlimited audiobooks and podcasts from Lydbok+, with one of your Picks."),
    ("TuneCo Music", "Audio", 119, 40, "TuneCo",
     "Ad-free music from TuneCo, offline too, with one of your Picks."),
    ("AskCo Assistant Plus", "AI", 229, 80, "AskCo",
     "AskCo's assistant on its paid tier — longer answers, files and images — with one of your Picks."),
    ("CloudCo Photos 1 TB", "AI", 99, 25, "CloudCo",
     "1 TB for your photos with CloudCo's smart search, with one of your Picks."),
]
SWAP_RULE = "Choices: 3 swaps a quarter"


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "henrik@taranga.example", "password": "henrik"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def call(base, method, path, body=None):
    r = urllib.request.Request(f"{base}/{path}", data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}",
                                        "X-Channel": "seed"}, method=method)
    with urllib.request.urlopen(r) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def every(path):
    """The whole collection, page by page — a page is capped at 100 and this catalog is larger."""
    found, offset = [], 0
    while True:
        page = call(CATALOG, "GET", f"{path}{'&' if '?' in path else '?'}limit=100&offset={offset}") or []
        found += page
        if len(page) < 100:
            return found
        offset += 100


def ref(entity, rt):
    return {"id": entity["id"], "name": entity["name"], "@referredType": rt}


offerings = {o["name"]: o for o in every("productOffering")}
prices = {p["name"]: p for p in every("productOfferingPrice")}
cats = {c["name"]: c for c in every("category")}
for name in ["Partner services", "Choices"] + sorted({b[1] for b in BENEFITS}):
    if name not in cats:
        cats[name] = call(CATALOG, "POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")

for plan, n in GRANTS.items():
    o = offerings.get(plan)
    if not o:
        print(f"skip: no plan named {plan}")
        continue
    if o.get("choicesGranted") != n:
        call(CATALOG, "PATCH", f"productOffering/{o['id']}", {"choicesGranted": n})
        print(f"plan: {plan} grants {n} Pick(s) per line")

for name, group, value, wholesale, partner, description in BENEFITS:
    price_name = f"{name} Monthly"
    if price_name not in prices:
        prices[price_name] = call(CATALOG, "POST", "productOfferingPrice", {
            "name": price_name, "priceType": "recurring", "lifecycleStatus": "Active",
            "recurringChargePeriodType": "month", "price": {"unit": "NOK", "value": value}})
    o = offerings.get(name)
    if o is None:
        o = call(CATALOG, "POST", "productOffering", {
            "name": name, "description": description, "isBundle": False, "choiceCost": 1,
            "choicePartner": partner, "choiceWholesaleCost": wholesale,
            # the first category decides fulfilment (a partner activation); the others shelve it
            "category": [ref(cats[c], "Category") for c in ("Partner services", "Choices", group)],
            "productOfferingPrice": [ref(prices[price_name], "ProductOfferingPrice")]})
        print(f"benefit: {name} made — {o.get('lifecycleStatus')}")
    if o.get("lifecycleStatus") not in ("Active", "Launched"):
        try:
            call(CATALOG, "POST", f"productOffering/{o['id']}/governance/launch",
                 {"force": True, "note": "Benefit Choices demo seed (#362)"})
            print(f"benefit: {name} launched by the approver")
        except urllib.error.HTTPError as e:
            print(f"benefit: {name} NOT launched — {e.code} {e.read().decode()[:200]}")

if not any(r.get("name") == SWAP_RULE for r in call(POLICY, "GET", "policyRule?limit=200") or []):
    call(POLICY, "POST", "policyRule", {
        "name": SWAP_RULE, "domain": "order", "effect": "deny", "priority": 50, "enabled": True,
        "description": "A household may swap a benefit for another three times a calendar quarter.",
        "condition": json.dumps({"and": [{"==": [{"var": "choiceSwap"}, True]},
                                         {">=": [{"var": "choiceSwapsThisQuarter"}, 3]}]}),
        "message": "You have used this quarter's 3 swaps. The next swap is possible from {nextQuarterStarts}."})
    print(f"rule: {SWAP_RULE}")

print("Taranga Picks seeded: three granting plans, six benefits in three groups, the swap limit.")
