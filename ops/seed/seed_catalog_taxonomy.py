#!/usr/bin/env python3
"""Catalog taxonomy + realistic tariff ladder (idempotent).

Gives the catalog what a real operator's has:
- TMF620 categories: Mobile plans / Broadband / Devices / TV & Add-ons /
  Top-ups / Bundles — channels use these to separate "my plan" from add-ons
  and to offer only like-for-like plan changes.
- A data ladder: Mobile 10 GB and Mobile 50 GB below Unlimited 5G, each with
  a TMF635 allowance so the usage meters show included data.
- A one-time Data Top-Up 5 GB (boost allowance): buying it extends the
  buyer's current-month meter and raises the overage threshold.
Also sweeps stray E2E-* throwaway offerings from failed test runs.
"""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
USAGE = "http://localhost:8080/tmf-api/usageManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, url, body=None):
    r = urllib.request.Request(
        url,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp) if resp.length != 0 else {}


# ---- 1. categories -------------------------------------------------------
CATEGORIES = ["Mobile plans", "Broadband", "Devices", "TV & Add-ons", "Top-ups", "Bundles"]
existing_cats = {c["name"]: c for c in req("GET", f"{CATALOG}/category?limit=100")}
cats = {}
for name in CATEGORIES:
    if name in existing_cats:
        cats[name] = existing_cats[name]
        print(f"exists: category {name}")
    else:
        cats[name] = req("POST", f"{CATALOG}/category", {
            "name": name, "lifecycleStatus": "Active"})
        print(f"created: category {name}")


def cat_ref(name):
    return {"id": cats[name]["id"], "name": name, "@referredType": "Category"}


# ---- 2. sweep stray E2E offerings ----------------------------------------
offerings = req("GET", f"{CATALOG}/productOffering?limit=100")
for o in offerings:
    if o["name"].startswith("E2E "):
        req("DELETE", f"{CATALOG}/productOffering/{o['id']}")
        print(f"swept: {o['name']}")
offerings = {o["name"]: o for o in req("GET", f"{CATALOG}/productOffering?limit=100")}

# ---- 3. tag existing offerings -------------------------------------------
TAGS = {
    "GenAlpha Mobile Unlimited 5G": "Mobile plans",
    "GenAlpha Postpaid Mobile (ID verified)": "Mobile plans",
    "GenAlpha Fiber 1000": "Broadband",
    "Samsung Galaxy S26": "Devices",
    "Apple iPhone 17 Pro": "Devices",
    "Apple iPhone 17": "Devices",
    "GenAlpha TV Max": "TV & Add-ons",
    "GenAlpha Sports Pass": "TV & Add-ons",
    "GenAlpha One Home & Mobile": "Bundles",
    # self-heal: these are created below, but tag them here too in case they
    # were born before the catalog persisted categories
    "GenAlpha Mobile 10 GB": "Mobile plans",
    "GenAlpha Mobile 50 GB": "Mobile plans",
    "Data Top-Up 5 GB": "Top-ups",
}
for name, cat in TAGS.items():
    o = offerings.get(name)
    if not o:
        print(f"SKIP missing offering: {name}")
        continue
    if any(c.get("name") == cat for c in (o.get("category") or [])):
        print(f"exists: {name} -> {cat}")
        continue
    req("PATCH", f"{CATALOG}/productOffering/{o['id']}", {"category": [cat_ref(cat)]})
    print(f"tagged: {name} -> {cat}")

# ---- 4. the data ladder + top-up -----------------------------------------
prices = {p["name"]: p for p in req("GET", f"{CATALOG}/productOfferingPrice?limit=100")}


def ensure_price(name, price_type, value, period=None):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": price_type,
            "price": {"unit": "EUR", "value": value}, "lifecycleStatus": "Active"}
    if period:
        body["recurringChargePeriodType"] = period
    p = req("POST", f"{CATALOG}/productOfferingPrice", body)
    print(f"created price: {name}")
    return p


def ensure_offering(name, category, price, description):
    if name in offerings:
        print(f"exists: {name}")
        return offerings[name]
    o = req("POST", f"{CATALOG}/productOffering", {
        "name": name, "description": description, "lifecycleStatus": "Active",
        "isBundle": False, "category": [cat_ref(category)],
        "productOfferingPrice": [{"id": price["id"], "name": price["name"]}]})
    print(f"created offering: {name}")
    return o


ten = ensure_offering(
    "GenAlpha Mobile 10 GB", "Mobile plans",
    ensure_price("Mobile 10 GB Monthly", "recurring", 15.00, "month"),
    "10 GB data, unlimited calls & texts")
fifty = ensure_offering(
    "GenAlpha Mobile 50 GB", "Mobile plans",
    ensure_price("Mobile 50 GB Monthly", "recurring", 20.00, "month"),
    "50 GB data, unlimited calls & texts")
topup = ensure_offering(
    "Data Top-Up 5 GB", "Top-ups",
    ensure_price("Data Top-Up 5 GB", "oneTime", 5.99),
    "5 GB extra data for the current month — one-time purchase")

# ---- 5. allowances so meters show included data ---------------------------
existing_allow = {(a["productOffering"]["id"], a["usageType"])
                  for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}
ALLOWANCES = [
    (ten, "Mobile data", 10, 1.00, False),
    (fifty, "Mobile data", 50, 0.50, False),
    (topup, "Mobile data", 5, 1.00, True),   # boost: buying = +5 GB this month
]
for offering, spec, value, overage, boost in ALLOWANCES:
    if (offering["id"], spec) in existing_allow:
        print(f"exists: allowance {offering['name']} / {spec}")
        continue
    req("POST", f"{USAGE}/usageAllowance", {
        "productOffering": {"id": offering["id"], "name": offering["name"]},
        "usageType": spec,
        "allowance": {"value": value, "units": "GB"},
        "overagePrice": {"value": overage, "unit": "EUR"},
        "boost": boost})
    print(f"allowance: {offering['name']} = {value} GB {spec}"
          + (" (boost)" if boost else ""))

print("taxonomy seeded.")
