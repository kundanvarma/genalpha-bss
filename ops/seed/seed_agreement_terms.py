#!/usr/bin/env python3
"""Give the GenAlpha bundle a TMF620 commitment term (idempotent)."""
import json
import urllib.request
import urllib.parse

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


bundle = next((o for o in req("GET", "productOffering?limit=100")
               if o["name"] == "GenAlpha One Home & Mobile"), None)
if not bundle:
    print("SKIP: bundle not found")
elif bundle.get("productOfferingTerm"):
    print("exists: bundle already carries a term")
else:
    req("PATCH", f"productOffering/{bundle['id']}", {"productOfferingTerm": [{
        "name": "12-month commitment",
        "duration": {"amount": 12, "units": "month"},
    }]})
    print("bundle now carries a 12-month commitment term")
