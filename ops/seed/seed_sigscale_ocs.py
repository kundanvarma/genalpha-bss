#!/usr/bin/env python3
"""Rate plans in SigScale OCS (the operator's OCS tooling, not the BSS).

The catalog references charging by `chargingSpecId` and never contains it —
so the rate plans the demo plans point at (RG-DATA-10, RG-DATA-60, RG-UNL…)
must exist IN the OCS as TMF620 product offerings: a monthly recurring price
of 0 whose alteration grants the data allowance, plus a usage price that rates
overage per GB. SigScale creates the allowance bucket the moment a product is
created on the offering, and grants it again every month.

Idempotent: an offering that already exists is left alone. Targets the fleet's
SigScale OCS (docker-compose `sigscale-ocs`, REST on :8155, HTTP Basic); point
OCS_URL/OCS_USER/OCS_PASS at the operator's own node to seed that instead.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

OCS = os.environ.get("OCS_URL", "http://localhost:8155")
USER = os.environ.get("OCS_USER", "bss")
PASS = os.environ.get("OCS_PASS", "bss-secret")
CURRENCY = os.environ.get("OCS_CURRENCY", "NOK")
CATALOG = "/productCatalogManagement/v2"
PREPAID_DATA_SPEC = "8"  # SigScale's built-in PrepaidDataProductSpec

# rate plan id (what chargingSpecId names) -> (allowance GB, overage per GB, description)
PLANS = {
    "RG-DATA-2": (2, "49.00", "2 GB data counter"),
    "RG-DATA-10": (10, "39.00", "10 GB data counter"),
    "RG-DATA-30": (30, "29.00", "30 GB data counter"),
    "RG-DATA-50": (50, "19.00", "50 GB data counter"),
    "RG-DATA-60": (60, "19.00", "60 GB data counter"),
    "RG-UNL": (1000, "0.01", "Unlimited (1 TB fair-use counter)"),
    # slice-aware plans: same counter; the priority uplift is rated by the BSS
    "RG-DATA-50-PRIO": (50, "19.00", "50 GB data counter (priority slice)"),
    "RG-DATA-60-PRIO": (60, "19.00", "60 GB data counter (priority slice)"),
    "RG-UNL-PRIO": (1000, "0.01", "Unlimited (priority slice)"),
}

AUTH = "Basic " + base64.b64encode(f"{USER}:{PASS}".encode()).decode()


def req(method, path, body=None):
    r = urllib.request.Request(
        OCS + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Accept": "application/json", "Content-Type": "application/json", "Authorization": AUTH},
        method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None


try:
    with urllib.request.urlopen(urllib.request.Request(OCS + "/health"), timeout=10) as r:
        status = r.status
except (urllib.error.HTTPError, urllib.error.URLError) as e:
    status = getattr(e, "code", 0)
if status != 200:
    print(f"SigScale OCS at {OCS} is not healthy (HTTP {status}) — is the sigscale-ocs container up?")
    sys.exit(1)

for name, (gb, overage, desc) in PLANS.items():
    status, _ = req("GET", f"{CATALOG}/productOffering/{name}")
    if status == 200:
        print(f"{name}: exists")
        continue
    status, offer = req("POST", f"{CATALOG}/productOffering", {
        "name": name, "description": desc, "isBundle": False, "isCustomerVisible": True,
        "lifecycleStatus": "Active",
        "productSpecification": {"id": PREPAID_DATA_SPEC,
                                 "href": f"{CATALOG}/productSpecification/{PREPAID_DATA_SPEC}"},
        "productOfferingPrice": [
            {"name": "monthly", "priceType": "recurring", "recurringChargePeriod": "monthly",
             "price": {"taxIncludedAmount": "0", "currencyCode": CURRENCY},
             "productOfferPriceAlteration": {"name": "allowance", "priceType": "usage",
                                             "unitOfMeasure": f"{gb}g",
                                             "price": {"taxIncludedAmount": "0", "currencyCode": CURRENCY}}},
            {"name": "usage", "priceType": "usage", "unitOfMeasure": "1g",
             "price": {"taxIncludedAmount": overage, "currencyCode": CURRENCY}},
        ]})
    print(f"{name}: {'created' if status == 201 else 'FAILED ' + str(status)} — {gb} GB/month, {overage} {CURRENCY}/GB overage")

print("done")
