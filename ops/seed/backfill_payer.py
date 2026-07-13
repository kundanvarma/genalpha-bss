#!/usr/bin/env python3
"""One-time backfill for split billing: products that predate payer stamping
carry only a customer related party, so after the billing rewrite they would
bill to their owner. Company-provisioned products should keep billing to the
company — stamp every org member's unstamped product with a payer related
party pointing at their organization.

Products a member genuinely bought themselves AFTER split billing shipped are
already distinguishable (no stamp, ordered by the member), so this runs once,
at the moment the feature ships, when everything unstamped is company-paid.
"""
import json
import sys
import urllib.request
import urllib.parse

PARTY = "http://localhost:8080/tmf-api/party/v4"
INVENTORY = "http://localhost:8080/tmf-api/productInventory/v4"

REALMS = ["bss", "nova"]


def token(realm):
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    url = f"http://localhost:8085/realms/{realm}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


def req(tok, method, url, body=None):
    r = urllib.request.Request(
        url,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def paged(tok, base):
    offset = 0
    while True:
        page = req(tok, "GET", f"{base}?limit=100&offset={offset}")
        yield from page
        if len(page) < 100:
            return
        offset += 100


total = 0
for realm in REALMS:
    tok = token(realm)
    org_of = {}
    for person in paged(tok, f"{PARTY}/individual"):
        org = person.get("organization") or {}
        if org.get("id"):
            org_of[person["id"]] = org["id"]
    stamped = 0
    for product in paged(tok, f"{INVENTORY}/product"):
        parties = product.get("relatedParty") or []
        if any(str(p.get("role", "")).lower() == "payer" for p in parties):
            continue
        owner = next((p["id"] for p in parties
                      if str(p.get("role", "")).lower() == "customer"), None)
        if owner not in org_of:
            continue
        parties.append({"id": org_of[owner], "role": "payer",
                        "@referredType": "Organization"})
        req(tok, "PATCH", f"{INVENTORY}/product/{product['id']}",
            {"relatedParty": parties})
        stamped += 1
    print(f"{realm}: stamped {stamped} product(s) with an organization payer")
    total += stamped

print(f"done: {total} product(s) backfilled")
sys.exit(0)
