#!/usr/bin/env python3
"""GenAlpha Family Max — the 'complicated product' demo bundle (idempotent).

One offering exercising every TMF620 modeling tool in the box:
- two FIXED components (fiber + TV): bundledProductOfferingOption 1..1
- a MULTI-SELECT choice group: "Family lines — pick 1–2" of three plans
  (lower=1, upper=2 — the storefront renders checkboxes, ordering enforces it)
- a SINGLE-SELECT choice group: "Choose your phone" (1..1) whose options carry
  configurable characteristics (color/storage) on their product specs
- an OPTIONAL choice group: "Streaming extras — pick up to 2" (0..2)
- a 12-month commitment term (TMF651 agreement minted at completion)
- mixed pricing: a bundle base price + a one-time install fee on the bundle,
  while every chosen option carries its own price ("the price follows the
  configuration")
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


def need(name):
    if name not in offerings:
        raise SystemExit(f"missing prerequisite offering: {name} — run the base seeds first")
    return offerings[name]


def ensure_price(name, price_type, value, period=None):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": price_type, "lifecycleStatus": "Active",
            "price": {"unit": "EUR", "value": value}}
    if period:
        body["recurringChargePeriodType"] = period
    prices[name] = req("POST", "productOfferingPrice", body)
    print(f"price: {name}")
    return prices[name]


# a second streaming add-on so "pick up to 2" has something to pick from
if "GenAlpha Kids TV" not in offerings:
    kids_price = ensure_price("Kids TV Monthly", "recurring", 5.99, "month")
    offerings["GenAlpha Kids TV"] = req("POST", "productOffering", {
        "name": "GenAlpha Kids TV", "lifecycleStatus": "Active", "isBundle": False,
        "description": "Ad-free kids' channels and films, safe by default.",
        "category": [{"id": cats["TV & Add-ons"]["id"], "name": "TV & Add-ons",
                      "@referredType": "Category"}] if "TV & Add-ons" in cats else [],
        "productOfferingPrice": [ref(kids_price, "ProductOfferingPrice")]})
    print("offering: GenAlpha Kids TV")


def mandatory(entity):
    return {**ref(entity, "ProductOffering"),
            "bundledProductOfferingOption": {"numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 1}}


def choice(name, lower, upper, options, default=None):
    return {"@type": "BundledProductOfferingChoice", "name": name,
            **({"default": default} if default else {}),
            "numberRelOfferLowerLimit": lower, "numberRelOfferUpperLimit": upper,
            "options": [ref(o, "ProductOffering") for o in options]}


base_price = ensure_price("Family Max Base Monthly", "recurring", 49.00, "month")
bundle_prices = [ref(base_price, "ProductOfferingPrice")]
if "Fiber Installation Fee" in prices:
    bundle_prices.append(ref(prices["Fiber Installation Fee"], "ProductOfferingPrice"))

body = {
    "name": "GenAlpha Family Max",
    "description": "The whole household on one bill: gigabit fiber and TV Max included, "
                   "one or two mobile lines of your choice, a new phone, and streaming "
                   "extras if you want them. 12-month term.",
    "lifecycleStatus": "Active", "isBundle": True, "version": "1.0",
    "category": [{"id": cats["Bundles"]["id"], "name": "Bundles",
                  "@referredType": "Category"}] if "Bundles" in cats else [],
    "productOfferingTerm": [{"name": "12-month commitment",
                             "duration": {"amount": 12, "units": "month"}}],
    "productOfferingPrice": bundle_prices,
    "bundledProductOffering": [
        mandatory(need("GenAlpha Fiber 1000")),
        mandatory(need("GenAlpha TV Max")),
        choice("Family lines — how many do you need?", 1, 2,
               [need("GenAlpha Mobile 10 GB"), need("GenAlpha Mobile 50 GB"),
                need("GenAlpha Mobile Unlimited 5G")],
               default=need("GenAlpha Mobile 50 GB")["id"]),
        choice("Choose your phone", 1, 1,
               [need("Apple iPhone 17 Pro"), need("Apple iPhone 17"),
                need("Samsung Galaxy S26")],
               default=need("Apple iPhone 17")["id"]),
        choice("Streaming extras", 0, 2,
               [need("GenAlpha Sports Pass"), offerings["GenAlpha Kids TV"]]),
    ],
}

if "GenAlpha Family Max" in offerings:
    req("PATCH", f"productOffering/{offerings['GenAlpha Family Max']['id']}", body)
    print("updated: GenAlpha Family Max")
else:
    offerings["GenAlpha Family Max"] = req("POST", "productOffering", body)
    print("created: GenAlpha Family Max")

print("Family Max: 2 fixed + pick 1-2 lines + pick 1 phone (configurable) + up to 2 extras, "
      "12-month term, base 49 EUR + install fee, options price themselves.")
