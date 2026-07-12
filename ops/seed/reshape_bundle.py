#!/usr/bin/env python3
"""Reshape GenAlpha One into a configurable bundle: the phone slot becomes a
choice of three devices, each with color/storage characteristics."""
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


def chars(*pairs):
    return [{"name": name, "valueType": "string",
             "productSpecCharacteristicValue": [{"value": v} for v in values]}
            for name, values in pairs]


def ref(entity, rt):
    return {"id": entity["id"], "href": entity.get("href"), "name": entity["name"], "@referredType": rt}


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
specs = {s["name"]: s for s in req("GET", "productSpecification?limit=100")}


def ensure_spec(name, brand, characteristics):
    if name in specs:
        updated = req("PATCH", f"productSpecification/{specs[name]['id']}",
                      {"productSpecCharacteristic": characteristics})
    else:
        updated = req("POST", "productSpecification",
                      {"name": name, "brand": brand, "lifecycleStatus": "Active",
                       "productSpecCharacteristic": characteristics})
    specs[name] = updated
    print("spec:", name)
    return updated


def ensure_price(name, value):
    if name not in prices:
        prices[name] = req("POST", "productOfferingPrice", {
            "name": name, "priceType": "recurring",
            "price": {"unit": "EUR", "value": value},
            "recurringChargePeriodType": "month", "recurringChargePeriodLength": 1,
            "lifecycleStatus": "Active", "version": "1.0"})
    print("price:", name)
    return prices[name]


def ensure_phone_offering(name, description, spec, price):
    body = {
        "description": description,
        "productSpecification": ref(spec, "ProductSpecification"),
        "productOfferingPrice": [ref(price, "ProductOfferingPrice")],
    }
    if name in offerings:
        updated = req("PATCH", f"productOffering/{offerings[name]['id']}", body)
    else:
        updated = req("POST", "productOffering",
                      {"name": name, "lifecycleStatus": "Active", "version": "1.0",
                       "isBundle": False, **body})
    offerings[name] = updated
    print("offering:", name)
    return updated


# 1. Phone specs with variant characteristics
spec_pro = ensure_spec("Apple iPhone 17 Pro", "Apple",
                       chars(("color", ["Black Titanium", "Blue Titanium", "Natural Titanium"]),
                             ("storage", ["256GB", "512GB", "1TB"])))
spec_std = ensure_spec("Apple iPhone 17", "Apple",
                       chars(("color", ["Black", "White", "Teal", "Pink"]),
                             ("storage", ["128GB", "256GB"])))
spec_sam = ensure_spec("Samsung Galaxy S26", "Samsung",
                       chars(("color", ["Phantom Black", "Cream", "Icy Blue"]),
                             ("storage", ["256GB", "512GB"])))

# 2. Phone offerings, each with its own installment price
price_pro = prices.get("iPhone 17 Pro Installment (24 months)") or ensure_price("iPhone 17 Pro Installment (24 months)", 45.79)
price_std = ensure_price("iPhone 17 Installment (24 months)", 33.29)
price_sam = ensure_price("Galaxy S26 Installment (24 months)", 37.49)

# Rename the storage-suffixed offering: storage is a characteristic now.
if "Apple iPhone 17 Pro 256GB" in offerings and "Apple iPhone 17 Pro" not in offerings:
    o = offerings.pop("Apple iPhone 17 Pro 256GB")
    offerings["Apple iPhone 17 Pro"] = req("PATCH", f"productOffering/{o['id']}", {"name": "Apple iPhone 17 Pro"})
    print("renamed: Apple iPhone 17 Pro 256GB -> Apple iPhone 17 Pro")

po_pro = ensure_phone_offering("Apple iPhone 17 Pro",
                               "Apple's flagship: ProMotion display, titanium body, 24-month installments.",
                               spec_pro, price_pro)
po_std = ensure_phone_offering("Apple iPhone 17",
                               "The iPhone for everyone, on 24-month installments.",
                               spec_std, price_std)
po_sam = ensure_phone_offering("Samsung Galaxy S26",
                               "Samsung's flagship with Galaxy AI, on 24-month installments.",
                               spec_sam, price_sam)

# 3b. An optional, standalone-purchasable add-on: TMF620 soft-bundle
#     cardinality lower=0 means the customer MAY include it (0..1).
price_sports = ensure_price("Sports Pass Monthly", 12.99)
sports = ensure_phone_offering(
    "GenAlpha Sports Pass",
    "Every match, live — an optional add-on you can drop onto the bundle or buy on its own.",
    spec_std, price_sports)


def mandatory(entity):
    """A fixed inclusion: TMF620 bundledProductOfferingOption lower=upper=1."""
    return {**ref(entity, "ProductOffering"),
            "bundledProductOfferingOption": {"numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 1}}


def optional(entity):
    """A standalone-purchasable add-on: lower=0 (may include), upper=1."""
    return {**ref(entity, "ProductOffering"),
            "bundledProductOfferingOption": {"numberRelOfferLowerLimit": 0, "numberRelOfferUpperLimit": 1}}


# 3. The bundle: fixed (mandatory) components + a phone choice group (pick
#    exactly 1) + an optional add-on. Phone installments move off the bundle's
#    own price list (the chosen option carries its price).
bundle = offerings["GenAlpha One Home & Mobile"]
fixed = [mandatory(offerings["GenAlpha Mobile Unlimited 5G"]),
         mandatory(offerings["GenAlpha Fiber 1000"]),
         mandatory(offerings["GenAlpha TV Max"])]
choice = {
    "@type": "BundledProductOfferingChoice",
    "name": "Choose your phone",
    "default": po_pro["id"],
    "numberRelOfferLowerLimit": 1,
    "numberRelOfferUpperLimit": 1,
    "options": [ref(po_pro, "ProductOffering"), ref(po_std, "ProductOffering"), ref(po_sam, "ProductOffering")],
}
bundle_prices = [ref(prices["Mobile Unlimited 5G Monthly"], "ProductOfferingPrice"),
                 ref(prices["Fiber 1000 Monthly"], "ProductOfferingPrice"),
                 ref(prices["TV Max Monthly"], "ProductOfferingPrice"),
                 ref(prices["GenAlpha One Bundle Discount"], "ProductOfferingPrice")]
req("PATCH", f"productOffering/{bundle['id']}", {
    "bundledProductOffering": fixed + [choice, optional(sports)],
    "productOfferingPrice": bundle_prices,
    "description": "Triple-play bundle: unlimited 5G mobile with the phone of your choice, "
                   "1 Gbps fiber broadband and TV Max — one bill, bundle discount included. "
                   "Add Sports Pass if you want it.",
})
print("bundle reshaped: 3 mandatory components + phone choice (pick 1 of 3) + optional Sports Pass")
