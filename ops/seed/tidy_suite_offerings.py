#!/usr/bin/env python3
"""Retire the catalog offerings browser suites left behind (#378), idempotent.

A suite run names what it makes after the run — "PlayCo Arcade 1793133663170",
"SUITE1791514308411 Plain Plan" — and older runs did not clean up, so those
names sat on shops, care desks and the back office. This retires every offering
named that way that is not retired yet, on GenAlpha and Taranga, and says which.
Retired, not deleted: a test household that picked one keeps its history.

Where launch governance is on (Taranga), a launched offering is unlaunched
through the governance door — on record, like a person would; anything else is
retired with a lifecycle change. Run against the laptop fleet; the hosted box
never runs suites, so it has none of these.
"""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:8085")
CATALOG = os.environ.get("API_URL", "http://localhost:8080") + "/tmf-api/productCatalogManagement/v4"
# a suite's run id is a 13-digit millisecond timestamp, at the end of a name or after "SUITE"
SUITE_NAME = re.compile(r"(\s\d{13}$)|(^SUITE\d{13}\b)")
TENANTS = [
    ("GenAlpha", "bss", "demo", os.environ.get("GENALPHA_STAFF_PASSWORD", "demo")),
    ("Taranga", "taranga", os.environ.get("TARANGA_APPROVER", "henrik@taranga.example"),
     os.environ.get("TARANGA_APPROVER_PASSWORD", "henrik")),
]


def token(realm, user, password):
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": user, "password": password}).encode()
    url = f"{KEYCLOAK}/realms/{realm}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


def call(tok, method, path, body=None):
    # NO X-Channel on purpose: the catalog shows staff every channel only when the request
    # names none — with a channel it narrows to what that channel sells, and a leftover
    # restricted to another channel would be missed
    r = urllib.request.Request(f"{CATALOG}/{path}", data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"},
                               method=method)
    with urllib.request.urlopen(r) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def every(tok):
    """Every offering in every state — the default listing shows Active only."""
    found = {}
    for status in ("Active", "Launched", "In design", "In test", "Rejected", "Retired"):
        offset = 0
        while True:
            q = urllib.parse.urlencode({"limit": 100, "offset": offset, "lifecycleStatus": status})
            page = call(tok, "GET", f"productOffering?{q}") or []
            for o in page:
                found[o["id"]] = o
            if len(page) < 100:
                break
            offset += 100
    return list(found.values())


total = 0
for label, realm, user, password in TENANTS:
    tok = token(realm, user, password)
    for o in sorted(every(tok), key=lambda x: x["name"]):
        if not SUITE_NAME.search(o["name"]) or o.get("lifecycleStatus") == "Retired":
            continue
        try:
            if o.get("lifecycleStatus") in ("Active", "Launched"):
                try:
                    call(tok, "POST", f"productOffering/{o['id']}/governance/unlaunch",
                         {"note": "suite leftover (#378)"})
                except urllib.error.HTTPError:
                    # governance is off for this tenant: a lifecycle change is the retirement
                    call(tok, "PATCH", f"productOffering/{o['id']}", {"lifecycleStatus": "Retired"})
            else:
                call(tok, "PATCH", f"productOffering/{o['id']}", {"lifecycleStatus": "Retired"})
            total += 1
            print(f"{label}: retired \"{o['name']}\" (was {o.get('lifecycleStatus')})")
        except urllib.error.HTTPError as e:
            print(f"{label}: could NOT retire \"{o['name']}\" — {e.code} {e.read().decode()[:160]}")

print(f"suite leftovers retired: {total}" if total else "no suite leftovers on sale — nothing to retire")
