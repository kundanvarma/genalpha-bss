#!/usr/bin/env python3
"""The chain under every CFS — catalog-to-provisioning step 2, ticket #73 (idempotent).

Under each customer-facing service the tenant sells, author the resource-facing
services it needs, each naming the TMF634 resource specification it realises;
the resource specification names a SEAM (number pool, SIM platform, online
charging, network slice, wholesale access, partner entitlement, customer
premises equipment) and never a vendor — `tenants.yml` picks the vendor. Each
CFS→RFS edge declares which product-spec characteristics the RFS CONSUMES; the
values stay on the product specification where the product manager edits them.

Runs AFTER seed_service_specifications.py (it needs the CFS by name). Re-running
changes nothing. A CFS may honestly need no RFS today (TV entitlement, device
shipment, security feature are realised in-house, not through a seam); the seed
says so rather than inventing one.

usage: seed_resource_facing_services.py                  # realm bss (GenAlpha)
       BSS_REALM=taranga seed_resource_facing_services.py
"""
import json
import os
import urllib.parse
import urllib.request

REALM = os.environ.get("BSS_REALM", "bss")
KEYCLOAK = f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token"
GATEWAY = os.environ.get("BSS_GATEWAY", "http://localhost:8080")
SERVICE_CATALOG = f"{GATEWAY}/tmf-api/serviceCatalogManagement/v4"
RESOURCE_CATALOG = f"{GATEWAY}/tmf-api/resourceCatalogManagement/v4"

# seam -> (resource specification name, description)
RESOURCE_SPECS = {
    "number": ("Mobile number", "An MSISDN drawn from the tenant's number pool, or ported in."),
    "sim": ("SIM profile", "A physical SIM or an eSIM profile on the SIM platform (SM-DP+)."),
    "ocs": ("Online-charging subscriber", "The subscriber and rate plan on the operator's online charging system."),
    "slice": ("Network-slice binding", "A priority slice profile on the 5G core, permanent or time-boxed."),
    "wholesale-access": ("Wholesale access", "An access line on another operator's fibre (MEF Sonata)."),
    "partner-entitlement": ("Partner entitlement", "An activation on a partner's platform; this BSS holds the code."),
    "cpe": ("Customer premises equipment", "The router or set-top box managed over the ACS."),
}
# seam -> (RFS name, the product-spec characteristics it consumes, required?)
# required = the orchestrator realises it for EVERY order of the CFS; an optional RFS is
# realised only when the product spec carries what it consumes (charging, slice) or when
# the address calls for it (wholesale access) — the disagreement gate reads this flag.
RFS = {
    "number": ("Number assignment", ["msisdn"], True),
    "sim": ("SIM provisioning", ["simType", "eid"], True),
    "ocs": ("Charging subscriber", ["chargingSpecId", "zeroRatedApps", "overageTier"], False),
    "slice": ("Slice binding", ["sliceProfile", "boostHours", "sliceChargingSpecId", "guaranteedDlMbps"], False),
    "wholesale-access": ("Wholesale access order", ["accessLayer", "speed"], False),
    "partner-entitlement": ("Partner entitlement activation", [], True),  # NOT "Partner activation": that is the CFS
    "cpe": ("Equipment management", [], False),
}
# CFS name -> the seams it needs today; an empty list is an honest "in-house"
CHAIN = {
    "Mobile line": ["number", "sim", "ocs", "slice"],
    "Broadband access": ["wholesale-access", "cpe"],
    "TV entitlement": [],
    "Device shipment": [],
    "Partner activation": ["partner-entitlement"],
    "Security feature": [],
}


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo", "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, url, body=None):
    r = urllib.request.Request(
        url, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}",
                 "Cache-Control": "no-cache"}, method=method)
    with urllib.request.urlopen(r) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def page(url):
    out, offset = [], 0
    while True:
        chunk = req("GET", f"{url}?limit=100&offset={offset}")
        out.extend(chunk)
        if len(chunk) < 100:
            return out
        offset += 100


def characteristic(name, value, description=None):
    c = {"name": name, "valueType": "string", "configurable": False,
         "serviceSpecCharacteristicValue": [{"value": value, "isDefault": True}]}
    if description:
        c["description"] = description
    return c


def char_value(entity, list_key, name):
    for c in entity.get(list_key) or []:
        if c.get("name") == name:
            vals = c.get(list_key.replace("Characteristic", "CharacteristicValue")) or []
            if vals and vals[0].get("value") is not None:
                return vals[0]["value"]
    return None


def ref(entity, referred_type):
    return {"id": entity["id"], "href": entity.get("href"), "name": entity["name"], "@referredType": referred_type}


# --- 1. resource specifications, one per seam
existing_rs = {r["name"]: r for r in page(f"{RESOURCE_CATALOG}/resourceSpecification")}
resource_spec = {}
for seam, (name, description) in RESOURCE_SPECS.items():
    body = {"name": name, "description": description, "version": "1.0", "lifecycleStatus": "Active",
            "category": "seam", "isBundle": False,
            "resourceSpecCharacteristic": [{"name": "seam", "valueType": "string", "configurable": False,
                                            "description": "the seam an adapter provides; tenants.yml names the vendor",
                                            "resourceSpecCharacteristicValue": [{"value": seam, "isDefault": True}]}]}
    if name in existing_rs:
        rs = existing_rs[name]
        if char_value(rs, "resourceSpecCharacteristic", "seam") != seam:
            rs = req("PATCH", f"{RESOURCE_CATALOG}/resourceSpecification/{rs['id']}",
                     {"resourceSpecCharacteristic": body["resourceSpecCharacteristic"]})
            print(f"resource spec: {name} — seam set to {seam}")
        else:
            print(f"exists: resource spec {name} ({seam})")
    else:
        rs = req("POST", f"{RESOURCE_CATALOG}/resourceSpecification", body)
        print(f"resource spec: {name} ({seam})")
    resource_spec[seam] = rs

# --- 2. resource-facing services, one per seam, each naming its resource spec
specs = {s["name"]: s for s in page(f"{SERVICE_CATALOG}/serviceSpecification")}
rfs = {}
for seam, (name, consumes, _required) in RFS.items():
    rs = resource_spec[seam]
    body = {"name": name, "description": f"Resource-facing service on the {seam} seam.", "version": "1.0",
            "lifecycleStatus": "Active", "serviceType": "RFS", "isBundle": False,
            "serviceSpecCharacteristic": [characteristic("seam", seam, "the seam this RFS is realised through")],
            "resourceSpecification": [ref(rs, "ResourceSpecification")]}
    if name in specs:
        cur = specs[name]
        if cur.get("serviceType") == "CFS":
            # a name clash with a customer-facing service: never rewrite a CFS into an RFS
            raise SystemExit(f"refusing: '{name}' is a CFS on this tenant; rename the RFS in this seed")
        cur_rs = (cur.get("resourceSpecification") or [{}])[0].get("id")
        if cur_rs != rs["id"] or char_value(cur, "serviceSpecCharacteristic", "seam") != seam:
            cur = req("PATCH", f"{SERVICE_CATALOG}/serviceSpecification/{cur['id']}",
                      {"serviceSpecCharacteristic": body["serviceSpecCharacteristic"],
                       "resourceSpecification": body["resourceSpecification"]})
            print(f"rfs: {name} — re-pointed at {rs['name']}")
        else:
            print(f"exists: rfs {name} -> {rs['name']}")
    else:
        cur = req("POST", f"{SERVICE_CATALOG}/serviceSpecification", body)
        print(f"rfs: {name} -> {rs['name']} ({seam})")
    rfs[seam] = cur

# --- 3. the edges: each CFS reliesOn its RFS, declaring what each consumes
wired = missing = 0
for cfs_name, seams in CHAIN.items():
    cfs = specs.get(cfs_name)
    if cfs is None:
        missing += 1
        print(f"no CFS '{cfs_name}' on this tenant (its family is not sold here, or seed_service_specifications has not run)")
        continue
    want = []
    for seam in seams:
        r = rfs[seam]
        edge = {**ref(r, "ServiceSpecification"), "relationshipType": "reliesOn",
                "serviceSpecRelationshipCharacteristic": [
                    {"name": "consumes", "valueType": "array",
                     "serviceSpecCharacteristicValue": [{"value": ",".join(RFS[seam][1])}]},
                    {"name": "required", "valueType": "boolean",
                     "serviceSpecCharacteristicValue": [{"value": "true" if RFS[seam][2] else "false"}]}]}
        want.append(edge)
    have = cfs.get("serviceSpecRelationship") or []
    def shape(edges):
        return [(e.get("id"), e.get("relationshipType"),
                 tuple(sorted((c.get("name"), (c.get("serviceSpecCharacteristicValue") or [{}])[0].get("value"))
                              for c in e.get("serviceSpecRelationshipCharacteristic") or [])))
                for e in edges]
    same = shape(have) == shape(want)
    if same:
        print(f"exists: {cfs_name} -> {[RFS[s][0] for s in seams] or 'no RFS (realised in-house)'}")
        continue
    req("PATCH", f"{SERVICE_CATALOG}/serviceSpecification/{cfs['id']}", {"serviceSpecRelationship": want})
    wired += 1
    print(f"cfs: {cfs_name} -> {[RFS[s][0] for s in seams] or 'no RFS (realised in-house)'}")

print(f"done: {len(resource_spec)} resource specs, {len(rfs)} RFS, {wired} CFS wired, {missing} CFS absent on this tenant")
