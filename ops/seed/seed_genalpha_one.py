#!/usr/bin/env python3
"""Seed the GenAlpha One triple-play product family through the gateway TMF620 API."""
import json
import urllib.error
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password",
        "client_id": "bss-demo",
        "username": "demo",
        "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def find_by_name(path, name):
    """The row this seed already made, if any — matched on the exact name."""
    q = urllib.parse.urlencode({"name": name, "limit": 100})
    req = urllib.request.Request(f"{API}/{path}?{q}",
                                 headers={"Authorization": f"Bearer {TOKEN}"})
    try:
        with urllib.request.urlopen(req) as r:
            rows = json.load(r)
    except urllib.error.HTTPError:
        return None
    return next((x for x in rows if x.get("name") == name), None)


def post(path, body):
    # IDEMPOTENT, as the README promises and CI relies on. This used to POST
    # blindly, so a second run minted a twin of every offering, spec and price:
    # a second "GenAlpha Mobile Unlimited 5G" with no category, which the shop
    # never listed but a customer's product could still point at — and then
    # the shop refused to offer that customer a plan change.
    existing = find_by_name(path, body.get("name")) if body.get("name") else None
    if existing is not None:
        print(f"reused {path}: {existing['name']} ({existing['id']})")
        return existing
    req = urllib.request.Request(
        f"{API}/{path}",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method="POST",
    )
    with urllib.request.urlopen(req) as r:
        created = json.load(r)
    print(f"created {path}: {created['name']} ({created['id']})")
    return created


def ref(entity, referred_type):
    return {"id": entity["id"], "href": entity["href"], "name": entity["name"],
            "@referredType": referred_type}


# 1. Product specifications
spec_iphone = post("productSpecification", {"name": "Apple iPhone 17 Pro", "brand": "Apple", "lifecycleStatus": "Active"})
spec_mobile = post("productSpecification", {"name": "5G Mobile Subscription", "brand": "GenAlpha", "lifecycleStatus": "Active"})
spec_fiber = post("productSpecification", {"name": "Fiber Broadband 1000", "brand": "GenAlpha", "lifecycleStatus": "Active"})
spec_tv = post("productSpecification", {"name": "IPTV Service", "brand": "GenAlpha", "lifecycleStatus": "Active"})

# 2. Component offerings
po_mobile = post("productOffering", {
    "name": "GenAlpha Mobile Unlimited 5G",
    "description": "Unlimited data, calls and texts on the 5G network.",
    "lifecycleStatus": "Active", "version": "1.0", "isBundle": False,
    "productSpecification": ref(spec_mobile, "ProductSpecification"),
})
po_iphone = post("productOffering", {
    "name": "Apple iPhone 17 Pro 256GB",
    "description": "Apple iPhone 17 Pro, 256GB, sold with a mobile plan on 24-month installments.",
    "lifecycleStatus": "Active", "version": "1.0", "isBundle": False,
    "productSpecification": ref(spec_iphone, "ProductSpecification"),
})
po_fiber = post("productOffering", {
    "name": "GenAlpha Fiber 1000",
    "description": "1 Gbps symmetric fiber broadband with Wi-Fi 7 router included.",
    "lifecycleStatus": "Active", "version": "1.0", "isBundle": False,
    "productSpecification": ref(spec_fiber, "ProductSpecification"),
})
po_tv = post("productOffering", {
    "name": "GenAlpha TV Max",
    "description": "IPTV with 150+ channels, catch-up TV and multi-screen streaming.",
    "lifecycleStatus": "Active", "version": "1.0", "isBundle": False,
    "productSpecification": ref(spec_tv, "ProductSpecification"),
})

# 3. The bundle
bundle = post("productOffering", {
    "name": "GenAlpha One Home & Mobile",
    "description": "Triple-play bundle: unlimited 5G mobile with Apple iPhone 17 Pro, "
                   "1 Gbps fiber broadband and TV Max — one bill, bundle discount included.",
    "lifecycleStatus": "Active", "version": "1.0", "isBundle": True,
    "bundledProductOffering": [
        ref(po_mobile, "ProductOffering"),
        ref(po_iphone, "ProductOffering"),
        ref(po_fiber, "ProductOffering"),
        ref(po_tv, "ProductOffering"),
    ],
})

# 4. Prices
post("productOfferingPrice", {"name": "Mobile Unlimited 5G Monthly", "priceType": "recurring",
     "price": {"unit": "EUR", "value": 25.00}, "recurringChargePeriodType": "month",
     "recurringChargePeriodLength": 1, "lifecycleStatus": "Active", "version": "1.0"})
post("productOfferingPrice", {"name": "iPhone 17 Pro Installment (24 months)", "priceType": "recurring",
     "price": {"unit": "EUR", "value": 45.79}, "recurringChargePeriodType": "month",
     "recurringChargePeriodLength": 1, "lifecycleStatus": "Active", "version": "1.0"})
post("productOfferingPrice", {"name": "Fiber 1000 Monthly", "priceType": "recurring",
     "price": {"unit": "EUR", "value": 39.99}, "recurringChargePeriodType": "month",
     "recurringChargePeriodLength": 1, "lifecycleStatus": "Active", "version": "1.0"})
post("productOfferingPrice", {"name": "TV Max Monthly", "priceType": "recurring",
     "price": {"unit": "EUR", "value": 14.99}, "recurringChargePeriodType": "month",
     "recurringChargePeriodLength": 1, "lifecycleStatus": "Active", "version": "1.0"})
post("productOfferingPrice", {"name": "GenAlpha One Bundle Discount", "priceType": "recurring",
     "price": {"unit": "EUR", "value": -15.00}, "recurringChargePeriodType": "month",
     "recurringChargePeriodLength": 1, "isBundle": True, "lifecycleStatus": "Active", "version": "1.0"})
post("productOfferingPrice", {"name": "Fiber Installation Fee", "priceType": "oneTime",
     "price": {"unit": "EUR", "value": 49.00}, "lifecycleStatus": "Active", "version": "1.0"})

print("\nbundle id:", bundle["id"])
