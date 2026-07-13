#!/usr/bin/env python3
"""Seed the Nova Telecom demo tenant: a small plan-only catalog, created by
Nova's own staff identity — the tenant lands in 'nova' because the token's
issuer is the nova realm, not because anything here says so."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/nova/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
USAGE = "http://localhost:8080/tmf-api/usageManagement/v4"


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
        path if path.startswith("http") else f"{API}/{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def ref(resource, referred_type):
    return {"id": resource["id"], "href": resource.get("href"),
            "name": resource.get("name"), "@referredType": referred_type}


existing = {o["name"] for o in req("GET", "productOffering?limit=100")}
if "Nova Unlimited 5G" in existing:
    # self-heal: Nova is a NORWEGIAN operator — its price is NOK. Older seeds
    # wrote EUR; repatch so the white-label demo shows real multi-currency.
    for p in req("GET", "productOfferingPrice?limit=100"):
        if p["name"] == "Nova Unlimited 5G Monthly" and p.get("price", {}).get("unit") != "NOK":
            req("PATCH", f"productOfferingPrice/{p['id']}",
                {"price": {"unit": "NOK", "value": 299.00}})
            print("repriced: Nova Unlimited 5G -> 299.00 NOK/month")
    print("exists: Nova base catalog already seeded")

if "Nova Unlimited 5G" not in existing:
    spec = req("POST", "productSpecification", {
    "name": "Nova 5G Subscription", "brand": "Nova", "lifecycleStatus": "Active"})
    offering = req("POST", "productOffering", {
    "name": "Nova Unlimited 5G", "description": "Unlimited data on the Nova 5G network.",
    "lifecycleStatus": "Active", "isBundle": False, "isSellable": True,
    "productSpecification": ref(spec, "ProductSpecification")})
    price = req("POST", "productOfferingPrice", {
    "name": "Nova Unlimited 5G Monthly", "priceType": "recurring",
    "recurringChargePeriodType": "month", "lifecycleStatus": "Active",
    "price": {"unit": "NOK", "value": 299.00}})
    req("PATCH", f"productOffering/{offering['id']}", {
    "productOfferingPrice": [{"id": price["id"], "href": price.get("href"),
                              "name": price["name"], "@referredType": "ProductOfferingPrice"}]})
    print(f"seeded: Nova Unlimited 5G ({offering['id']}) at 299.00 NOK/month")


# ---- the fuller Norwegian catalog: taxonomy, a second tier, a top-up -------
offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
cats = {c["name"]: c for c in req("GET", "category?limit=100")}
# internal taxonomy keys shared by every channel (display names come from i18n)
for name in ("Mobile plans", "Top-ups"):
    if name not in cats:
        cats[name] = req("POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"created: category {name}")


def cat_ref(name):
    return {"id": cats[name]["id"], "name": name, "@referredType": "Category"}


def ensure_price(name, price_type, value, period=None):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": price_type,
            "price": {"unit": "NOK", "value": value}, "lifecycleStatus": "Active"}
    if period:
        body["recurringChargePeriodType"] = period
    prices[name] = req("POST", "productOfferingPrice", body)
    print(f"created price: {name}")
    return prices[name]


def ensure_offering(name, category, price, description):
    if name in offerings:
        if not any(c.get("name") == category for c in (offerings[name].get("category") or [])):
            req("PATCH", f"productOffering/{offerings[name]['id']}", {"category": [cat_ref(category)]})
            print(f"tagged: {name} -> {category}")
        return offerings[name]
    offerings[name] = req("POST", "productOffering", {
        "name": name, "description": description, "lifecycleStatus": "Active",
        "isBundle": False, "category": [cat_ref(category)],
        "productOfferingPrice": [{"id": price["id"], "name": price["name"]}]})
    print(f"created offering: {name}")
    return offerings[name]


ensure_offering("Nova Unlimited 5G", "Mobile plans",
                prices.get("Nova Unlimited 5G Monthly"), "Ubegrenset data")
smart = ensure_offering(
    "Nova Smart 15 GB", "Mobile plans",
    ensure_price("Nova Smart 15 GB Monthly", "recurring", 199.00, "month"),
    "15 GB data, fri tale og SMS")
topup = ensure_offering(
    "Nova Datapåfyll 5 GB", "Top-ups",
    ensure_price("Nova Datapåfyll 5 GB", "oneTime", 59.00),
    "5 GB ekstra data denne måneden — engangskjøp")

# allowances so meters show included data (tenant-scoped: nova token = nova rows)
existing_allow = {(a["productOffering"]["id"], a["usageType"])
                  for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}
for offering, value, overage, boost in ((smart, 15, 10.0, False), (topup, 5, 10.0, True)):
    if (offering["id"], "Mobildata") in existing_allow:
        continue
    req("POST", f"{USAGE}/usageAllowance", {
        "productOffering": {"id": offering["id"], "name": offering["name"]},
        "usageType": "Mobildata",
        "allowance": {"value": value, "units": "GB"},
        "overagePrice": {"value": overage, "unit": "NOK"},
        "boost": boost})
    print(f"allowance: {offering['name']} = {value} GB Mobildata" + (" (boost)" if boost else ""))

print("Nova catalog extended: two tiers + top-up, all NOK.")
