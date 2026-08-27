#!/usr/bin/env python3
"""Usage-policy demo data for the family personas (idempotent):

- a household allowance pool: paula owns and funds it, wilma and sonny draw
  from it with per-member caps ('Mobile data', the type the family's seeded
  usage records carry);
- sonny's content-services cap set at the statutory floor (250 NOK) — the
  junior line's carrier-billing guardrail;
- paula's roaming default limit stated EXPLICITLY (50 EUR — the meter also
  exists lazily, but the demo should show a deliberate setting).

Spend meters and pools are PARTY-scoped in the usage service — one meter
covers every line/subscription the party holds, so no per-subscription
resolution is needed (or possible). SKIPs gracefully when personas or the
usage service are missing.
"""
import base64
import json
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
USAGE = "/tmf-api/usageManagement/v4"
PARTY = "/tmf-api/party/v4"
KEYCLOAK = "http://localhost:8085/realms/bss/protocol/openid-connect/token"

POOL_NAME = "Family data pool"
POOL_GB = 40
USAGE_TYPE = "Mobile data"
MEMBER_CAPS = {  # partyKey -> caps on the pool draw
    "wilma": {"softLimitGB": 8, "hardLimitGB": 15},
    "sonny": {"softLimitGB": 3, "hardLimitGB": 5},
}


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


parties = {}
for key, email, pw in (("paula", "paula@family.example", "paula"),
                       ("wilma", "wilma@family.example", "wilma"),
                       ("sonny", "sonny@family.example", "sonny")):
    parties[key] = find_party(email, email, pw)
    if not parties[key]:
        print(f"SKIP: persona {email} not found — run the family seed first")
        raise SystemExit(0)

# ---- 1. household pool: paula owns, wilma + sonny draw with caps -----------
try:
    pools = req("GET", f"{USAGE}/allowancePool")
except (urllib.error.HTTPError, urllib.error.URLError) as e:
    print(f"SKIP: usage policy endpoints not reachable ({e})")
    raise SystemExit(0)

pool = next((p for p in pools
             if p.get("ownerPartyId") == parties["paula"]
             and p.get("name") == POOL_NAME), None)
if pool:
    print(f"exists: pool '{POOL_NAME}' ({pool['id'][:8]}…)")
else:
    pool = req("POST", f"{USAGE}/allowancePool", {
        "ownerPartyId": parties["paula"], "name": POOL_NAME,
        "usageType": USAGE_TYPE, "poolGB": POOL_GB})
    print(f"pool: '{POOL_NAME}' {POOL_GB} GB of {USAGE_TYPE}, paula funds it")

members = {m["partyId"] for m in pool.get("member") or []}
for key, caps in MEMBER_CAPS.items():
    if parties[key] in members:
        print(f"exists: member {key}")
        continue
    try:
        pool = req("POST", f"{USAGE}/allowancePool/{pool['id']}/member",
                   {"partyId": parties[key], **caps})
        print(f"member: {key} soft {caps['softLimitGB']} / hard {caps['hardLimitGB']} GB")
    except urllib.error.HTTPError as e:
        if e.code == 409:  # already a member / already drawing another pool
            print(f"SKIP member {key}: {e.read().decode()[:120]}")
        else:
            raise

# ---- 2. sonny's content-services cap at the statutory floor ----------------
meters = req("GET", f"{USAGE}/spendPolicy?partyId={parties['sonny']}")
content = next((m for m in meters if m.get("meterType") == "content"), None)
if content and content.get("enabled") and (content.get("limit") or {}).get("value") == 250:
    print("exists: sonny's content cap at the 250 NOK floor")
else:
    req("PATCH", f"{USAGE}/spendPolicy/content?partyId={parties['sonny']}",
        {"limit": 250, "currency": "NOK", "enabled": True})
    print("content cap: sonny -> 250 NOK (the statutory floor, junior line)")

# ---- 3. paula's roaming default limit, stated explicitly -------------------
meters = req("GET", f"{USAGE}/spendPolicy?partyId={parties['paula']}")
roaming = next((m for m in meters if m.get("meterType") == "roaming"), None)
if roaming and roaming.get("enabled") and (roaming.get("limit") or {}).get("value") == 50:
    print("exists: paula's roaming limit at the 50 EUR default")
else:
    req("PATCH", f"{USAGE}/spendPolicy/roaming?partyId={parties['paula']}",
        {"limit": 50, "currency": "EUR", "enabled": True})
    print("roaming limit: paula -> 50 EUR, explicit (covers all her lines)")

print("done")
