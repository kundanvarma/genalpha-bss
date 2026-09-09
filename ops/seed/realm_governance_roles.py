#!/usr/bin/env python3
"""Bring the LIVE Keycloak in line with the realm files for launch governance:
the catalog:approve role in every realm, granted to demo (and the tenant's
approver persona), plus the taranga desk personas (sigrid product, henrik
commercial approver, ingrid marketing). Idempotent."""
import json, os, sys, urllib.request, urllib.parse, urllib.error

KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8085")
ADMIN, PASS = os.environ.get("KC_ADMIN", "admin"), os.environ.get("KC_PASS", "admin")

def admin_token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": ADMIN, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{KC}/realms/master/protocol/openid-connect/token", data=data)) as r:
        return json.load(r)["access_token"]

TOKEN = admin_token()

def call(method, path, body=None, ok=(200, 201, 204, 409)):
    r = urllib.request.Request(f"{KC}/admin{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read(); return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        if e.code in ok: return e.code, None
        print(f"  ! {method} {path} -> {e.code} {e.read()[:200]!r}"); return e.code, None

REALMS = [r["realm"] for _, rs in [call("GET", "/realms")] for r in (rs or [])]
for realm in REALMS:
    if realm == "master": continue
    st, _ = call("POST", f"/realms/{realm}/roles", {"name": "catalog:approve", "description": "May approve, reject and force-launch product offerings (launch governance)"})
    _, role = call("GET", f"/realms/{realm}/roles/catalog:approve")
    if not role: continue
    approvers = ["demo"] + (["dwayne@enet.example"] if realm == "enet" else []) + (["henrik@taranga.example"] if realm == "taranga" else [])
    # taranga desk personas from the realm file (ids stripped from nothing — partialImport skips existing)
    if realm == "taranga":
        rf = json.load(open(os.path.join(os.path.dirname(__file__), "..", "..", "infra", "keycloak", "taranga-realm.json")))
        users = [u for u in rf["users"] if u["username"] in ("sigrid@taranga.example", "henrik@taranga.example", "ingrid@taranga.example")]
        st, res = call("POST", f"/realms/{realm}/partialImport", {"ifResourceExists": "SKIP", "users": users})
        print(f"{realm}: personas partialImport -> {st} {res and {k: res[k] for k in ('added','skipped') if k in res}}")
        # admin-API/partialImport users may pick up default-roles -> customer -> PartyScope trap: strip it
        _, dr = call("GET", f"/realms/{realm}/roles/default-roles-{realm}")
        for u in users:
            _, found = call("GET", f"/realms/{realm}/users?username={urllib.parse.quote(u['username'])}&exact=true")
            if not found: continue
            uid = found[0]["id"]
            if dr: call("DELETE", f"/realms/{realm}/users/{uid}/role-mappings/realm", [dr])
            # make sure the file's realm roles are all mapped (partialImport maps them; belt and braces)
            want = []
            for rn in u.get("realmRoles", []):
                _, rr = call("GET", f"/realms/{realm}/roles/{urllib.parse.quote(rn)}")
                if rr: want.append({"id": rr["id"], "name": rr["name"]})
            call("POST", f"/realms/{realm}/users/{uid}/role-mappings/realm", want)
    for username in approvers:
        _, found = call("GET", f"/realms/{realm}/users?username={urllib.parse.quote(username)}&exact=true")
        if not found: continue
        call("POST", f"/realms/{realm}/users/{found[0]['id']}/role-mappings/realm", [{"id": role["id"], "name": role["name"]}])
        print(f"{realm}: catalog:approve -> {username}")
print("done")
