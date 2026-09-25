#!/usr/bin/env python3
"""Every sellable spec names a CFS — checked against the LIVE catalog.

Catalog-to-provisioning step 1 says the catalog is the decomposition: each
retail product spec an Active offering sells names a TMF633 customer-facing
service, and that CFS declares the fulfilment family the SOM runs. This is
only checkable on a running catalog (the seeds compose it), so it runs where
`rls_check.py` runs: on every pull request against the smoke fleet, and in
the proof run. Zero is the only passing count; a spec without a CFS is a
product the orchestrator can decompose only by guessing from a category
string, which is exactly what this step retires.

Scope, honestly:
  judged   an Active offering that names a product spec and a category other
           than the billing-only ones (Insurance, Top-ups) and the container
           (Bundles) — wholesale keeps its own CFS from its own seed
  skipped  offerings with no category or no spec: printed, not judged (most
           are suite fixtures; the catalog debris sweep owns them)
  warned   a CFS whose family disagrees with the offering's category — the
           CFS wins at order time, so this is where a data mistake would
           silently change fulfilment; it is a warning, because overriding
           the category is the point of authoring a CFS

usage: cfs_check.py [--gateway http://localhost:8080] [--host shop.example]
exit 0 clean · 2 at least one sellable spec without a resolvable CFS
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

BILLING_ONLY = {"Insurance", "Top-ups"}
CONTAINERS = {"Bundles"}
OWN_SEED = {"Wholesale access", "Wholesale mobile"}
FAMILY_OF_CATEGORY = {
    "Mobile plans": "mobile", "Broadband": "internet", "TV & Add-ons": "tv",
    "Devices": "device", "Partner services": "partner", "Security": "security",
}

ap = argparse.ArgumentParser()
ap.add_argument("--gateway", default="http://localhost:8080")
ap.add_argument("--host", default=None, help="tenant hostname to send as Host (default: the gateway's own)")
args = ap.parse_args()


def get(path):
    # the gateway's browse cache (catalog route, max-age 25s) would otherwise
    # hand back the spec as it was BEFORE the seed stamped it — a stale pass
    headers = {"Cache-Control": "no-cache"}
    if args.host:
        headers["Host"] = args.host
    r = urllib.request.Request(args.gateway + path, headers=headers)
    with urllib.request.urlopen(r, timeout=30) as resp:
        return json.load(resp)


def page(path):
    out, offset = [], 0
    while True:
        chunk = get(f"{path}{'&' if '?' in path else '?'}limit=100&offset={offset}")
        out.extend(chunk)
        if len(chunk) < 100:
            return out
        offset += 100


def family_of(cfs):
    for c in cfs.get("serviceSpecCharacteristic") or []:
        if c.get("name") == "fulfilmentFamily":
            vals = c.get("serviceSpecCharacteristicValue") or []
            if vals and vals[0].get("value"):
                return str(vals[0]["value"])
    return None


try:
    offerings = page("/tmf-api/productCatalogManagement/v4/productOffering")
except (urllib.error.URLError, OSError) as e:
    print(f"cfs_check: the catalog is not reachable at {args.gateway}: {e}")
    sys.exit(2)

specs, cfs_cache = {}, {}
missing, warnings, skipped = [], [], []
judged = set()
for o in offerings:
    if o.get("lifecycleStatus") != "Active":
        continue
    cats = o.get("category") or []
    cat = cats[0].get("name") if cats and isinstance(cats[0], dict) else None
    spec_id = (o.get("productSpecification") or {}).get("id")
    if cat in BILLING_ONLY or cat in CONTAINERS or cat in OWN_SEED:
        continue
    if not cat or not spec_id:
        skipped.append(f"{o['name']} ({'no category' if not cat else 'no spec'})")
        continue
    judged.add(spec_id)
    if spec_id not in specs:
        try:
            specs[spec_id] = get(f"/tmf-api/productCatalogManagement/v4/productSpecification/{spec_id}")
        except urllib.error.HTTPError as e:
            specs[spec_id] = {"name": "?", "_error": e.code}
    spec = specs[spec_id]
    refs = spec.get("serviceSpecification") or []
    cfs_id = refs[0].get("id") if refs and isinstance(refs[0], dict) else None
    if not cfs_id:
        missing.append((o["name"], cat, spec.get("name", "?"), "spec names no serviceSpecification"))
        continue
    if cfs_id not in cfs_cache:
        try:
            cfs_cache[cfs_id] = get(f"/tmf-api/serviceCatalogManagement/v4/serviceSpecification/{cfs_id}")
        except urllib.error.HTTPError as e:
            cfs_cache[cfs_id] = None
    cfs = cfs_cache[cfs_id]
    if cfs is None:
        missing.append((o["name"], cat, spec.get("name", "?"), f"CFS {cfs_id} does not resolve in TMF633"))
        continue
    family = family_of(cfs)
    if not family:
        missing.append((o["name"], cat, spec.get("name", "?"), f"CFS '{cfs['name']}' declares no fulfilmentFamily"))
        continue
    expected = FAMILY_OF_CATEGORY.get(cat)
    if expected and family != expected:
        warnings.append(f"{o['name']}: category '{cat}' sells as {expected} but its CFS '{cfs['name']}' says {family} — the CFS wins at order time")

for line in skipped:
    print(f"  not judged: {line}")
for w in warnings:
    print(f"  WARNING: {w}")
if missing:
    print(f"\nCFS CHECK — {len(missing)} sellable offering(s) whose spec names no usable CFS:")
    for name, cat, spec, why in missing:
        print(f"  - {name} [{cat}] spec '{spec}': {why}")
    print("\nfix: python3 ops/seed/seed_service_specifications.py (or author the CFS in the console), then rerun")
    sys.exit(2)
print(f"cfs_check: clean — {len(judged)} sellable specs, every one names a CFS with a fulfilment family"
      f"{f'; {len(warnings)} family warning(s)' if warnings else ''}; {len(skipped)} uncategorised/spec-less offerings not judged")
