#!/usr/bin/env python3
"""Seed the TMF645 coverage map: the operator's footprint as data.
Fiber where the fiber is (matching the TMF679 serviceable-area prefixes),
VDSL in the older Malmö plant, 5G-FWA everywhere (empty prefix = the
mobile fallback). Idempotent by (technology, prefix)."""
import json
import urllib.request
import urllib.parse

KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
SQM = "http://localhost:8080/tmf-api/serviceQualificationManagement/v4"


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


existing = {(row["technology"], row["postcodePrefix"])
            for row in req("GET", f"{SQM}/coverageMap")}

ROWS = [
    # the fiber footprint mirrors the TMF679 commercial gates
    {"technology": "fiber", "postcodePrefix": "111", "maxDownMbps": 1000, "maxUpMbps": 1000,
     "note": "Stockholm inner city — full XGS-PON"},
    {"technology": "fiber", "postcodePrefix": "222", "maxDownMbps": 500, "maxUpMbps": 500,
     "note": "Göteborg — GPON"},
    {"technology": "fiber", "postcodePrefix": "333", "maxDownMbps": 300, "maxUpMbps": 300,
     "note": "Malmö — GPON, older splitters"},
    # the copper plant that remains
    {"technology": "vdsl", "postcodePrefix": "333", "maxDownMbps": 100, "maxUpMbps": 20,
     "note": "Malmö copper plant"},
    # the mobile fallback: everywhere
    {"technology": "5g-fwa", "postcodePrefix": "", "maxDownMbps": 100, "maxUpMbps": 30,
     "note": "national 5G fixed-wireless footprint"},
]

for row in ROWS:
    key = (row["technology"], row["postcodePrefix"])
    if key in existing:
        print(f"exists: {key}")
        continue
    req("POST", f"{SQM}/coverageMap", row)
    print(f"covered: {row['technology']} in '{row['postcodePrefix'] or 'everywhere'}'"
          f" at {row['maxDownMbps']} Mbps")
print("done")
