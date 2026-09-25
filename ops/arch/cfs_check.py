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

Step 2 adds the DISAGREEMENT check: for every CFS the orchestrator has realised
services for, what the catalog declares (its resource-facing services, each
with a `required` flag on the edge) must agree with what the orchestrator
recorded (TMF638 `supportingService[]` realisations). Red when a service
realised a seam its CFS never declared, or when a REQUIRED RFS was never
realised by any service of that CFS. An optional RFS (charging only with a
charging reference, slice only with a slice profile…) that no service needed
is noted, not failed. Services whose CFS no longer exists (suite fixtures
deleted after the order) or that predate step 2 (no realisation rows) carry
no evidence and are skipped, counted.

The inventory face needs a staff token: demo/demo in realm BSS_REALM (bss).

usage: cfs_check.py [--gateway http://localhost:8080] [--host shop.example]
       BSS_REALM=taranga cfs_check.py --host shop.taranga.no
Step 3 counts the FALLBACK: a service whose realisations carry seam
`category-fallback` was fulfilled by the category table because its product
spec named no CFS. That is a debt, not a disagreement: the count is printed as
`metric categoryFallbacks=N` and ops/arch/ratchet.sh --live lets it only fall.

exit 0 clean · 2 a sellable spec without a resolvable CFS, or a catalog/orchestrator disagreement
"""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.error
import urllib.request

# step 3: billing-only is a CFS (family billing-only, zero seams), so Insurance
# and Top-ups are judged like every other sellable category
BILLING_ONLY = set()
CONTAINERS = {"Bundles"}
OWN_SEED = {"Wholesale access", "Wholesale mobile"}
FAMILY_OF_CATEGORY = {
    "Mobile plans": "mobile", "Broadband": "internet", "TV & Add-ons": "tv",
    "Devices": "device", "Partner services": "partner", "Security": "security",
    "Insurance": "billing-only", "Top-ups": "billing-only",
}
CATEGORY_FALLBACK = "category-fallback"  # the seam the orchestrator records when the category table fulfilled a service

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

# ---------------------------------------------------------------- step 2 ----
# what the catalog declares under each CFS, and what the orchestrator realised
def token():
    realm = os.environ.get("BSS_REALM", "bss")
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "demo", "password": "demo"}).encode()
    kc = os.environ.get("BSS_KEYCLOAK", "http://localhost:8085")
    with urllib.request.urlopen(urllib.request.Request(f"{kc}/realms/{realm}/protocol/openid-connect/token", data=data), timeout=30) as r:
        return json.load(r)["access_token"]


def page_auth(path, tok):
    out, offset = [], 0
    while True:
        headers = {"Cache-Control": "no-cache", "Authorization": f"Bearer {tok}"}
        if args.host:
            headers["Host"] = args.host
        with urllib.request.urlopen(urllib.request.Request(f"{args.gateway}{path}{'&' if '?' in path else '?'}limit=100&offset={offset}", headers=headers), timeout=60) as resp:
            chunk = json.load(resp)
        out.extend(chunk)
        if len(chunk) < 100:
            return out
        offset += 100


def edge_flag(edge, name):
    for c in edge.get("serviceSpecRelationshipCharacteristic") or []:
        if c.get("name") == name:
            vals = c.get("serviceSpecCharacteristicValue") or []
            if vals and vals[0].get("value") is not None:
                return str(vals[0]["value"])
    return None


all_specs = {sp["id"]: sp for sp in page("/tmf-api/serviceCatalogManagement/v4/serviceSpecification")}
declared = {}  # cfs id -> {seam: required?}
for sp in all_specs.values():
    if sp.get("serviceType") != "CFS":
        continue
    seams = {}
    for edge in sp.get("serviceSpecRelationship") or []:
        if edge.get("relationshipType") != "reliesOn":
            continue
        rfs = all_specs.get(edge.get("id"))
        seam = None
        for c in (rfs or {}).get("serviceSpecCharacteristic") or []:
            if c.get("name") == "seam":
                vals = c.get("serviceSpecCharacteristicValue") or []
                seam = vals[0].get("value") if vals else None
        if seam:
            seams[seam] = (edge_flag(edge, "required") or "false").lower() == "true"
    declared[sp["id"]] = seams

disagreements, optional_unused = [], []
orphaned = predating = judged_services = fallbacks = 0
services = None
for attempt in (1, 2, 3):
    try:
        services = page_auth("/tmf-api/serviceInventory/v4/service", token())
        break
    except (urllib.error.URLError, OSError) as e:
        last = e
        import time
        time.sleep(3 * attempt)
if services is None:
    # a gate that cannot see is red, never a pass by omission
    print(f"cfs_check: the service inventory is not reachable ({last}) — the disagreement check could not run")
    sys.exit(2)
realised = {}  # cfs id -> {seam: [ (service name, declared?) ]}
for svc in services:
    cfs_id = (svc.get("serviceSpecification") or {}).get("id")
    rows = [x for x in (svc.get("supportingService") or []) if isinstance(x, dict) and x.get("seam")]
    if not rows:
        predating += 1
        continue
    if any(x["seam"] == CATEGORY_FALLBACK for x in rows):
        # the category table fulfilled this service (its spec named no CFS): a
        # counted debt (ratchet metric categoryFallbacks), never a disagreement
        fallbacks += 1
        continue
    if cfs_id not in declared:
        orphaned += 1
        continue
    judged_services += 1
    for x in rows:
        realised.setdefault(cfs_id, {}).setdefault(x["seam"], []).append((svc.get("name"), bool(x.get("declared"))))
for cfs_id, seams in realised.items():
    cfs_name = all_specs[cfs_id]["name"]
    for seam, hits in seams.items():
        # judged against what the catalog declares NOW, not the flag recorded at order
        # time: declaring the missing RFS afterwards is the fix, and it must turn green
        if seam not in declared[cfs_id]:
            disagreements.append(f"CFS '{cfs_name}': {len(hits)} service(s) realised seam '{seam}' that the CFS never declares (e.g. '{hits[0][0]}') — author the RFS or stop the adapter")
    for seam, required in declared[cfs_id].items():
        if seam not in seams:
            if required:
                disagreements.append(f"CFS '{cfs_name}': declares RFS on seam '{seam}' as required, but none of its {sum(len(h) for h in seams.values())} realisation(s) touched it")
            else:
                optional_unused.append(f"{cfs_name}: optional RFS on seam '{seam}' not needed by any service so far")

for line in skipped:
    print(f"  not judged: {line}")
for line in optional_unused:
    print(f"  note: {line}")
for w in warnings:
    print(f"  WARNING: {w}")
if missing or disagreements:
    if missing:
        print(f"\nCFS CHECK — {len(missing)} sellable offering(s) whose spec names no usable CFS:")
        for name, cat, spec, why in missing:
            print(f"  - {name} [{cat}] spec '{spec}': {why}")
        print("\nfix: python3 ops/seed/seed_service_specifications.py (or author the CFS in the console), then rerun")
    print(f"metric categoryFallbacks={fallbacks}")
    if disagreements:
        print(f"\nCFS CHECK — {len(disagreements)} disagreement(s) between what the catalog declares and what the orchestrator realised:")
        for d in disagreements:
            print(f"  - {d}")
        print("\nfix: python3 ops/seed/seed_resource_facing_services.py declares the seams the orchestrator drives; a seam the code exercises that no RFS names is the catalog lagging the code")
    sys.exit(2)
# machine-readable for ops/arch/ratchet.sh --live (the count may only fall)
print(f"metric categoryFallbacks={fallbacks}")
print(f"cfs_check: clean — {len(judged)} sellable specs, every one names a CFS with a fulfilment family"
      f"{f'; {len(warnings)} family warning(s)' if warnings else ''}; {len(skipped)} uncategorised/spec-less offerings not judged; "
      f"catalog and orchestrator agree on {len(realised)} CFS across {judged_services} realised services "
      f"({orphaned} whose CFS is gone and {predating} predating step 2 skipped); "
      f"{fallbacks} service(s) fulfilled by the category fallback")
