#!/usr/bin/env python3
"""Demo catalog reset — a clean, curated storefront every time.

E2E suites litter the catalog with throwaway offerings (Advisor NNNN, Config
Locked, StreamPlus, ...). The storefront shows every Active offering, so that
debris shows up next to the real products. This script curates:

  - RETIRE every Active offering that is NOT on the demo allowlist below
    (lifecycleStatus -> Retired; the shop only lists Active, so they vanish).
    Nothing is deleted — a retired offering can be re-activated any time.
  - RE-ACTIVATE any allowlist product that got retired, so the demo catalog is
    always fully present.

Run before a demo:  python3 ops/demo-reset-catalog.py
Idempotent and safe to run repeatedly. GenAlpha tenant only (the primary shop).
"""
import json
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
KC = "http://localhost:8085/realms/bss/protocol/openid-connect/token"

# The curated demo catalog — exactly what the storefront should show. Edit here
# to add/remove a demo product; everything else Active gets retired.
ALLOWLIST = {
    # Mobile plans
    "GenAlpha Mobile 10 GB", "GenAlpha Mobile 30GB 5G", "GenAlpha Mobile 50 GB",
    "GenAlpha Mobile 60 GB 5G", "GenAlpha Mobile Unlimited 5G",
    "GenAlpha Postpaid Mobile (ID verified)", "Kids Plan 2 GB",
    # Bundles
    "GenAlpha One Home & Mobile", "GenAlpha Family Max",
    # Devices
    "Samsung Galaxy S26", "Apple iPhone 17", "Apple iPhone 17 Pro",
    # Broadband
    "GenAlpha Fiber 1000",
    # TV & add-ons
    "GenAlpha TV Max", "GenAlpha Kids TV", "GenAlpha Sports Pass",
    # Insurance / security / partner / top-up
    "GenAlpha Device Care", "GenAlpha Secure Net", "Netflix Standard",
    "Data Top-Up 5 GB",
    # Legacy estate (the wrapped-legacy demo overlay)
    "Heritage DSL 20", "Heritage Voice Line",
}


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=data)) as r:
        return json.load(r)["access_token"]


TOK = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp) if resp.length != 0 else None


def all_offerings():
    """Page through the whole catalog (limit caps at 100)."""
    out, offset = [], 0
    while True:
        page = req("GET", f"{CATALOG}/productOffering?limit=100&offset={offset}")
        if not page:
            break
        out.extend(page)
        if len(page) < 100:
            break
        offset += 100
    return out


def set_status(o, status):
    req("PATCH", f"{CATALOG}/productOffering/{o['id']}", {"lifecycleStatus": status})


retired, reactivated, kept = 0, 0, 0
for o in all_offerings():
    name = o.get("name") or ""
    status = (o.get("lifecycleStatus") or "").lower()
    keep = name in ALLOWLIST
    if keep:
        kept += 1
        if status != "active":
            set_status(o, "Active")
            reactivated += 1
            print(f"  re-activated  {name}")
    elif status == "active":
        set_status(o, "Retired")
        retired += 1
        print(f"  retired       {name}")

print(f"\ndone — demo catalog curated: {kept} kept, {retired} retired, "
      f"{reactivated} re-activated. The shop now shows only the demo products.")
