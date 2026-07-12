#!/usr/bin/env python3
"""Seed the Nova Telecom demo tenant: a small plan-only catalog, created by
Nova's own staff identity — the tenant lands in 'nova' because the token's
issuer is the nova realm, not because anything here says so."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/nova/protocol/openid-connect/token"
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
    print("exists: Nova catalog already seeded")
    raise SystemExit(0)

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
