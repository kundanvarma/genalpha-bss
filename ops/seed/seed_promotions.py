#!/usr/bin/env python3
"""Seed the WELCOME10 promotion: 10% off the bundle's recurring charges (idempotent)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
PROMOTION = "http://localhost:8080/tmf-api/promotionManagement/v4"


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
        return json.load(resp)


existing = req("GET", f"{PROMOTION}/promotion?limit=100&code=WELCOME10")
if existing:
    print("exists: WELCOME10")
    raise SystemExit(0)

bundle = next((o for o in req("GET", f"{CATALOG}/productOffering?limit=100")
               if o["name"] == "GenAlpha One Home & Mobile"), None)
if not bundle:
    print("SKIP: bundle not found")
    raise SystemExit(0)

req("POST", f"{PROMOTION}/promotion", {
    "name": "Welcome offer", "description": "10% off the bundle for new customers",
    "code": "WELCOME10", "percentage": 10,
    "appliesTo": [bundle["id"]],
})
print(f"seeded: WELCOME10 -> 10% off {bundle['name']}")
