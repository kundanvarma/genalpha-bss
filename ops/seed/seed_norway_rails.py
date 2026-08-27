#!/usr/bin/env python3
"""Norway registry rails for the demo personas (idempotent):

- link each persona to their registry personRef (the mock national-registry
  residents), so the feed sweeper keeps their addresses registry-true;
- directory settings: kai lists FULL, wilma takes the secret-number service
  (which forces reserved — the mandatory free suppression);
- print-verify sigrid, the PROTECTED resident, stays registry-side only —
  no demo party may ever be linked to her ref by a seed.

registryLink and directorySetting are upserts by construction; the extra
GET-checks just keep the output honest on re-runs. SKIPs gracefully when
party-account or a persona is missing.
"""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
PARTY = "/tmf-api/party/v4"
KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"

# persona -> (login email, password, registry personRef)
PERSONAS = {
    "kai": ("kai@bss.local", "kai", "01018012345"),
    "paula": ("paula@family.example", "paula", "02027512346"),
    "wilma": ("wilma@family.example", "wilma", "03037812347"),
    "sonny": ("sonny@family.example", "sonny", "10101012348"),
}
PROTECTED_REF = "04046912349"  # sigrid — registry-side only, NEVER linked here


def token(username="demo", password="demo"):
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": username, "password": password,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def jwt_sub(tok):
    payload = tok.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))["sub"]


def find_party(email, username, password):
    try:
        q = urllib.parse.quote(email.split("@")[0])
        for p in req("GET", f"{PARTY}/individual?q={q}&limit=50"):
            for m in p.get("contactMedium") or []:
                if (m.get("characteristic") or {}).get("emailAddress") == email:
                    return p["id"]
    except (urllib.error.HTTPError, urllib.error.URLError):
        pass
    try:
        return jwt_sub(token(username, password))
    except (urllib.error.HTTPError, urllib.error.URLError):
        return None


# ---- 1. registry links -----------------------------------------------------
ids = {}
for name, (email, pw, person_ref) in PERSONAS.items():
    party_id = find_party(email, email, pw)
    if not party_id:
        print(f"SKIP: persona {email} not found — not linked")
        continue
    ids[name] = party_id
    try:
        req("POST", f"{PARTY}/individual/{party_id}/registryLink",
            {"personRef": person_ref})
        print(f"linked: {name} -> {person_ref}")
    except urllib.error.HTTPError as e:
        if e.code in (404, 501, 503):
            print(f"SKIP: registryLink not available ({e.code}) — is part A deployed?")
            raise SystemExit(0)
        raise

if not ids:
    print("SKIP: no personas found — nothing linked")
    raise SystemExit(0)

# ---- 2. directory settings: kai full, wilma secret number ------------------
def upsert_setting(name, body, want):
    settings = req("GET", f"{PARTY}/individual/{ids[name]}/directorySetting")
    default = next((s for s in settings if "serviceRef" not in s), None)
    if default and all(default.get(k) == v for k, v in want.items()):
        print(f"exists: {name}'s directory setting ({', '.join(f'{k}={v}' for k, v in want.items())})")
        return
    req("POST", f"{PARTY}/individual/{ids[name]}/directorySetting", body)
    print(f"directory: {name} -> {', '.join(f'{k}={v}' for k, v in body.items())}")


if "kai" in ids:
    upsert_setting("kai", {"exposure": "full"}, {"exposure": "full"})
else:
    print("SKIP: kai missing — no directory exposure set")
if "wilma" in ids:
    # secretNumber forces reserved — the setting IS the suppression
    upsert_setting("wilma", {"secretNumber": True}, {"secretNumber": True})
else:
    print("SKIP: wilma missing — no secret number set")

# ---- 3. verify the protected resident stays registry-side only -------------
hits = req("GET", "%s/individual?q=%s&limit=10" % (PARTY, urllib.parse.quote("sigrid")))
if hits:
    print(f"WARNING: {len(hits)} party record(s) match 'sigrid' — the protected "
          f"resident {PROTECTED_REF} must stay registry-side only; inspect them")
else:
    print(f"verified: sigrid ({PROTECTED_REF}) has no party record — "
          "protected resident stays registry-side only")
print("done")
