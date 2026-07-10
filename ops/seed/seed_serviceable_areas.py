#!/usr/bin/env python3
"""Seed TMF679 serviceable areas: fiber is gated to specific postcode
prefixes; everything else qualifies anywhere (idempotent by prefix)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
CATALOG = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
QUALIFICATION = "http://localhost:8080/tmf-api/productOfferingQualification/v4"


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


offerings = {o["name"]: o for o in req("GET", f"{CATALOG}/productOffering?limit=100")}
fiber = offerings["GenAlpha Fiber 1000"]

existing = {a["postcodePrefix"] for a in
            req("GET", f"{QUALIFICATION}/serviceableArea?productOfferingId={fiber['id']}&limit=100")}

# Fiber footprint: central Stockholm (111), Göteborg (222), Malmö (333).
for prefix, label in [("111", "Stockholm inner city"), ("222", "Göteborg"), ("333", "Malmö")]:
    if prefix in existing:
        print(f"exists: {prefix} ({label})")
        continue
    req("POST", f"{QUALIFICATION}/serviceableArea", {
        "name": f"Fiber footprint: {label}",
        "productOffering": {"id": fiber["id"], "name": fiber["name"], "@referredType": "ProductOffering"},
        "postcodePrefix": prefix,
    })
    print(f"gated: fiber serviceable in {prefix} ({label})")
print("done")
