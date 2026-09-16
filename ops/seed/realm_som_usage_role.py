#!/usr/bin/env python3
"""Live-realm patch: the bss-som machine identity reads usage allowances (usage:read) so activation can
push an offering's overage tier table to the charging system. Every realm of a running Keycloak; the
realm JSON files carry the same for fresh installs. Env: KC_URL, KC_USER/KC_PASS. Idempotent."""
import json, os, urllib.error, urllib.parse, urllib.request
KC = os.environ.get("KC_URL", "http://localhost:8085").rstrip("/")
USER, PASS = os.environ.get("KC_USER", "admin"), os.environ.get("KC_PASS", "admin")
def admin_token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": USER, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{KC}/realms/master/protocol/openid-connect/token", data=data)) as r:
        return json.load(r)["access_token"]
TOKEN = admin_token()
def req(method, path, body=None):
    r = urllib.request.Request(f"{KC}/admin{path}", method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read(); return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        if e.code in (204, 409): return e.code, None
        raise
for realm in [x["realm"] for x in req("GET", "/realms")[1] if x["realm"] != "master"]:
    _, clients = req("GET", f"/realms/{realm}/clients?clientId=bss-som")
    if not clients: print(f"{realm}: no bss-som client"); continue
    _, sa = req("GET", f"/realms/{realm}/clients/{clients[0]['id']}/service-account-user")
    _, role = req("GET", f"/realms/{realm}/roles/{urllib.parse.quote('usage:read', safe='')}")
    if not role: print(f"{realm}: no usage:read role"); continue
    req("POST", f"/realms/{realm}/users/{sa['id']}/role-mappings/realm", [{"id": role["id"], "name": role["name"]}])
    print(f"{realm}: bss-som service account holds usage:read")
