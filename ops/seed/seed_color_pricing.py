#!/usr/bin/env python3
"""Colour-conditioned pricing demo (TMF620 prodSpecCharValueUse): the Samsung
Galaxy S26 gains a 'Titanium Edition' colour that costs +2.00 EUR/month — one
offering, one spec, no SKU per colour. The premium is a price component
conditioned on color=Titanium Edition; the variant photo rides along so the
picture AND the price follow the pick. Idempotent."""
import base64
import json
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
DOCS = "/tmf-api/documentManagement/v4/document"

COLOR = "Titanium Edition"
PREMIUM = 2.00
TINT = "#9a9da1"


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    url = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


TOK = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


samsung = req("GET", f"{CATALOG}/productOffering?name={urllib.parse.quote('Samsung Galaxy S26')}")[0]
spec_id = samsung["productSpecification"]["id"]
spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}")

# 1. the colour on the spec
chars = spec.get("productSpecCharacteristic") or []
for ch in chars:
    if ch["name"].lower() == "color":
        values = ch.setdefault("productSpecCharacteristicValue", [])
        if not any(v.get("value") == COLOR for v in values):
            values.append({"value": COLOR})
            req("PATCH", f"{CATALOG}/productSpecification/{spec_id}",
                {"productSpecCharacteristic": chars})
            print(f"spec: color += {COLOR}")

# 2. the variant photo (same look as seed_device_content's Samsung, titanium tint)
PHONE_SVG = f'''<svg xmlns="http://www.w3.org/2000/svg" width="240" height="300" viewBox="0 0 240 300">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#f4f7f7"/><stop offset="1" stop-color="#dde7e7"/></linearGradient></defs>
<rect width="240" height="300" rx="18" fill="url(#bg)"/>
<rect x="60" y="30" width="120" height="240" rx="22" fill="{TINT}"/>
<rect x="70" y="52" width="100" height="196" rx="8" fill="#101820"/>
<circle cx="120" cy="42" r="4" fill="#101820"/>
<rect x="82" y="70" width="76" height="8" rx="4" fill="#7fd1ae" opacity="0.9"/>
<rect x="82" y="90" width="52" height="6" rx="3" fill="#ffffff" opacity="0.35"/>
<circle cx="120" cy="230" r="12" fill="none" stroke="#7fd1ae" stroke-width="3"/>
</svg>'''
doc_name = f"Samsung Galaxy S26 — {COLOR}"
docs = {d["name"]: d for d in req("GET", DOCS)}
doc = docs.get(doc_name) or req("POST", DOCS, {
    "name": doc_name, "category": "offering", "mimeType": "image/svg+xml",
    "content": base64.b64encode(PHONE_SVG.encode()).decode()})
attachments = samsung.get("attachment") or []
if not any(a.get("name") == f"variant-{COLOR}" for a in attachments):
    attachments.append({"name": f"variant-{COLOR}", "mimeType": "image/svg+xml",
                        "url": doc["attachmentUrl"], "@type": "Attachment"})
    req("PATCH", f"{CATALOG}/productOffering/{samsung['id']}", {"attachment": attachments})
    print(f"art:  variant-{COLOR}")

# 3. the conditioned price component
prices = req("GET", f"{CATALOG}/productOfferingPrice?limit=100")
premium_name = f"{COLOR} premium"
premium = next((p for p in prices if p["name"] == premium_name), None)
if premium is None:
    premium = req("POST", f"{CATALOG}/productOfferingPrice", {
        "name": premium_name,
        "priceType": "recurring",
        "recurringChargePeriodType": "month",
        "price": {"unit": "EUR", "value": PREMIUM},
        "prodSpecCharValueUse": [{
            "name": "color",
            "productSpecCharacteristicValue": [{"value": COLOR}],
        }],
        "lifecycleStatus": "Active",
    })
    print(f"price: {premium_name} +{PREMIUM:.2f} EUR/month when color={COLOR}")

refs = samsung.get("productOfferingPrice") or []
if not any(r.get("id") == premium["id"] for r in refs):
    refs.append({"id": premium["id"], "name": premium_name,
                 "@referredType": "ProductOfferingPrice"})
    req("PATCH", f"{CATALOG}/productOffering/{samsung['id']}", {"productOfferingPrice": refs})
    print("offering: premium linked")

print("done")
