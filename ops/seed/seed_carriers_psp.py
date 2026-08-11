#!/usr/bin/env python3
"""Seed the demo tenant's delivery + payment menus so the storefront shows a
real CHOICE, not the built-in single fallback.

  - Carriers (TMF700 shipping seam): Helthjem (home), Posten/Bring (home +
    pickup, the default), PostNord (home + pickup). The shopper picks the
    carrier at checkout; the fulfilment router honours the pick.
  - Payment (PSP seam): Klarna offered alongside the built-in card PSP.

secret_ref only — the config stores an ENV VAR NAME, never a key. Idempotent
(PUT upserts). Re-run after carrier_choice_test / psp_klarna_test, which bind
and then UNBIND their own providers on this tenant (leaving it back at the
single fallback)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
FUL = "http://localhost:8080/tmf-api/shippingOrderManagement/v4"
PAY = "http://localhost:8080/tmf-api/paymentManagement/v4"


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


CARRIERS = [
    {"carrier": "helthjem", "displayName": "Helthjem", "baseUrl": "http://mock-logistics:8080",
     "secretRef": "HELTHJEM_API_KEY", "methods": ["home"], "isDefault": False},
    {"carrier": "bring", "displayName": "Posten/Bring", "baseUrl": "http://mock-bring:8080",
     "secretRef": "BRING_API_KEY", "methods": ["home", "pickupPoint"], "isDefault": True},
    {"carrier": "postnord", "displayName": "PostNord", "baseUrl": "http://mock-postnord:8080",
     "secretRef": "POSTNORD_API_KEY", "methods": ["home", "pickupPoint"], "isDefault": False},
]

for c in CARRIERS:
    put(f"{FUL}/carrier", c)
    print(f"carrier: {c['displayName']} ({', '.join(c['methods'])})"
          + (" [default]" if c["isDefault"] else ""))

put(f"{PAY}/paymentProvider", {
    "provider": "klarna", "displayName": "Klarna", "baseUrl": "http://mock-klarna:8080",
    "secretRef": "KLARNA_API_KEY", "webhookSecretRef": "KLARNA_WEBHOOK_SECRET",
    "methods": ["card", "klarna"], "isDefault": False})
print("payment: Klarna offered alongside card")
print("done")
