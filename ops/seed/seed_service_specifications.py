#!/usr/bin/env python3
"""CFS on every sellable spec — catalog-to-provisioning step 1 (idempotent).

Authors one TMF633 ServiceSpecification per fulfilment family in the service
catalog the product-catalog component already serves, and puts its reference
on every retail product spec an Active offering sells. From then on the SOM
reads `serviceSpecification[0]` and falls back to the category string only
while a spec names none — so the catalog IS the decomposition, and a buyer's
architect can read the mapping off TMF620/TMF633 instead of off our code.

The family rides the CFS as the characteristic `fulfilmentFamily` (TMF633
serviceSpecCharacteristic), because `serviceType` already means CFS | RFS.

A spec that offerings of two different families share is a catalog defect,
not a decision this seed should make: it is printed as CONFLICT and skipped,
and `ops/arch/cfs_check.py` then counts it. Categories that create no service
(Insurance, Top-ups) and containers (Bundles) get no CFS by design.

usage: seed_service_specifications.py            # tenant of the default realm (bss)
       BSS_REALM=taranga seed_service_specifications.py
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

REALM = os.environ.get("BSS_REALM", "bss")
KEYCLOAK = f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token"
GATEWAY = os.environ.get("BSS_GATEWAY", "http://localhost:8080")
CATALOG = f"{GATEWAY}/tmf-api/productCatalogManagement/v4"
SERVICE_CATALOG = f"{GATEWAY}/tmf-api/serviceCatalogManagement/v4"

# One customer-facing service per fulfilment family. The SOM knows exactly
# these six families (CatalogClient.Cfs.FAMILIES); a seventh needs code first.
CFS = [
    ("Mobile line", "mobile",
     "A network line: a number, a SIM or eSIM profile, an online-charging subscriber."),
    ("Broadband access", "internet",
     "A fixed access installed at a place; it never draws a number."),
    ("TV entitlement", "tv",
     "A digital entitlement; when bundled it rides the broadband and waits for it."),
    ("Device shipment", "device",
     "A handset shipped to the customer; a parcel, not a line."),
    ("Partner activation", "partner",
     "Activated on the partner's platform; this BSS holds the activation code."),
    ("Security feature", "security",
     "A feature toggled on the customer's line or access."),
]
# The retail categories and the family each sells. Anything else is either
# billing-only, a container, or another seed's business (wholesale has its own CFS).
FAMILY_OF_CATEGORY = {
    "Mobile plans": "mobile",
    "Broadband": "internet",
    "TV & Add-ons": "tv",
    "Devices": "device",
    "Partner services": "partner",
    "Security": "security",
}


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
        url, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def page(url):
    out, offset = [], 0
    while True:
        chunk = req("GET", f"{url}{'&' if '?' in url else '?'}limit=100&offset={offset}")
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


def cfs_ref(cfs):
    return {"id": cfs["id"], "href": cfs.get("href"), "name": cfs["name"],
            "@referredType": "ServiceSpecification"}


# --- 1. the six CFS, by name, with their family declared
existing = {s["name"]: s for s in page(f"{SERVICE_CATALOG}/serviceSpecification")}
cfs_by_family = {}
for name, family, description in CFS:
    body = {
        "name": name, "description": description, "version": "1.0",
        "lifecycleStatus": "Active", "serviceType": "CFS", "isBundle": False,
        "serviceSpecCharacteristic": [{
            "name": "fulfilmentFamily", "valueType": "string", "configurable": False,
            "description": "the fulfilment family the SOM runs for this service",
            "serviceSpecCharacteristicValue": [{"value": family, "isDefault": True}],
        }],
    }
    if name in existing:
        cfs = existing[name]
        if family_of(cfs) != family:
            cfs = req("PATCH", f"{SERVICE_CATALOG}/serviceSpecification/{cfs['id']}",
                      {"serviceSpecCharacteristic": body["serviceSpecCharacteristic"]})
            print(f"cfs: {name} — family set to {family}")
        else:
            print(f"exists: {name} ({family})")
    else:
        cfs = req("POST", f"{SERVICE_CATALOG}/serviceSpecification", body)
        print(f"cfs: {name} ({family})")
    cfs_by_family[family] = cfs
our_cfs_ids = {c["id"] for c in cfs_by_family.values()}

# --- 2. which family every sellable spec belongs to, read off its Active offerings
families_of_spec = {}
for o in page(f"{CATALOG}/productOffering"):
    if o.get("lifecycleStatus") != "Active":
        continue
    cats = o.get("category") or []
    cat = cats[0].get("name") if cats and isinstance(cats[0], dict) else None
    spec_id = (o.get("productSpecification") or {}).get("id")
    if cat in FAMILY_OF_CATEGORY and spec_id:
        families_of_spec.setdefault(spec_id, {})[FAMILY_OF_CATEGORY[cat]] = o["name"]

# --- 3. the reference on each spec — never overriding a CFS someone else authored
stamped = kept = conflicts = foreign = 0
for spec_id, families in sorted(families_of_spec.items()):
    if len(families) > 1:
        conflicts += 1
        detail = "; ".join(f"{fam} via '{off}'" for fam, off in sorted(families.items()))
        print(f"CONFLICT: spec {spec_id} is sold by offerings of {len(families)} families ({detail}) — fix the offerings' specs, not this seed")
        continue
    (family,) = families
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")
    current = spec.get("serviceSpecification") or []
    current_id = current[0].get("id") if current and isinstance(current[0], dict) else None
    if current_id and current_id not in our_cfs_ids:
        foreign += 1
        print(f"kept: '{spec['name']}' names CFS {current_id} that this seed did not author")
        continue
    want = cfs_by_family[family]
    if current_id == want["id"]:
        kept += 1
        continue
    req("PATCH", f"{CATALOG}/productSpecification/{spec_id}", {"serviceSpecification": [cfs_ref(want)]})
    stamped += 1
    print(f"spec: '{spec['name']}' -> {want['name']}")

print(f"done: {len(families_of_spec)} sellable specs — {stamped} stamped, {kept} already right, "
      f"{foreign} kept as authored elsewhere, {conflicts} conflicts left for a human")
