#!/usr/bin/env python3
"""ENet growth seed — the marketing & sales layer of the Guyana demo tenant.

Consumer-only, prepaid-heavy, WhatsApp-first: quiet hours in Georgetown time,
the "Test Drive" acquisition promotion ENet actually ran, WhatsApp journeys on
the events the BSS already publishes (welcome, running low, top-up thanks,
churn save, cart abandoned), a #SWITCHED landing page with lead capture, and
an ENet Rewards loyalty program in Guyana dollars.

Needs the marketing slice UP: campaign, insight (+ communication, loyalty).
Idempotent — safe to re-run. PITCH MATERIAL, NOT REPO CANON.
"""
import json
import urllib.error
import urllib.parse
import urllib.request

KC = "http://localhost:8085/realms/enet/protocol/openid-connect/token"
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
orange30 = offerings.get("Orange 30 Days 5G")
orange_extra = offerings.get("Orange 30 Days Extra 5G")

# ---- guardrails: Georgetown quiet hours, a sane weekly cap ----------------------------
print("settings:", req("POST", f"{CAMPAIGN}/settings", {
    "maxMarketingMessages": 3, "perDays": 7, "quietStart": "21:00", "quietEnd": "07:00",
    "timeZone": "America/Guyana"}).get("timeZone"))

# ---- promotions: the acquisition offer ENet ran ("Test Drive"), and a save offer ----------
promos = {p.get("code"): p for p in req("GET", f"{PROMO}/promotion?limit=100")}
if "TESTDRIVE" not in promos and orange30:
    req("POST", f"{PROMO}/promotion", {"name": "Test Drive — first Orange 30 Days free",
        "description": "New to ENet? Your first Orange 30 Days 5G is on us.", "code": "TESTDRIVE",
        "percentage": 100, "durationMonths": 1, "appliesTo": [orange30["id"]]})
    print("promo: TESTDRIVE (100% off the first Orange 30 Days 5G)")
if "STAYWITHENET" not in promos and orange_extra:
    req("POST", f"{PROMO}/promotion", {"name": "Stay with ENet — 20% off Extra for 2 months",
        "description": "A thank-you for staying: 20% off Orange 30 Days Extra 5G for two cycles.",
        "code": "STAYWITHENET", "percentage": 20, "durationMonths": 2, "appliesTo": [orange_extra["id"]]})
    print("promo: STAYWITHENET (20% x 2 months on Orange 30 Days Extra 5G)")

# ---- journeys: WhatsApp-first, on events the BSS already publishes ------------------------
journeys = {j["name"]: j for j in req("GET", f"{CAMPAIGN}/journey?limit=100")}
JOURNEYS = [
    # welcome goes in-app: at sign-up the BSS knows an email, not yet a WhatsApp number
    ("Welcome to ENet", "IndividualCreateEvent", 0, [
        {"type": "message", "stage": "Welcome", "channel": "inApp",
         "subject": "Welcome to ENet, {{party.firstName}}!",
         "content": "Hi {{party.firstName}} — welcome to Guyana's fastest network. Your My ENet account is live: top up, "
                    "check your data or chat with us right here on WhatsApp, any hour."}]),
    ("Running low — Orange top-up", "UsageThresholdBreachedEvent", 10, [
        {"type": "message", "stage": "Running low", "channel": "whatsapp",
         "subject": "{{usage.remaining}} left on your plan",
         "content": "{{party.firstName}}, you've used {{usage.percentUsed}}% of your data — {{usage.remaining}} left. "
                    "Grab an Orange 7 Days pass ($1,000, 10 GB) or move up to Orange 30 Days Extra in the app."}]),
    ("Top-up thanks", "BucketBalanceChangeEvent", 0, [
        {"type": "message", "stage": "Thanks", "channel": "inApp",
         "subject": "Your data has landed", "content": "Thanks, {{party.firstName}} — your extra data is on your line now. Enjoy."}]),
    ("Churn save — Stay with ENet", "ChurnRiskDetectedEvent", 20, [
        {"type": "message", "stage": "Save", "channel": "whatsapp", "promotionCode": "STAYWITHENET",
         "subject": "A thank-you from ENet, {{party.firstName}}",
         "content": "{{party.firstName}}, we'd hate to lose you. Use code {code} for 20% off Orange 30 Days Extra 5G for two months — "
                    "100 GB, USA roaming included. Reply here if anything's wrong and a person will call you."}]),
    ("Cart abandoned — finish in a tap", "ShoppingCartAbandonedEvent", 0, [
        {"type": "message", "stage": "Nudge", "channel": "whatsapp",
         "subject": "Your ENet cart is waiting",
         "content": "Hi {{party.firstName}}, you left something in your cart. Finish in a tap — pay by card or MMG, "
                    "collect at any ENet store or we deliver."}]),
]
for name, trigger, holdout, steps in JOURNEYS:
    if name in journeys:
        have = journeys[name]
        if have.get("triggerEventType") != trigger or (have.get("steps") or [{}])[0].get("channel") != steps[0]["channel"]:
            req("PATCH", f"{CAMPAIGN}/journey/{have['id']}", {"triggerEventType": trigger, "steps": steps})
            print(f"journey: {name} corrected -> {trigger}, {steps[0]['channel']}")
        continue
    j = req("POST", f"{CAMPAIGN}/journey", {"name": name, "triggerEventType": trigger,
                                             "holdoutPercent": holdout, "steps": steps})
    req("PATCH", f"{CAMPAIGN}/journey/{j['id']}", {"status": "active"})
    print(f"journey: {name} ({trigger}, {steps[0]['channel']}) active")

# ---- audiences: who to talk to — the CDP's product trait, no SQL, no export ----------------
AUD = f"{API}/insight/v1/audience"
try:
    auds = {a.get("name"): a for a in req("GET", AUD, quiet=True)}
    for name, plan in [("Orange 30 Days holders", "Orange 30 Days 5G"), ("OnFiber homes", "OnFiber 300")]:
        if name not in auds and plan in offerings:
            req("POST", AUD, {"name": name, "criteria": {"all": [{"type": "trait", "key": "product", "value": plan}]}})
            print(f"audience: {name}")
except Exception:
    print("audience: insight not reachable — skipped")

# ---- landing page: the #SWITCHED campaign gets a page with lead capture --------------------
try:
    pages = {p.get("slug"): p for p in req("GET", LANDING, quiet=True)}
except Exception:
    pages = {}
if "switched" not in pages:
    req("POST", LANDING, {"slug": "switched", "headline": "#SWITCHED — keep your number, join Guyana's fastest network",
        "subhead": "Porting is free and takes a day. Bring a photo ID to any ENet store, or leave your number and we'll call.",
        "ctaLabel": "Switch to ENet", "brandColor": "#F78F1E",
        "logoUrl": "http://shop.enet.localhost:8080/tmf-api/documentManagement/v4/document/brand-logo",
        **({"offeringId": orange30["id"]} if orange30 else {})})
    print("landing: /insight/v1/landing/switched/view")

# ---- loyalty: ENet Rewards, earned in Guyana dollars, burnable as data or a voucher ---------
prog = req("POST", f"{LOYALTY}/loyaltyProgram", {"enabled": True, "earnPointsPerCurrency": 0.01,
    "pointsPerGb": 100, "expiryMonths": 12, "voucherPercent": 10, "pointsPerVoucher": 200,
    "silverThreshold": 500, "goldThreshold": 2000})
print("loyalty: ENet Rewards — 1 point per $100, 100 points = 1 GB, silver 500 / gold 2000")

print("\nENet growth seeded: Georgetown quiet hours, TESTDRIVE + STAYWITHENET, 5 event journeys (WhatsApp-first), "
      "#SWITCHED landing page, ENet Rewards.")
