#!/usr/bin/env python3
"""Taranga growth seed — the marketing layer of the vendor's own demo tenant.
Norwegian consumer rails: SMS for the moments that matter, in-app for the rest,
quiet hours in Oslo time, a WELCOME promotion, event journeys on what the BSS
already publishes (welcome, running low, top-up thanks, churn save, boost on,
cart abandoned), a "switch" landing page and Taranga Points.
Needs the marketing slice UP: campaign, insight (+ communication, loyalty).
Idempotent — safe to re-run.
"""
import json
import urllib.error
import urllib.parse
import urllib.request

KC = "http://localhost:8085/realms/taranga/protocol/openid-connect/token"
API = "http://localhost:8080"
CAMPAIGN = f"{API}/tmf-api/campaignManagement/v4"
PROMO = f"{API}/tmf-api/promotionManagement/v4"
LOYALTY = f"{API}/tmf-api/loyaltyManagement/v4"
LANDING = f"{API}/insight/v1/landing"
CATALOG = f"{API}/tmf-api/productCatalogManagement/v4"


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "demo", "password": "demo"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, url, body=None, quiet=False):
    r = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
                               method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        if not quiet:
            print("ERR", method, url, e.code, e.read().decode()[:200])
        raise


def up(url):
    try:
        req("GET", url, quiet=True)
        return True
    except Exception:
        return False


if not up(f"{CAMPAIGN}/journey?limit=1"):
    raise SystemExit("campaign service is not running — start the marketing slice (campaign insight event-hub flow) first")

offerings = {o["name"]: o for o in req("GET", f"{CATALOG}/productOffering?limit=100")}
m20 = offerings.get("Taranga Mobile 20 GB")
unl = offerings.get("Taranga Mobile Unlimited 5G")

print("settings:", req("POST", f"{CAMPAIGN}/settings", {
    "maxMarketingMessages": 3, "perDays": 7, "quietStart": "21:00", "quietEnd": "08:00",
    "timeZone": "Europe/Oslo"}).get("timeZone"))

promos = {p.get("code"): p for p in req("GET", f"{PROMO}/promotion?limit=100")}
if "WELCOME" not in promos and m20:
    req("POST", f"{PROMO}/promotion", {"name": "Welcome — first month of Mobile 20 GB free",
        "description": "New to Taranga? Your first month of Taranga Mobile 20 GB is on us.", "code": "WELCOME",
        "percentage": 100, "durationMonths": 1, "appliesTo": [m20["id"]]})
    print("promo: WELCOME")
if "STAY" not in promos and unl:
    req("POST", f"{PROMO}/promotion", {"name": "Stay — 20% off Unlimited for 3 months",
        "description": "A thank-you for staying: 20% off Taranga Mobile Unlimited 5G for three months.",
        "code": "STAY", "percentage": 20, "durationMonths": 3, "appliesTo": [unl["id"]]})
    print("promo: STAY")

journeys = {j["name"]: j for j in req("GET", f"{CAMPAIGN}/journey?limit=100")}
JOURNEYS = [
    ("Welcome to Taranga", "IndividualCreateEvent", 0, [
        {"type": "message", "stage": "Welcome", "channel": "inApp",
         "subject": "Welcome to Taranga, {{party.firstName}}!",
         "content": "Hi {{party.firstName}} — your My Taranga account is live. Check your data, top up or chat with us here, any hour."}]),
    ("Running low — Data Boost", "UsageThresholdBreachedEvent", 10, [
        {"type": "message", "stage": "Running low", "channel": "sms",
         "subject": "{{usage.remaining}} left this month",
         "content": "{{party.firstName}}, you've used {{usage.percentUsed}}% of your data — {{usage.remaining}} left. "
                    "Data Boost 10 GB is 99 kr in My Taranga, or move up to Unlimited 5G."}]),
    ("Top-up thanks", "BucketBalanceChangeEvent", 0, [
        {"type": "message", "stage": "Thanks", "channel": "inApp",
         "subject": "Your data has landed", "content": "Thanks, {{party.firstName}} — your extra data is on your line now."}]),
    ("Churn save — Stay", "ChurnRiskDetectedEvent", 20, [
        {"type": "message", "stage": "Save", "channel": "sms", "promotionCode": "STAY",
         "subject": "A thank-you from Taranga, {{party.firstName}}",
         "content": "{{party.firstName}}, we'd hate to lose you. Code {code} gives 20% off Unlimited 5G for three months. "
                    "Reply here if anything's wrong and a person will call you."}]),
    ("Boost on — enjoy the match", "ServiceSliceChangeEvent", 0, [
        {"type": "message", "stage": "Boost on", "channel": "sms",
         "subject": "Priority network is on, {{party.firstName}}",
         "content": "{{party.firstName}}, your {{slice.pass}} is live — your line rides the priority 5G slice until {{slice.until}}. "
                    "It switches itself off after; buy another any time in My Taranga."}]),
    ("Cart abandoned — finish in a tap", "ShoppingCartAbandonedEvent", 0, [
        {"type": "message", "stage": "Nudge", "channel": "inApp",
         "subject": "Your Taranga cart is waiting",
         "content": "Hi {{party.firstName}}, you left something in your cart. Finish in a tap — pay with Vipps, Klarna or card."}]),
]
RULES = {"Boost on — enjoy the match": {"triggerState": "on", "conversionEvent": "ServiceSliceChangeEvent:lapsed"}}
for name, trigger, holdout, steps in JOURNEYS:
    rules = RULES.get(name, {})
    if name in journeys:
        have = journeys[name]
        stale = have.get("triggerEventType") != trigger or (have.get("steps") or [{}])[0].get("channel") != steps[0]["channel"]
        stale = stale or any(have.get(k) != v for k, v in rules.items())
        if stale:
            req("PATCH", f"{CAMPAIGN}/journey/{have['id']}", {"triggerEventType": trigger, "steps": steps, **rules})
            print(f"journey: {name} corrected")
        continue
    j = req("POST", f"{CAMPAIGN}/journey", {"name": name, "triggerEventType": trigger, "holdoutPercent": holdout, "steps": steps, **rules})
    req("PATCH", f"{CAMPAIGN}/journey/{j['id']}", {"status": "active"})
    print(f"journey: {name} ({trigger}, {steps[0]['channel']}) active")

AUD = f"{API}/insight/v1/audience"
try:
    auds = {a.get("name"): a for a in req("GET", AUD, quiet=True)}
    for name, plan in [("Mobile 20 GB holders", "Taranga Mobile 20 GB"), ("Fiber homes", "Taranga Fiber 300")]:
        if name not in auds and plan in offerings:
            req("POST", AUD, {"name": name, "criteria": {"all": [{"type": "trait", "key": "product", "value": plan}]}})
            print(f"audience: {name}")
except Exception:
    print("audience: insight not reachable — skipped")

try:
    pages = {p.get("slug"): p for p in req("GET", LANDING, quiet=True)}
except Exception:
    pages = {}
if "switch" not in pages:
    req("POST", LANDING, {"slug": "switch", "headline": "Switch to Taranga — keep your number",
        "subhead": "Porting is free and takes a day. Leave your number and we'll call, or order online with BankID.",
        "ctaLabel": "Switch to Taranga", "brandColor": "#0B5FA5",
        "logoUrl": "http://shop.taranga.localhost:8080/tmf-api/documentManagement/v4/document/brand-logo",
        **({"offeringId": m20["id"]} if m20 else {})})
    print("landing: /insight/v1/landing/switch/view")

req("POST", f"{LOYALTY}/loyaltyProgram", {"enabled": True, "earnPointsPerCurrency": 0.1,
    "pointsPerGb": 100, "expiryMonths": 12, "voucherPercent": 10, "pointsPerVoucher": 300,
    "silverThreshold": 500, "goldThreshold": 2000})
print("loyalty: Taranga Points — 1 point per 10 kr, 100 points = 1 GB")
print("\nTaranga growth seeded: Oslo quiet hours, WELCOME + STAY, 6 event journeys (SMS + in-app), switch landing page, Taranga Points.")
