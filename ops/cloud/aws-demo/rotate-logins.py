#!/usr/bin/env python3
"""The hosted box has no published login (#323, part of #320).

The realm fixtures carry the demo logins with well-known passwords (demo/demo,
mira/mira, …). On the laptop that is the point; on the box, which strangers can
reach, it means anyone can sign in as staff or as a persona. This script, run by
install.sh after the fleet and its seeds are up, gives every such login a
generated password:

  - the staff login `demo` gets ONE password, the same in every box realm, so the
    founders remember one staff login and the box's machines (seeds, the worker
    controller, ops/onboard-tenant.sh) read one value — DEMO_STAFF_PASSWORD in the
    box's .env;
  - every other fixture login (agents, personas) gets its own;
  - the passwords go to a credentials sheet outside the checkout, readable by its
    owner only, and to nowhere else.

Idempotent: if the sheet exists the logins were rotated already and nothing
changes, unless --force. --dry-run lists what would change and touches nothing.
Logins created at runtime (self-registered customers, digital-worker badges,
service accounts) are never touched: only the fixtures' plain-text credentials
are published.

  python3 ops/cloud/aws-demo/rotate-logins.py --sheet ~/taranga-demo-logins.txt --env-file .env
"""
import argparse
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..")
# the realms the compose file imports (docker-compose.yml, the keycloak service)
DEFAULT_REALMS = {"bss": "bss-realm.json", "nova": "nova-realm.json",
                  "enet": "enet-realm.json", "taranga": "taranga-realm.json"}
ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"   # the BSS's own shape (Passwords.java)


def generated():
    return "-".join("".join(secrets.choice(ALPHABET) for _ in range(4)) for _ in range(4))


def call(method, url, token=None, body=None, form=None):
    data = None
    headers = {}
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def admin_token(kc, user, password):
    return call("POST", f"{kc}/realms/master/protocol/openid-connect/token",
                form={"grant_type": "password", "client_id": "admin-cli",
                      "username": user, "password": password})["access_token"]


def published_logins(fixture):
    """The fixture's logins with a plain-text password: what the repo publishes."""
    realm = json.load(open(fixture))
    for u in realm.get("users", []):
        if any(c.get("type") == "password" and "value" in c for c in u.get("credentials", [])):
            yield u["username"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--keycloak", default=os.environ.get("KEYCLOAK_BASE", "http://localhost:8085"))
    ap.add_argument("--admin-user", default=os.environ.get("KEYCLOAK_ADMIN_USER", "admin"))
    ap.add_argument("--sheet", required=True, help="where the new passwords are written (outside the checkout)")
    ap.add_argument("--env-file", help="the box's .env: DEMO_STAFF_PASSWORD is set here for its machines")
    ap.add_argument("--realm", action="append", default=[], metavar="REALM=FIXTURE",
                    help="rotate these instead of the compose file's realms (a probe realm for a dry test)")
    ap.add_argument("--force", action="store_true", help="rotate again even though a sheet exists")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if os.path.exists(a.sheet) and not a.force and not a.dry_run:
        print(f"[rotate-logins] already rotated — the sheet is at {a.sheet} (--force to rotate again)")
        return 0
    realms = dict(r.split("=", 1) for r in a.realm) if a.realm else DEFAULT_REALMS
    admin_pw = os.environ.get("KEYCLOAK_ADMIN_PASSWORD")
    if not admin_pw:
        print("[rotate-logins] KEYCLOAK_ADMIN_PASSWORD is not set", file=sys.stderr)
        return 2
    token = admin_token(a.keycloak, a.admin_user, admin_pw)
    staff_pw = generated()
    rows, missing = [], []
    for realm, fixture in realms.items():
        path = fixture if os.path.isabs(fixture) else os.path.join(ROOT, "infra", "keycloak", fixture)
        try:
            call("GET", f"{a.keycloak}/admin/realms/{realm}", token)
        except urllib.error.HTTPError:
            missing.append(realm)
            continue
        for username in published_logins(path):
            found = call("GET", f"{a.keycloak}/admin/realms/{realm}/users?exact=true&username="
                         + urllib.parse.quote(username), token) or []
            if not found:
                continue
            pw = staff_pw if username == "demo" else generated()
            if not a.dry_run:
                token = admin_token(a.keycloak, a.admin_user, admin_pw)   # a minute each; many realms outlast one
                call("PUT", f"{a.keycloak}/admin/realms/{realm}/users/{found[0]['id']}/reset-password", token,
                     body={"type": "password", "value": pw, "temporary": False})
            rows.append((realm, username, pw))
    if a.dry_run:
        for realm, username, _ in rows:
            print(f"[rotate-logins] would rotate {realm}/{username}")
        print(f"[rotate-logins] dry run: {len(rows)} login(s) in {len(realms) - len(missing)} realm(s); nothing changed")
        return 0

    old = os.umask(0o077)
    try:
        with open(a.sheet, "w") as f:
            f.write("# Taranga hosted demo — sign-in passwords (#323). Rotated by install.sh;\n"
                    "# the published ones no longer work here. Keep this file private.\n\n")
            f.write(f"{'realm':<10} {'login':<36} password\n")
            for realm, username, pw in rows:
                f.write(f"{realm:<10} {username:<36} {pw}\n")
    finally:
        os.umask(old)
    os.chmod(a.sheet, 0o600)
    if a.env_file:
        lines = open(a.env_file).read().splitlines() if os.path.exists(a.env_file) else []
        lines = [l for l in lines if not l.startswith("DEMO_STAFF_PASSWORD=")] + [f"DEMO_STAFF_PASSWORD={staff_pw}"]
        with open(a.env_file, "w") as f:
            f.write("\n".join(lines) + "\n")
        os.chmod(a.env_file, 0o600)
    print(f"[rotate-logins] rotated {len(rows)} login(s) in {len(realms) - len(missing)} realm(s); "
          f"sheet at {a.sheet}" + (f"; DEMO_STAFF_PASSWORD in {a.env_file}" if a.env_file else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
