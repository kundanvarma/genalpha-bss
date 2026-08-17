#!/usr/bin/env python3
"""
Seed the social surfaces (listening + care) so the console's Marketing desk
shows real data in a demo.

The BSS reaches social through a swappable seam (bss.downstream.social-api-url);
in this stack that points at the local `mock-social` emulator, NOT a live
Meta/Instagram account. This seeder posts a small, brand-neutral set of public
MENTIONS and inbound DIRECT MESSAGES into the mock, then triggers the insight
service to pull them in — mentions get sentiment-scored (Social listening) and
DMs that need a human open TMF621 trouble tickets (Social care).

In-memory mock: it resets when the mock-social container restarts, so run this
again after a `docker compose up`. Idempotent — if the surfaces already hold
data it does nothing, so it's safe to re-run.

    python3 ops/seed/seed_social.py            # genalpha (bss realm)
    python3 ops/seed/seed_social.py nova       # the nova tenant
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
MOCK = "http://localhost:8122"          # mock-social, host-mapped
KC = "http://localhost:8085/realms/{realm}/protocol/openid-connect/token"

# (realm, staff user/pass, the brand handle insight is configured to watch)
TENANTS = {
    "genalpha": ("bss", "demo", "demo", "genalpha-brand"),
    "nova": ("nova", "demo", "demo", "nova-brand"),
}

# Public mentions — a deliberate sentiment mix so the listening pane isn't all one colour.
MENTIONS = [
    {"platform": "x", "author": "ravi_k", "text": "Loving the new GenAlpha 5G plan, the speeds are unreal 🚀"},
    {"platform": "instagram", "author": "mia.told", "text": "third day of slow internet from GenAlpha, come on"},
    {"platform": "x", "author": "tomas90", "text": "Anyone else on GenAlpha? thinking of switching over"},
    {"platform": "x", "author": "lena_writes", "text": "credit where due — GenAlpha support sorted my bill in five minutes"},
    {"platform": "instagram", "author": "deej", "text": "my GenAlpha data ran out mid-call again, so frustrating"},
]

# Inbound DMs — a complaint (major), a support ask (minor), a compliment (no ticket).
DMS = [
    {"author": "priya_m", "text": "My internet has been down for two days and no one has helped. This is unacceptable."},
    {"author": "sam_ok", "text": "Hi, how do I move my number onto a new SIM?"},
    {"author": "grateful_gus", "text": "just wanted to say your care team was lovely today — thank you!"},
]


def req(url, method="GET", tok=None, form=None, data=None):
    body, headers = None, {}
    if form is not None:
        body = urllib.parse.urlencode(form).encode()
    if data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if tok:
        headers["Authorization"] = "Bearer " + tok
    r = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        return e.code, None


def main():
    tenant = sys.argv[1] if len(sys.argv) > 1 else "genalpha"
    if tenant not in TENANTS:
        print(f"unknown tenant '{tenant}' — choose from {list(TENANTS)}")
        sys.exit(2)
    realm, user, pw, account = TENANTS[tenant]

    _, tok_body = req(KC.format(realm=realm), "POST",
                      form={"grant_type": "password", "client_id": "bss-demo",
                            "username": user, "password": pw})
    if not tok_body or "access_token" not in tok_body:
        print(f"could not get a staff token for realm '{realm}' — is the stack up?")
        sys.exit(1)
    tok = tok_body["access_token"]

    # --- listening ---
    _, existing = req(f"{GATEWAY}/insight/v1/listening/mentions", tok=tok)
    if existing:
        print(f"listening: already {len(existing)} mention(s) — skipping (idempotent)")
    else:
        for m in MENTIONS:
            req(f"{MOCK}/v1/{account}/mentions", "POST", data=m)
        st, sync = req(f"{GATEWAY}/insight/v1/listening/sync", "POST", tok=tok)
        print(f"listening: seeded {len(MENTIONS)} mentions -> sync {st} {sync or ''}")

    # --- care ---
    _, queue = req(f"{GATEWAY}/insight/v1/care/queue", tok=tok)
    if queue:
        print(f"care: already {len(queue)} conversation(s) in the queue — skipping (idempotent)")
    else:
        for d in DMS:
            d = {**d, "handle": "@" + d["author"]}
            req(f"{MOCK}/v1/{account}/dms", "POST", data=d)
        st, sync = req(f"{GATEWAY}/insight/v1/care/sync", "POST", tok=tok)
        print(f"care: seeded {len(DMS)} DMs -> sync {st} {sync or ''}")

    # --- report ---
    _, mentions = req(f"{GATEWAY}/insight/v1/listening/mentions", tok=tok)
    _, queue = req(f"{GATEWAY}/insight/v1/care/queue", tok=tok)
    _, tickets = req(f"{GATEWAY}/tmf-api/troubleTicket/v4/troubleTicket?limit=100", tok=tok)
    social_tix = [t for t in (tickets or []) if t.get("ticketType") == "socialCare"]
    print(f"\nnow live for '{tenant}': "
          f"{len(mentions or [])} mentions, {len(queue or [])} care conversations, "
          f"{len(social_tix)} socialCare tickets")


if __name__ == "__main__":
    main()
