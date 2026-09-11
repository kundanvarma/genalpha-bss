#!/usr/bin/env python3
"""Learning contracts for the operational ontology's decision points — the written
intent for what an action is measured by and what must never happen. The registry
executes what the caller chose; the contract is where the operator says what
"better" means for the outcome sweep and the decision log.

Usage: seed_ontology.py [tenant]   (default taranga; genalpha works too). Idempotent (PUT)."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TENANT = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TENANT", "taranga")).lower()
REALM = {"genalpha": "bss"}.get(TENANT, TENANT)
API = os.environ.get("API", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK", f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token")
USER, PASS = os.environ.get("SEED_USER", "demo"), os.environ.get("SEED_PASS", "demo")
C = "/tmf-api/campaignManagement/v4/learningContract"


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo", "username": USER, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def put(point, body):
    r = urllib.request.Request(API + C + "/" + point, method="PUT", data=json.dumps(body).encode(),
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            d = json.load(resp)
            print(f"  {point}: contract v{d.get('contract', {}).get('version')} ({d.get('contract', {}).get('objective')})")
    except urllib.error.HTTPError as e:
        print(f"  ! {point} -> {e.code} {e.read()[:160]!r}")


put("ontology.upgradeSubscription", {
    "objective": "retained",
    "secondaryMetrics": ["revenue"],
    "guardrails": ["never move a line to a dearer plan without the customer's own yes",
                   "never above the operator's price ceiling", "never while a commitment binds the current plan"],
    "explorationMaxPercent": 0, "autonomy": "medium",
    "notes": "measured by the outcome sweep: the line still active on the chosen plan after the window (retained / changed / lost)",
})
put("ontology.issueCredit", {
    "objective": "accepted",
    "guardrails": ["a care agent alone up to 25; a finance approver above; never above 50",
                   "a credit note always carries a reason"],
    "explorationMaxPercent": 0, "autonomy": "low",
    "notes": "the receipt names the human who asked and, above the threshold, the human who approved",
})
print(f"{TENANT}: ontology learning contracts seeded")
