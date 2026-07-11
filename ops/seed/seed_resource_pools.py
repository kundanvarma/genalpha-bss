#!/usr/bin/env python3
"""Seed per-tenant MSISDN pools for the SOM's activation step (idempotent)."""
import json
import urllib.request
import urllib.parse

POOLS = {"bss": "+46701", "nova": "+46731"}


def token(realm):
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    url = f"http://localhost:8085/realms/{realm}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


for realm, prefix in POOLS.items():
    tok = token(realm)

    def req(method, path, body=None):
        r = urllib.request.Request(
            f"http://localhost:8080/tmf-api/resourcePoolManagement/v4/{path}",
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"},
            method=method)
        with urllib.request.urlopen(r) as resp:
            return json.load(resp)

    if any(p["prefix"] == prefix for p in req("GET", "resourcePool")):
        print(f"exists: {realm} pool {prefix}")
        continue
    req("POST", "resourcePool", {"name": f"{realm} mobile numbers",
                                 "resourceType": "msisdn", "prefix": prefix})
    print(f"seeded: {realm} MSISDN pool {prefix}xxxxxx")
