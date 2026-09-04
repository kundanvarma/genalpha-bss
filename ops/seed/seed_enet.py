#!/usr/bin/env python3
"""ENet demo — a Guyanese fibre + mobile operator tenant (GYD, en).

PITCH MATERIAL, NOT REPO CANON: this seeds the 'enet' tenant with a lineup
shaped after ENet's publicly reported retail plans (OnFiber 350/100 and
1000/500 with DreamTV included, the Orange unlimited 30-day tiers, TV
Complete, the One Home & Mobile bundle) plus a prepaid 50 GB bundle, an entry
fibre tier and top-ups so every storefront tab and journey has something to
sell. Names match what the tenant already carried on 2026-09-02, so re-runs
never duplicate. Prices are demo values in
GYD. Keep this file out of public docs/books; the dataset lives in the DB.

Idempotent — safe to re-run.
"""
import json
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/enet/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
USAGE = "http://localhost:8080/tmf-api/usageManagement/v4"
POOL = "http://localhost:8080/tmf-api/resourcePoolManagement/v4"
FUL = "http://localhost:8080/tmf-api/shippingOrderManagement/v4"
PAY = "http://localhost:8080/tmf-api/paymentManagement/v4"
CUR = "GYD"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        path if path.startswith("http") else f"{API}/{path}",
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        print("ERR", method, path, e.code, e.read().decode()[:300])
        raise


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
cats = {c["name"]: c for c in req("GET", "category?limit=100")}

# storefront tabs key on these exact category names (Shop.jsx LOB list)
for name in ("Mobile plans", "Broadband", "TV & Add-ons", "Top-ups"):
    if name not in cats:
        cats[name] = req("POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")


def cat_ref(name):
    return {"id": cats[name]["id"], "name": name, "@referredType": "Category"}


def ensure_price(name, value, price_type="recurring", period="month"):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": price_type,
            "price": {"unit": CUR, "value": value}, "lifecycleStatus": "Active"}
    if period:
        body["recurringChargePeriodType"] = period
    prices[name] = req("POST", "productOfferingPrice", body)
    print(f"price: {name} = {value} {CUR}")
    return prices[name]


def ensure_offering(name, category, value, description, bundle_of=None,
                    price_type="recurring", period="month"):
    if name in offerings:
        return offerings[name]
    suffix = " monthly" if price_type == "recurring" else ""
    price = ensure_price(f"{name}{suffix}", value, price_type, period)
    body = {"name": name, "description": description, "lifecycleStatus": "Active",
            "isBundle": bool(bundle_of), "isSellable": True,
            "category": [cat_ref(category)],
            "productOfferingPrice": [{"id": price["id"], "name": price["name"]}]}
    if bundle_of:
        body["bundledProductOffering"] = [
            {"id": offerings[n]["id"], "name": n, "@referredType": "ProductOffering"}
            for n in bundle_of]
    offerings[name] = req("POST", "productOffering", body)
    print(f"offering: {name} ({category}) {value} {CUR}")
    return offerings[name]


# ---- mobile: ENet's "Orange Plans" as published on enetworks.gy (2026) ----
# Prepaid validity plans. 30-day plans are modelled as monthly recurring; the
# 1-day / 7-day passes are one-time top-ups; the 90-day plan recurs quarterly.
MOBILE = [
    ("Orange 30 Days 5G", 3500,
     "80 GB premium 5G data, 100 off-net minutes + SMS, unlimited ENet-to-ENet, USA roaming included. 30 days."),
    ("Orange 30 Days Extra 5G", 5000,
     "100 GB premium 5G data, 250 off-net minutes + SMS, unlimited ENet-to-ENet, USA roaming included. 30 days."),
    ("30 Days Data 5G", 3500,
     "60 GB of 5G data, data only — for a second SIM or a hotspot. 30 days."),
    ("Voice Only 30 Days", 1000,
     "Unlimited ENet-to-ENet calls and SMS for 30 days; off-net calls $10/min."),
]
for name, price, desc in MOBILE:
    ensure_offering(name, "Mobile plans", price, desc)
if "90-Day Plan" not in offerings:
    p90 = ensure_price("90-Day Plan quarterly", 9000)
    req("PATCH", f"productOfferingPrice/{p90['id']}", {"recurringChargePeriodLength": 3})
    offerings["90-Day Plan"] = req("POST", "productOffering", {
        "name": "90-Day Plan", "lifecycleStatus": "Active", "isBundle": False, "isSellable": True,
        "description": "150 GB (50 GB a month), 60 off-net minutes + SMS a month, 50 international Zone 1 minutes a month. 90 days.",
        "category": [cat_ref("Mobile plans")],
        "productOfferingPrice": [{"id": p90["id"], "name": p90["name"]}]})
    print("offering: 90-Day Plan (Mobile plans) 9000 GYD / 3 months")

# ---- fibre: OnFiber tiers as published (300M / 600M / 1 Gig), ENet TV + Wi-Fi 6 included ----
FIBRE = [
    ("OnFiber 300", 8900,
     "300 Mbps fibre to the home with ENet TV and the DreamTV app included, Infinity Wi-Fi 6 mesh free. Installed in 3-7 days."),
    ("OnFiber 600", 13100,
     "600 Mbps fibre to the home with ENet TV included — for the whole household. Installed in 3-7 days."),
    ("OnFiber 1 Gig", 26300,
     "Gigabit fibre with ENet TV included — home office, gaming, everything at once."),
]
for name, price, desc in FIBRE:
    ensure_offering(name, "Broadband", price, desc)

# ---- TV: DreamTV packages as published (fibre customers get DreamTV free) ----
TV = [
    ("DreamTV Wireless Preferred", 3600, "70 channels over ENet wireless — movies, sports, news. DreamTV app included."),
    ("DreamTV Wireless Ultimate", 5400, "96 channels over ENet wireless — the full line-up with premium programmes."),
    ("DreamTV Satellite Ultimate", 5990, "78 channels by satellite, countrywide — Lethem, Mabaruma, Mahdia included."),
]
for name, price, desc in TV:
    ensure_offering(name, "TV & Add-ons", price, desc)

# ---- top-ups: the 1-day and 7-day Orange passes a prepaid market lives on ----
TOPUPS = [
    ("Orange 1 Day", 300, "5 GB, 5 off-net minutes + SMS, unlimited ENet-to-ENet. Valid 1 day."),
    ("Orange 7 Days", 1000, "10 GB, 30 off-net minutes + SMS, unlimited ENet-to-ENet. Valid 7 days."),
]
for name, price, desc in TOPUPS:
    ensure_offering(name, "Top-ups", price, desc, price_type="oneTime", period=None)

# ---- the cross-sell bundle (a DEMO shape, not an ENet product): fibre + a 30-day mobile line ----
if "Bundles" not in cats:
    cats["Bundles"] = req("POST", "category", {"name": "Bundles", "lifecycleStatus": "Active"})
ensure_offering("ENet Home + Mobile", "Bundles", 11900,
                "OnFiber 300 at home and Orange 30 Days 5G in your pocket — one bill, $500 off.",
                bundle_of=["OnFiber 300", "Orange 30 Days 5G"])

# ---- retire the earlier demo names that were never ENet products ----
for stale in ("ENet Prepaid 50 GB", "Orange Unlimited Data", "Orange Unlimited Voice+Data", "Orange Unlimited Extra",
              "ENet Fibre 100", "OnFiber 350", "OnFiber 1000", "ENet TV Complete", "ENet One Home & Mobile"):
    o = offerings.pop(stale, None)
    if o:
        req("DELETE", f"productOffering/{o['id']}")
        for pn in (f"{stale} monthly", f"{stale} Monthly"):
            if pn in prices:
                req("DELETE", f"productOfferingPrice/{prices[pn]['id']}")
        print(f"retired: {stale}")

# ---- one-time charges so checkout has an amount due now --------------------
sim = ensure_price("SIM activation", 1000, price_type="oneTime", period=None)
install = ensure_price("Fibre installation", 5000, price_type="oneTime", period=None)
SETUP = {**{n: sim for n, _, _ in MOBILE}, "90-Day Plan": sim, **{n: install for n, _, _ in FIBRE},
         "ENet Home + Mobile": install}
for name, price in SETUP.items():
    o = offerings[name]
    have = {p.get("name") for p in (o.get("productOfferingPrice") or [])}
    if price["name"] not in have:
        req("PATCH", f"productOffering/{o['id']}", {"productOfferingPrice":
            [*(o.get("productOfferingPrice") or []),
             {"id": price["id"], "name": price["name"]}]})
        print(f"{price['name']} on: {name}")

# ---- allowances so meters and "running low" journeys work ------------------
existing_allow = {(a["productOffering"]["id"], a["usageType"])
                  for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}
ALLOW = [("Orange 30 Days 5G", 80, 0.0), ("Orange 30 Days Extra 5G", 100, 0.0),
         ("30 Days Data 5G", 60, 0.0), ("90-Day Plan", 150, 0.0)]
for name, gb, overage in ALLOW:
    o = offerings[name]
    if (o["id"], "Mobile data") in existing_allow:
        continue
    req("POST", f"{USAGE}/usageAllowance", {
        "productOffering": {"id": o["id"], "name": name},
        "usageType": "Mobile data",
        "allowance": {"value": gb, "units": "GB"},
        "overagePrice": {"value": overage, "unit": CUR}})
    print(f"allowance: {name} = {gb} GB (overage {overage} {CUR}/GB)")

# ---- checkout seams: number pool, delivery, payment -------------------------
# Guyana +592, closed 7-digit plan; ENet's mobile ranges per the TA numbering plan
# (ITU OB 1336): 635, 710-720, 730-742, 760-767 — 710 is ours. (624 belongs to One.)
pools = req("GET", f"{POOL}/resourcePool")
# (the early "+592624" pool from the first demo pass is a One range; pools cannot be
#  deleted over the API, so it stays inert and unused — new numbers draw from 710)
if not any(p.get("prefix") == "+592710" for p in pools):
    req("POST", f"{POOL}/resourcePool",
        {"name": "ENet mobile numbers (710 range)", "resourceType": "msisdn", "prefix": "+592710"})
    print("MSISDN pool: +592710xxxx")


def put(url, body):
    r = urllib.request.Request(url, data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOKEN}"},
        method="PUT")
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


# the generic HTTP carrier seam pointed at the dev logistics mock — in
# production this is ENet's own courier / a Georgetown last-mile partner
# No Guyanese operator home-delivers SIMs or phones: collection at an ENet store is
# the normal rail (the mock's /pickup-points lists the stores by region), with a
# WhatsApp-dispatched courier for the coast as the home option.
put(f"{FUL}/carrier", {
    "carrier": "http", "displayName": "ENet", "baseUrl": "http://mock-logistics:8080",
    "secretRef": "ENET_COURIER_API_KEY", "methods": ["pickupPoint", "home"], "isDefault": True,
    # delivery tiers by region digit (GPOC postcode): coast same/next day, Linden 2-3 days,
    # the interior (Regions 1, 7, 8, 9) by boat or air — days to weeks, weather permitting
    "config": {"etaByPrefix": {"4": "same day in Georgetown and Demerara", "3": "1–2 days (West Demerara / Essequibo Islands)",
                               "2": "1–2 days (Essequibo Coast)", "5": "1–2 days (Mahaica–Berbice)", "6": "1–2 days (Berbice)",
                               "10": "2–3 days (Linden)", "1": "5–10 days by boat or air, weather permitting",
                               "7": "3–7 days by boat or air (Bartica sooner)", "8": "5–10 days by air, weather permitting",
                               "9": "5–10 days by road or air, seasonal"},
               "etaDefault": "1–3 days"}})
print("carrier: ENet stores (pickup) + courier (home) via the http seam")

# PayPal and Stripe do not serve Guyana. Cards clear through the bank acquirer
# (the built-in card rail); MMG — Mobile Money Guyana — is the wallet everyone has.
try:
    req("DELETE", f"{PAY}/paymentProvider/paypal")
    print("payment: PayPal removed (not available in Guyana)")
except Exception:
    pass
put(f"{PAY}/paymentProvider", {
    "provider": "mmg", "displayName": "MMG mobile money", "baseUrl": "http://mock-mmg:8080",
    "secretRef": "MMG_API_KEY", "methods": ["card", "mmg"], "isDefault": False})
print("payment: MMG mobile money alongside the card rail")

# ---- tax: 14% VAT, prices tax-inclusive (revenue splits net + VAT payable) ----
REV = "http://localhost:8080/revenue/v1"
try:  # revenue is optional weight (fleet.sh demo slice) — the mapping row persists once written
    tax_row = next((m for m in req("GET", f"{REV}/accountMapping") if m.get("key") == "tax"), None)
    if not tax_row or str(tax_row.get("configValue")) not in ("14", "14.0", "14.00"):
        req("POST", f"{REV}/accountMapping", {"key": "tax", "accountCode": "2700", "accountName": "VAT payable (GRA)", "configValue": 14})
        print("tax: 14% VAT mapping (tax-inclusive prices)")
except Exception:
    print("tax: revenue service not running — default VAT mapping left as is (start revenue to seed it)")

# ---- collections: a Guyana policy in GYD (whole dollars; PUC-style due + grace + disconnect) ----
BILLS = "http://localhost:8080/tmf-api/customerBillManagement/v4"
if not any(p.get("country") == "GY" and p.get("active") for p in req("GET", f"{BILLS}/dunningPolicy")):
    req("POST", f"{BILLS}/dunningPolicy", {
        "name": "ENet collections (GY)", "country": "GY", "currency": "GYD", "paymentTermDays": 30,
        "entryThreshold": 1000, "reconnectionFee": 0, "writeOffThreshold": 5000,
        "promiseMaxPerPeriod": 2, "promisePeriodDays": 90, "promiseMaxDays": 14,
        "steps": [
            {"offsetDays": 3, "action": "remind", "templateId": "dunning-reminder"},
            {"offsetDays": 10, "action": "warn", "templateId": "dunning-warning"},
            {"offsetDays": 40, "action": "restrict", "templateId": "dunning-restricted"},
            {"offsetDays": 47, "action": "suspend", "templateId": "dunning-suspended"}]})
    print("collections: GY/GYD policy (3-day grace reminder, restrict day 40)")

# ---- fibre footprint: every fibre offer is install-gated in the same areas ----
QUAL = "http://localhost:8080/tmf-api/productOfferingQualification/v4"
for name in ("OnFiber 300", "OnFiber 600", "OnFiber 1 Gig", "ENet Home + Mobile"):
    o = offerings[name]
    areas = req("GET", f"{QUAL}/serviceableArea?productOfferingId={o['id']}&limit=100")
    for a in areas:  # retire the early demo's phone-code prefixes (592/593 were never postcodes)
        if a.get("postcodePrefix") in ("592", "593"):
            req("DELETE", f"{QUAL}/serviceableArea/{a['id']}")
            print(f"footprint: retired {a.get('postcodePrefix')} on {name}")
    have = {a.get("postcodePrefix") for a in areas if a.get("postcodePrefix") not in ("592", "593")}
    # Guyana Post Office 7-digit postcodes start with the REGION digit: 4 = Demerara-Mahaica
    # (Georgetown, East Bank/East Coast), 7 = Cuyuni-Mazaruni (Bartica, on the submarine link)
    for prefix, label in [("4", "Region 4 — Georgetown & Demerara coast"), ("7", "Region 7 — Bartica (submarine link)")]:
        if prefix in have:
            continue
        req("POST", f"{QUAL}/serviceableArea", {"name": f"Fibre footprint: {label}",
            "productOffering": {"id": o["id"], "name": name, "@referredType": "ProductOffering"},
            "postcodePrefix": prefix})
        print(f"footprint: {name} serviceable in {prefix} ({label})")

# ---- VAT per price (GRA VAT Policy 26): residential internet data is ZERO-RATED,
# voice/TV/one-time charges carry 14%. TMF620 price.tax → TMF678 appliedTax → ledger.
ZERO_RATED = {"OnFiber 300 monthly", "OnFiber 600 monthly", "OnFiber 1 Gig monthly", "30 Days Data 5G monthly"}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
for pname, p in prices.items():
    rate = 0 if pname in ZERO_RATED else 14
    have = (p.get("tax") or [{}])[0].get("taxRate")
    if have is None or float(have) != rate:
        req("PATCH", f"productOfferingPrice/{p['id']}", {"tax": [{"taxCategory": "VAT", "taxRate": rate}]})
        print(f"tax: {pname} -> {rate}%")

# ---- power reality: grid blackouts are weekly news; an ONT battery backup is a real SKU ----
STOCK = "http://localhost:8080/tmf-api/productStockManagement/v4"
if "Devices" not in cats:
    cats["Devices"] = req("POST", "category", {"name": "Devices", "lifecycleStatus": "Active"})
ensure_offering("ONT Battery Backup", "Devices", 12000,
                "Keeps your fibre modem and Wi-Fi up through a GPL outage — up to 8 hours on a full charge. "
                "Plugs in beside the ONT; no electrician needed. (Demo price.)",
                price_type="oneTime", period=None)
bb = offerings["ONT Battery Backup"]
if not req("GET", f"{STOCK}/productStock?productOfferingId={bb['id']}"):
    req("POST", f"{STOCK}/productStock", {"name": "ONT Battery Backup stock",
        "productOffering": {"id": bb["id"], "name": bb["name"], "@referredType": "ProductOffering"},
        "stockedQuantity": {"amount": 120, "units": "unit"}})
    print("stock: ONT Battery Backup x120")

# ---- the brand: ENet's own logo (public asset) as the tenant's brand document ----
import base64, os
DOC = "http://localhost:8080/tmf-api/documentManagement/v4"
docs = req("GET", f"{DOC}/document")
if not any(d.get("name") == "ENet-logo" for d in docs):
    # ops/demo-assets is git-ignored (media): drop the operator's public logo at
    # ops/demo-assets/enet/enet-logo.png (enetworks.gy/assets/images/enet-logo_colour.png)
    logo_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "demo-assets", "enet", "enet-logo.png")
    if os.path.exists(logo_path):
        with open(logo_path, "rb") as fh:
            req("POST", f"{DOC}/document", {"name": "ENet-logo", "category": "brand", "mimeType": "image/png",
                                             "content": base64.b64encode(fh.read()).decode()})
        print("brand: ENet logo uploaded")
    else:
        print("brand: no logo file at ops/demo-assets/enet/enet-logo.png — storefront hides the logo slot")

# ---- the shop window: the operator's own campaign creative as 'banner' documents ----
# (ops/demo-assets is git-ignored; drop ENet's public homepage creatives there)
banner_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "demo-assets", "enet", "banners")
existing_banners = {}
for d in req("GET", f"{DOC}/document?category=banner"):
    if d.get("link"):
        existing_banners[d["name"]] = d
    else:  # created before captions/destinations existed — replace
        req("DELETE", f"{DOC}/document/{d['id']}")
extra = offerings.get("Orange 30 Days Extra 5G", {})
for fname, name, caption, link in [
    ("switched_2.jpg", "banner-switched", "#SWITCHED — keep your number, move to Guyana's fastest network", "/?tab=Mobile"),
    ("bundled-cpl-plan.jpg", "banner-orange-extra-tv", "Orange 30 Days Extra includes ENet TV — activate and watch on the go", f"/offering/{extra.get('id', '')}"),
    ("myenet_app_2026_1.jpg", "banner-my-enet-app", "The all-new My ENet App is here", "https://apps.apple.com/gy/app/my-enet/id1618927353"),
]:
    path = os.path.join(banner_dir, fname)
    if name in existing_banners or not os.path.exists(path):
        continue
    with open(path, "rb") as fh:
        req("POST", f"{DOC}/document", {"name": name, "category": "banner", "description": caption, "link": link,
                                         "mimeType": "image/jpeg", "content": base64.b64encode(fh.read()).decode()})
    print(f"banner: {name}")

# ---- plan comparison: truthful per-plan characteristics (the shop's compare table reads these) ----
SPEC = "productSpecification"
COMPARE = {
    "Orange 30 Days 5G":       [("Data", "80 GB"), ("Network", "5G"), ("Calls & texts", "Unlimited ENet + 100 off-net min/SMS"), ("USA roaming", "Included"), ("Validity", "30 days")],
    "Orange 30 Days Extra 5G": [("Data", "100 GB"), ("Network", "5G"), ("Calls & texts", "Unlimited ENet + 250 off-net min/SMS"), ("USA roaming", "Included"), ("Validity", "30 days")],
    "30 Days Data 5G":         [("Data", "60 GB"), ("Network", "5G"), ("Calls & texts", "Data only"), ("USA roaming", "—"), ("Validity", "30 days")],
    "Voice Only 30 Days":      [("Data", "—"), ("Network", "4G/5G"), ("Calls & texts", "Unlimited ENet; off-net $10/min"), ("USA roaming", "—"), ("Validity", "30 days")],
    "90-Day Plan":             [("Data", "150 GB (50 GB/month)"), ("Network", "5G"), ("Calls & texts", "Unlimited ENet + 60 off-net + 50 intl Zone 1 min/month"), ("USA roaming", "—"), ("Validity", "90 days")],
}
def char(name, value):
    return {"name": name, "valueType": "string", "configurable": False,
            "productSpecCharacteristicValue": [{"value": value, "isDefault": True}]}
for pname, chars in COMPARE.items():
    o = offerings.get(pname)
    if not o:
        continue
    spec_ref = o.get("productSpecification")
    if not spec_ref:
        spec = req("POST", f"{SPEC}", {"name": f"{pname} spec", "lifecycleStatus": "Active",
                                       "productSpecCharacteristic": [char(n, v) for n, v in chars]})
        req("PATCH", f"productOffering/{o['id']}", {"productSpecification": {"id": spec["id"], "name": spec["name"], "@referredType": "ProductSpecification"}})
        print(f"spec: {pname} -> {len(chars)} comparison characteristics")
    else:
        spec = req("GET", f"{SPEC}/{spec_ref['id']}")
        have = {c["name"] for c in (spec.get("productSpecCharacteristic") or [])}
        missing = [char(n, v) for n, v in chars if n not in have]
        if missing:
            req("PATCH", f"{SPEC}/{spec['id']}", {"productSpecCharacteristic": [*(spec.get("productSpecCharacteristic") or []), *missing]})
            print(f"spec: {pname} +{len(missing)} characteristics")

# ---- installers: Georgetown calendar + the roster capacity derives from ----
APPT = "http://localhost:8080/tmf-api/appointment/v4"
put(f"{APPT}/scheduleConfig", {
    "timezone": "America/Guyana", "workingDays": ["MON", "TUE", "WED", "THU", "FRI", "SAT"],
    "slotStarts": ["08:00", "10:00", "13:00", "15:00"], "slotHours": 2, "daysAhead": 10,
    "defaultCapacity": 2})
print("calendar: America/Guyana, Mon-Sat, 08/10/13/15 windows")
roster = {t["name"]: t for t in req("GET", f"{APPT}/technician")}
for name, zone, days, start, end, skills in [
    ("Asha Persaud", "Georgetown", ["MON", "TUE", "WED", "THU", "FRI"], "08:00", "17:00", ["fibre", "tv"]),
    ("Ravi Singh", "East Bank Demerara", ["MON", "TUE", "WED", "THU", "FRI", "SAT"], "08:00", "17:00", ["fibre"]),
    ("Devika Ramnarine", "East Coast Demerara", ["TUE", "WED", "THU", "FRI", "SAT"], "08:00", "12:00", ["fibre", "mobile"]),
]:
    if name in roster:
        continue
    req("POST", f"{APPT}/technician", {"name": name, "zone": zone, "workingDays": days,
                                       "startTime": start, "endTime": end, "skills": skills})
    print(f"technician: {name} ({zone}) {'/'.join(days)} {start}-{end}")

print("\nENet tenant seeded: Orange Plans (4 x 30-day + 90-day) + OnFiber 300/600/1 Gig + 3 DreamTV packages "
      "+ Orange 1/7-day passes + Home + Mobile bundle, allowances, +592 710 number pool, store pickup + courier, "
      "MMG wallet, 14% VAT, GY collections, Georgetown calendar + 3-technician roster — all GYD.")
