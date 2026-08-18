#!/usr/bin/env python3
"""Bind the demo tenant's national registry: Folkeregisteret (freg) for NO,
pointed at mock-freg (freg-address-plan F-P1). One binding per (tenant,
country); secret_ref names an env var on the geographic-address service, never
a key. Idempotent (PUT upserts). Real Freg / a distributor = change baseUrl +
the env var, no code."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
GEO = "http://localhost:8080/tmf-api/geographicAddressManagement/v4"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def put(url, body):
    r = urllib.request.Request(
        url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method="PUT")
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


binding = put(f"{GEO}/registry", {
    "country": "NO", "provider": "freg", "displayName": "Folkeregisteret",
    "baseUrl": "http://mock-freg:8080", "secretRef": "FREG_API_KEY",
})
print(f"registry: {binding['displayName']} ({binding['provider']}) bound for {binding['country']}")
print("done")
