#!/usr/bin/env python3
"""Seed a postpaid offering that requires a verified identity at checkout
(the BankID/Vipps step-up path). Idempotent."""
import json
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"


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
        f"{CATALOG}/{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


NAME = "GenAlpha Postpaid Mobile (ID verified)"
offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
if NAME in offerings:
    o = offerings[NAME]
    if not o.get("requiresVerifiedIdentity"):
        req("PATCH", f"productOffering/{o['id']}", {"requiresVerifiedIdentity": True})
        print(f"flagged existing: {NAME}")
    else:
        print(f"exists: {NAME}")
else:
    spec = req("POST", "productSpecification",
               {"name": "Postpaid 5G Subscription", "brand": "GenAlpha", "lifecycleStatus": "Active"})
    price = req("POST", "productOfferingPrice", {
        "name": "Postpaid mobile monthly", "priceType": "recurring",
        "price": {"unit": "EUR", "value": 39.00},
        "recurringChargePeriodType": "month", "recurringChargePeriodLength": 1,
        "lifecycleStatus": "Active"})
    req("POST", "productOffering", {
        "name": NAME,
        "description": "Postpaid mobile plan. Requires a verified identity (BankID) at checkout.",
        "lifecycleStatus": "Active", "isSellable": True,
        "requiresVerifiedIdentity": True,
        "productSpecification": {"id": spec["id"], "href": spec["href"],
                                 "name": spec["name"], "@referredType": "ProductSpecification"},
        "productOfferingPrice": [{"id": price["id"], "href": price["href"],
                                  "name": price["name"], "@referredType": "ProductOfferingPrice"}],
    })
    print(f"created flagged offering: {NAME}")
print("done")
