#!/usr/bin/env python3
"""Taranga demo — the vendor's own demo tenant (Taranga AS, taranga.no).
A Norwegian converged operator shown in English and NOK on the built-in
Norway rails: fibre + mobile + TV, top-ups, a Home + Mobile bundle, the
priority-slice pass and tier, Posten/Helthjem delivery, Vipps + Klarna beside
the card rail, 25 % MVA tax-inclusive prices, a NO/NOK collections policy, an
Oslo/Bergen/Trondheim footprint and an installer roster. Prices are demo
values. Idempotent — safe to re-run. This is REPO CANON: the hosted demo
serves this tenant on *.taranga.no.
"""
import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = "http://localhost:8085/realms/taranga/protocol/openid-connect/token"
API = "http://localhost:8080/tmf-api/productCatalogManagement/v4"
USAGE = "http://localhost:8080/tmf-api/usageManagement/v4"
POOL = "http://localhost:8080/tmf-api/resourcePoolManagement/v4"
FUL = "http://localhost:8080/tmf-api/shippingOrderManagement/v4"
PAY = "http://localhost:8080/tmf-api/paymentManagement/v4"
DOC = "http://localhost:8080/tmf-api/documentManagement/v4"
QUAL = "http://localhost:8080/tmf-api/productOfferingQualification/v4"
BILLS = "http://localhost:8080/tmf-api/customerBillManagement/v4"
APPT = "http://localhost:8080/tmf-api/appointment/v4"
STOCK = "http://localhost:8080/tmf-api/productStockManagement/v4"
REV = "http://localhost:8080/revenue/v1"
CUR = "NOK"
BRAND = "#4A4AC3"  # the wave in the Taranga logo


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo",
                                   "username": "demo", "password": "demo"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None, quiet=False):
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
        if not quiet:
            print("ERR", method, path, e.code, e.read().decode()[:300])
        raise


def put(url, body):
    return req("PUT", url, body)


offerings = {o["name"]: o for o in req("GET", "productOffering?limit=100")}
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
cats = {c["name"]: c for c in req("GET", "category?limit=100")}
# storefront tabs key on these exact category names (Shop.jsx LOB list)
for name in ("Mobile plans", "Broadband", "TV & Add-ons", "Top-ups", "Bundles", "Devices"):
    if name not in cats:
        cats[name] = req("POST", "category", {"name": name, "lifecycleStatus": "Active"})
        print(f"category: {name}")


def cat_ref(name):
    return {"id": cats[name]["id"], "name": name, "@referredType": "Category"}


def ensure_price(name, value, price_type="recurring", period="month"):
    if name in prices:
        return prices[name]
    body = {"name": name, "priceType": price_type, "price": {"unit": CUR, "value": value},
            "lifecycleStatus": "Active"}
    if period:
        body["recurringChargePeriodType"] = period
    prices[name] = req("POST", "productOfferingPrice", body)
    print(f"price: {name} = {value} {CUR}")
    return prices[name]


def ensure_offering(name, category, value, description, bundle_of=None, price_type="recurring", period="month"):
    if name in offerings:
        return offerings[name]
    suffix = " monthly" if price_type == "recurring" else ""
    price = ensure_price(f"{name}{suffix}", value, price_type, period)
    body = {"name": name, "description": description, "lifecycleStatus": "Active",
            "isBundle": bool(bundle_of), "isSellable": True, "category": [cat_ref(category)],
            "productOfferingPrice": [{"id": price["id"], "name": price["name"]}]}
    if bundle_of:
        body["bundledProductOffering"] = [{"id": offerings[n]["id"], "name": n, "@referredType": "ProductOffering"}
                                          for n in bundle_of]
    offerings[name] = req("POST", "productOffering", body)
    print(f"offering: {name} ({category}) {value} {CUR}")
    return offerings[name]


# ---- mobile: a Norwegian line-up (EU/EEA roaming included by law) -----------------------
MOBILE = [
    ("Taranga Mobile 5 GB", 199, "5 GB, unlimited calls and texts in Norway, EU/EEA roaming included. No binding."),
    ("Taranga Mobile 20 GB", 299, "20 GB on 5G, unlimited calls and texts, EU/EEA roaming included. Data rollover. No binding."),
    ("Taranga Mobile Unlimited 5G", 449, "Unlimited 5G data at full speed, unlimited calls and texts, 30 GB EU/EEA roaming. No binding."),
    ("Taranga Data SIM 30 GB", 199, "30 GB of 5G data for a tablet, router or second device. Data only."),
]
for name, price, desc in MOBILE:
    ensure_offering(name, "Mobile plans", price, desc)

# ---- fibre: Oslo, Bergen and Trondheim footprint, Wi-Fi 6 router included --------------
FIBRE = [
    ("Taranga Fiber 300", 699, "300/300 Mbit/s symmetric fibre with a Wi-Fi 6 router included. Installed within 5-10 working days."),
    ("Taranga Fiber 1000", 999, "1000/1000 Mbit/s symmetric fibre for the whole household, Wi-Fi 6 router included."),
    ("Taranga Fiber 2500", 1299, "2.5 Gbit/s symmetric fibre for the home office, gaming and everything at once (XGS-PON)."),
]
for name, price, desc in FIBRE:
    ensure_offering(name, "Broadband", price, desc)

# ---- TV & add-ons ------------------------------------------------------------------------
TV = [
    ("Taranga TV", 249, "40 channels and the Taranga TV app on every screen; pick your own extra channels."),
    ("Taranga TV + Sport", 449, "Taranga TV with the full sports package: Eliteserien, Premier League, Champions League."),
    ("Wi-Fi Mesh Point", 49, "One extra mesh point for the rooms the router does not reach. Cancel any time."),
]
for name, price, desc in TV:
    ensure_offering(name, "TV & Add-ons", price, desc)

# ---- top-ups -----------------------------------------------------------------------------
TOPUPS = [
    ("Data Boost 10 GB", 99, "10 GB of extra data on your line, valid until the end of the month."),
    ("Roam World 7 Days", 199, "3 GB, 60 minutes and 60 texts outside the EU/EEA for 7 days."),
]
for name, price, desc in TOPUPS:
    ensure_offering(name, "Top-ups", price, desc, price_type="oneTime", period=None)

# ---- the bundle --------------------------------------------------------------------------
ensure_offering("Taranga Home + Mobile", "Bundles", 899,
                "Taranga Fiber 300 at home and Taranga Mobile 20 GB in your pocket. One bill, 99 kr off every month.",
                bundle_of=["Taranga Fiber 300", "Taranga Mobile 20 GB"])

# ---- one-time charges so checkout has an amount due now ----------------------------------
sim = ensure_price("SIM & activation", 49, price_type="oneTime", period=None)
install = ensure_price("Fibre installation", 1990, price_type="oneTime", period=None)
SETUP = {**{n: sim for n, _, _ in MOBILE}, **{n: install for n, _, _ in FIBRE}, "Taranga Home + Mobile": install}
for name, price in SETUP.items():
    o = offerings[name]
    have = {p.get("name") for p in (o.get("productOfferingPrice") or [])}
    if price["name"] not in have:
        req("PATCH", f"productOffering/{o['id']}", {"productOfferingPrice":
            [*(o.get("productOfferingPrice") or []), {"id": price["id"], "name": price["name"]}]})
        print(f"{price['name']} on: {name}")

# ---- allowances so meters and "running low" journeys work -------------------------------
existing_allow = {(a["productOffering"]["id"], a["usageType"]) for a in req("GET", f"{USAGE}/usageAllowance?limit=100")}
ALLOW = [("Taranga Mobile 5 GB", 5, 0.0), ("Taranga Mobile 20 GB", 20, 0.0),
         ("Taranga Mobile Unlimited 5G", 1000, 0.0), ("Taranga Data SIM 30 GB", 30, 0.0)]
for name, gb, overage in ALLOW:
    o = offerings[name]
    if (o["id"], "Mobile data") in existing_allow:
        continue
    req("POST", f"{USAGE}/usageAllowance", {"productOffering": {"id": o["id"], "name": name}, "usageType": "Mobile data",
                                            "allowance": {"value": gb, "units": "GB"}, "overagePrice": {"value": overage, "unit": CUR}})
    print(f"allowance: {name} = {gb} GB")

# ---- checkout seams: Norwegian numbers, Posten/Helthjem, Vipps + Klarna ------------------
if not any(p.get("prefix") == "+4741" for p in req("GET", f"{POOL}/resourcePool")):
    req("POST", f"{POOL}/resourcePool", {"name": "Taranga mobile numbers", "resourceType": "msisdn", "prefix": "+4741"})
    print("MSISDN pool: +47 41xx xxxx")
for c in [
    {"carrier": "helthjem", "displayName": "Helthjem", "baseUrl": "http://mock-logistics:8080",
     "secretRef": "HELTHJEM_API_KEY", "methods": ["home"], "isDefault": True},
    {"carrier": "bring", "displayName": "Posten/Bring", "baseUrl": "http://mock-bring:8080",
     "secretRef": "BRING_API_KEY", "methods": ["home", "pickupPoint"], "isDefault": False},
]:
    put(f"{FUL}/carrier", c)
    print(f"carrier: {c['displayName']} ({', '.join(c['methods'])})")
put(f"{PAY}/paymentProvider", {"provider": "vipps", "displayName": "Vipps", "baseUrl": "http://mock-vipps:8080",
                               "secretRef": "VIPPS_API_KEY", "methods": ["vipps"], "isDefault": False})
put(f"{PAY}/paymentProvider", {"provider": "klarna", "displayName": "Klarna", "baseUrl": "http://mock-klarna:8080",
                               "secretRef": "KLARNA_API_KEY", "webhookSecretRef": "KLARNA_WEBHOOK_SECRET",
                               "methods": ["card", "klarna"], "isDefault": False})
print("payment: Vipps + Klarna beside the card rail")

# ---- tax: 25 % MVA, consumer prices tax-inclusive ----------------------------------------
try:
    tax_row = next((m for m in req("GET", f"{REV}/accountMapping") if m.get("key") == "tax"), None)
    if not tax_row or str(tax_row.get("configValue")) not in ("25", "25.0", "25.00"):
        req("POST", f"{REV}/accountMapping", {"key": "tax", "accountCode": "2700", "accountName": "MVA payable (Skatteetaten)", "configValue": 25})
        print("tax: 25% MVA mapping")
except Exception:
    print("tax: revenue service not running — start revenue to seed the MVA mapping")
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
for pname, p in prices.items():
    if (p.get("tax") or [{}])[0].get("taxRate") in (None, "") or float((p.get("tax") or [{}])[0].get("taxRate") or 0) != 25:
        req("PATCH", f"productOfferingPrice/{p['id']}", {"tax": [{"taxCategory": "MVA", "taxRate": 25}]})
        print(f"tax: {pname} -> 25%")

# ---- collections: NO/NOK — purring after 14 days, inkassovarsel, restrict, suspend ------
if not any(p.get("country") == "NO" and p.get("active") for p in req("GET", f"{BILLS}/dunningPolicy")):
    req("POST", f"{BILLS}/dunningPolicy", {
        "name": "Taranga collections (NO)", "country": "NO", "currency": "NOK", "paymentTermDays": 14,
        "entryThreshold": 250, "reconnectionFee": 199, "writeOffThreshold": 500,
        "promiseMaxPerPeriod": 2, "promisePeriodDays": 90, "promiseMaxDays": 14,
        "steps": [{"offsetDays": 3, "action": "remind", "templateId": "dunning-reminder"},
                  {"offsetDays": 14, "action": "warn", "templateId": "dunning-warning"},
                  {"offsetDays": 44, "action": "restrict", "templateId": "dunning-restricted"},
                  {"offsetDays": 58, "action": "suspend", "templateId": "dunning-suspended"}]})
    print("collections: NO/NOK policy (14-day terms, inkassovarsel day 14, restrict day 44 — 30 days after the warning)")

# ---- fibre footprint: 4-digit Norwegian postcodes by leading digit ----------------------
for name in ("Taranga Fiber 300", "Taranga Fiber 1000", "Taranga Fiber 2500", "Taranga Home + Mobile"):
    o = offerings[name]
    have = {a.get("postcodePrefix") for a in req("GET", f"{QUAL}/serviceableArea?productOfferingId={o['id']}&limit=100")}
    for prefix, label in [("0", "Oslo"), ("1", "Oslo & Akershus"), ("5", "Bergen"), ("7", "Trondheim")]:
        if prefix in have:
            continue
        req("POST", f"{QUAL}/serviceableArea", {"name": f"Fibre footprint: {label}",
            "productOffering": {"id": o["id"], "name": name, "@referredType": "ProductOffering"}, "postcodePrefix": prefix})
        print(f"footprint: {name} serviceable in {prefix}xxx ({label})")

# ---- plan comparison: truthful per-plan characteristics ---------------------------------
SPEC = "productSpecification"


def char(name, value):
    return {"name": name, "valueType": "string", "configurable": False,
            "productSpecCharacteristicValue": [{"value": value, "isDefault": True}]}


def ensure_spec_chars(offering, chars):
    ref = offering.get("productSpecification")
    if not ref:
        spec = req("POST", SPEC, {"name": f"{offering['name']} spec", "lifecycleStatus": "Active",
                                  "productSpecCharacteristic": [char(n, v) for n, v in chars]})
        req("PATCH", f"productOffering/{offering['id']}", {"productSpecification": {"id": spec["id"], "name": spec["name"], "@referredType": "ProductSpecification"}})
        return True
    spec = req("GET", f"{SPEC}/{ref['id']}")
    have = {c["name"] for c in (spec.get("productSpecCharacteristic") or [])}
    missing = [char(n, v) for n, v in chars if n not in have]
    if missing:
        req("PATCH", f"{SPEC}/{spec['id']}", {"productSpecCharacteristic": [*(spec.get("productSpecCharacteristic") or []), *missing]})
    return bool(missing)


COMPARE = {
    "Taranga Mobile 5 GB":         [("chargingSpecId", "RG-DATA-60"), ("Data", "5 GB"), ("Network", "4G/5G"), ("Calls & texts", "Unlimited"), ("EU/EEA roaming", "Included"), ("Binding", "None")],
    "Taranga Mobile 20 GB":        [("chargingSpecId", "RG-DATA-60"), ("Data", "20 GB + rollover"), ("Network", "5G"), ("Calls & texts", "Unlimited"), ("EU/EEA roaming", "Included"), ("Binding", "None")],
    "Taranga Mobile Unlimited 5G": [("chargingSpecId", "RG-UNL"), ("Data", "Unlimited"), ("Network", "5G full speed"), ("Calls & texts", "Unlimited"), ("EU/EEA roaming", "30 GB"), ("Binding", "None")],
    "Taranga Data SIM 30 GB":      [("chargingSpecId", "RG-DATA-60"), ("Data", "30 GB"), ("Network", "5G"), ("Calls & texts", "Data only"), ("EU/EEA roaming", "Included"), ("Binding", "None")],
}
for pname, chars in COMPARE.items():
    if ensure_spec_chars(offerings[pname], chars):
        print(f"spec: {pname} -> comparison characteristics")

# ---- network slicing: the Match Day Boost pass and a priority tier ----------------------
ensure_offering("Match Day Boost", "Top-ups", 29,
                "Priority on the 5G network for 6 hours — smooth streams and calls when the whole stand is online. "
                "Turns on the moment you buy it, switches itself off after.", price_type="oneTime", period=None)
if ensure_spec_chars(offerings["Match Day Boost"], [("sliceProfile", "priority"), ("boostHours", "6"),
                                                    ("sliceChargingSpecId", "RG-DATA-60-PRIO"), ("guaranteedDlMbps", "50"),
                                                    ("Priority network", "6 hours · 50 Mbit/s guaranteed"), ("Validity", "6 hours")]):
    print("spec: Match Day Boost -> priority slice pass, 6 h, guarantee 50 Mbit/s")
ensure_offering("Taranga Mobile Priority 5G", "Mobile plans", 549,
                "Unlimited 5G on the priority slice all month — first in line at the stadium, the festival and the commute. "
                "50 Mbit/s guaranteed, credited if we miss it.")
if ensure_spec_chars(offerings["Taranga Mobile Priority 5G"], [("chargingSpecId", "RG-UNL"), ("sliceProfile", "priority"),
        ("sliceChargingSpecId", "RG-UNL-PRIO"), ("guaranteedDlMbps", "50"), ("Data", "Unlimited"), ("Network", "5G priority slice"),
        ("Calls & texts", "Unlimited"), ("EU/EEA roaming", "30 GB"), ("Binding", "None"), ("Priority network", "Included")]):
    print("spec: Taranga Mobile Priority 5G -> priority tier")
prices = {p["name"]: p for p in req("GET", "productOfferingPrice?limit=100")}
for pname in ("Taranga Mobile Priority 5G monthly", "Match Day Boost"):
    row = prices.get(pname)
    if row and float((row.get("tax") or [{}])[0].get("taxRate") or 0) != 25:
        req("PATCH", f"productOfferingPrice/{row['id']}", {"tax": [{"taxCategory": "MVA", "taxRate": 25}]})

# ---- a device so the Devices tab has stock ----------------------------------------------
ensure_offering("Wi-Fi 6 Router (spare)", "Devices", 1490,
                "The same Wi-Fi 6 router we install with fibre, as a spare or for a cabin. Ships with Posten.",
                price_type="oneTime", period=None)
rt = offerings["Wi-Fi 6 Router (spare)"]
if not req("GET", f"{STOCK}/productStock?productOfferingId={rt['id']}"):
    req("POST", f"{STOCK}/productStock", {"name": "Wi-Fi 6 Router stock",
        "productOffering": {"id": rt["id"], "name": rt["name"], "@referredType": "ProductOffering"},
        "stockedQuantity": {"amount": 80, "units": "unit"}})
    print("stock: Wi-Fi 6 Router x80")

# ---- installers: Oslo calendar, a three-city roster -------------------------------------
put(f"{APPT}/scheduleConfig", {"timezone": "Europe/Oslo", "workingDays": ["MON", "TUE", "WED", "THU", "FRI"],
                               "slotStarts": ["08:00", "10:00", "13:00", "15:00"], "slotHours": 2, "daysAhead": 10, "defaultCapacity": 2})
roster = {t["name"]: t for t in req("GET", f"{APPT}/technician")}
for name, zone, days, start, end, skills in [
    ("Sindre Haugen", "Oslo", ["MON", "TUE", "WED", "THU", "FRI"], "08:00", "16:00", ["fibre", "tv"]),
    ("Ingrid Solberg", "Bergen", ["MON", "TUE", "WED", "THU", "FRI"], "08:00", "16:00", ["fibre"]),
    ("Jonas Berg", "Trondheim", ["TUE", "WED", "THU", "FRI"], "08:00", "16:00", ["fibre", "mobile"]),
]:
    if name not in roster:
        req("POST", f"{APPT}/technician", {"name": name, "zone": zone, "workingDays": days, "startTime": start, "endTime": end, "skills": skills})
        print(f"technician: {name} ({zone})")
print("calendar: Europe/Oslo, Mon-Fri, 08/10/13/15 windows")

# ---- the brand: the Taranga logo (infra/brand, in-repo: the animated GIF is the master;
# the header uses the trimmed wave + wordmark PNG, tagline dropped at 30 px) and three banners --
docs = req("GET", f"{DOC}/document")
if not any(d.get("name") == "Taranga-logo" for d in docs):
    logo = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "infra", "brand", "taranga-logo-header.png")
    with open(logo, "rb") as fh:
        req("POST", f"{DOC}/document", {"name": "Taranga-logo", "category": "brand", "mimeType": "image/png",
                                         "content": base64.b64encode(fh.read()).decode()})
    print("brand: Taranga logo uploaded")


def banner_svg(title, sub, bg1, bg2):
    # 16:9 — the shop window shows banners in a 16:9 card (object-fit: cover), so
    # the copy sits in the left two-thirds and the wave decorates the lower right
    return f"""<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1600 900' width='1600' height='900'>
<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='{bg1}'/><stop offset='1' stop-color='{bg2}'/></linearGradient></defs>
<rect width='1600' height='900' fill='url(#g)'/>
<g fill='none' stroke='#ffffff' stroke-opacity='0.16' stroke-width='34' stroke-linecap='round'>
<path d='M760 700 C 880 560, 1000 560, 1120 700 S 1360 840, 1480 700'/>
<path d='M760 790 C 880 650, 1000 650, 1120 790 S 1360 930, 1480 790'/></g>
<text x='110' y='400' font-family='-apple-system, Helvetica Neue, Helvetica, Arial, sans-serif' font-size='96' font-weight='800' fill='#ffffff'>{title}</text>
<text x='110' y='500' font-family='-apple-system, Helvetica Neue, Helvetica, Arial, sans-serif' font-size='44' fill='#ffffff' fill-opacity='0.92'>{sub}</text>
</svg>"""


existing_banners = {d["name"]: d for d in req("GET", f"{DOC}/document?category=banner")}
boost = offerings.get("Match Day Boost", {})
fiber = offerings.get("Taranga Fiber 1000", {})
for name, caption, link, title, sub, c1, c2 in [
    ("banner-match-day-boost", "Priority when it matters — Match Day Boost, 29 kr for 6 hours", f"/offering/{boost.get('id', '')}",
     "Priority when it matters", "Match Day Boost · 6 hours on the priority 5G slice · 29 kr", "#4A4AC3", "#7B7BE0"),
    ("banner-fiber-1000", "Taranga Fiber 1000 — now in Oslo, Bergen and Trondheim", f"/offering/{fiber.get('id', '')}",
     "Fiber 1000 has arrived", "Oslo · Bergen · Trondheim · Wi-Fi 6 router included · 999 kr", "#22226B", "#4A4AC3"),
    ("banner-switch", "Switch to Taranga — keep your number, ported in a day", "/?tab=Mobile",
     "Switch and keep your number", "Ported in a day · no binding · EU/EEA roaming included", "#7B7BE0", "#4A4AC3"),
]:
    if name in existing_banners:
        continue
    req("POST", f"{DOC}/document", {"name": name, "category": "banner", "description": caption, "link": link,
                                     "mimeType": "image/svg+xml",
                                     "content": base64.b64encode(banner_svg(title, sub, c1, c2).encode()).decode()})
    print(f"banner: {name}")

print("\nTaranga tenant seeded: 4 mobile plans + priority tier, Fiber 300/1000/2500, TV + Sport + mesh, top-ups + Match Day Boost, "
      "Home + Mobile bundle, allowances, +47 41 number pool, Helthjem/Posten, Vipps + Klarna, 25% MVA, NO collections, "
      "Oslo/Bergen/Trondheim footprint + roster, wordmark + 3 banners — all NOK.")


# ---- B2B: Olav's company, so the business console has an organization to show ------------
PARTY = "/tmf-api/party/v4"
def sub_of(username, password):
    """The persona's Keycloak subject — the party id the consoles key on."""
    import base64
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo", "username": username, "password": password}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        t = json.load(r)["access_token"]
    p = t.split(".")[1]
    p += "=" * (-len(p) % 4)
    return json.loads(base64.urlsafe_b64decode(p))["sub"]


OLAV_ID = sub_of("olav@fjordbygg.example", "olav")
orgs = req("GET", f"{PARTY}/organization?limit=100", quiet=True) or []
org = next((o for o in orgs if isinstance(o, dict) and str(o.get("tradingName", "")).startswith("Fjordbygg")), None)
if not org:
    org = req("POST", f"{PARTY}/organization", {"tradingName": "Fjordbygg AS", "name": "Fjordbygg AS", "isLegalEntity": True})
    print("organization: Fjordbygg AS")
me = req("GET", f"{PARTY}/individual/{OLAV_ID}", quiet=True)
if org and not me:
    req("POST", f"{PARTY}/individual", {"id": OLAV_ID, "givenName": "Olav", "familyName": "Fjordbygg", "organization": {"id": org["id"]},
                                         "contactMedium": [{"mediumType": "email", "characteristic": {"emailAddress": "olav@fjordbygg.example"}}]})
    print("olav linked to Fjordbygg AS")
elif org and me and not (me.get("organization") or {}).get("id"):
    req("PATCH", f"{PARTY}/individual/{OLAV_ID}", {"organization": {"id": org["id"]}})
    print("olav linked to Fjordbygg AS")
