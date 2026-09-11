#!/usr/bin/env python3
"""Live-realm patch for the ontology component: the bss-ontology machine client
with a service account holding policy:evaluate — in every realm of a running
Keycloak (the realm JSON files carry the same for fresh installs).

Env: KC_URL (default http://localhost:8085), KC_USER/KC_PASS (admin), optional
KC_SECRET (default ontology-secret). Idempotent."""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

KC = os.environ.get("KC_URL", "http://localhost:8085").rstrip("/")
USER, PASS = os.environ.get("KC_USER", "admin"), os.environ.get("KC_PASS", "admin")
SECRET = os.environ.get("KC_SECRET", "ontology-secret")
CLIENT = "bss-ontology"
ROLES = ["policy:evaluate", "insight:read", "inventory:read", "billing:admin", "workforce:use"]  # policy pre-check; outcome sweep; delegated credit notes; filing approvals


def admin_token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": USER, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{KC}/realms/master/protocol/openid-connect/token", data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = admin_token()


def req(method, path, body=None, ok=(200, 201, 204, 409)):
    r = urllib.request.Request(f"{KC}/admin{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        if e.code in ok:
            return e.code, None
        raise


for realm in [x["realm"] for x in req("GET", "/realms")[1] if x["realm"] != "master"]:
    st, _ = req("POST", f"/realms/{realm}/clients", {
        "clientId": CLIENT, "name": "ontology machine identity (policy pre-check only)", "enabled": True,
        "protocol": "openid-connect", "publicClient": False, "secret": SECRET, "serviceAccountsEnabled": True,
        "standardFlowEnabled": False, "directAccessGrantsEnabled": False})
    _, clients = req("GET", f"/realms/{realm}/clients?clientId={CLIENT}")
    cid = clients[0]["id"]
    _, sa = req("GET", f"/realms/{realm}/clients/{cid}/service-account-user")
    roles = []
    for name in ROLES:
        _, role = req("GET", f"/realms/{realm}/roles/{urllib.parse.quote(name, safe='')}")
        if role:
            roles.append({"id": role["id"], "name": role["name"]})
    req("POST", f"/realms/{realm}/users/{sa['id']}/role-mappings/realm", roles)
    # admin-API users may pick up default-roles -> customer -> PartyScope trap: strip it
    _, mapped = req("GET", f"/realms/{realm}/users/{sa['id']}/role-mappings/realm")
    strip = [m for m in (mapped or []) if m["name"] in ("customer",) or m["name"].startswith("default-roles")]
    if strip:
        req("DELETE", f"/realms/{realm}/users/{sa['id']}/role-mappings/realm", strip)
    print(f"{realm}: {CLIENT} {'created' if st == 201 else 'present'}, service account holds {[r['name'] for r in roles]}")
