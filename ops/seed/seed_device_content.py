#!/usr/bin/env python3
"""Device product content for the INTERNAL PIM path (TMF667 document store):
every Devices offering gets a real gallery — front/back/side product shots —
plus one variant image per colour the spec offers, and the spec itself gains
descriptive, non-configurable characteristics (display, camera, battery,
weight) that the storefront renders as "About this device".

Idempotent: galleries key on document names, spec facts on characteristic
names. Nova is deliberately NOT seeded here — nova brings its own PIM (the
mock-pim container), which is the bring-your-own-PIM proof.
"""
import base64
import json
import urllib.parse
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
DOCS = "/tmf-api/documentManagement/v4/document"


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


# per-device look and facts; anything unknown gets the generic entry
DEVICES = {
    "Samsung Galaxy S26": {
        "body": "#22303c", "screen": "#101820", "accent": "#7fd1ae",
        "colors": {"Phantom Black": "#1c2321", "Cream": "#e8ddc7", "Icy Blue": "#9bc4e2"},
        "facts": {"display": '6.8" AMOLED, 120 Hz', "camera": "200 MP main + ultrawide",
                  "battery": "5,000 mAh", "weight": "218 g"},
    },
    "Apple iPhone 17 Pro": {
        "body": "#3c3744", "screen": "#14121f", "accent": "#f2c14e",
        "colors": {"Natural Titanium": "#c8c2b8", "Blue Titanium": "#5a7d9a"},
        "facts": {"display": '6.7" Super Retina XDR', "camera": "48 MP Pro system",
                  "battery": "4,700 mAh", "weight": "199 g"},
    },
    "Apple iPhone 17": {
        "body": "#44435a", "screen": "#14121f", "accent": "#f2c14e",
        "colors": {"Starlight": "#e8e1d5", "Midnight": "#232b2b", "Pink": "#e7b9c4"},
        "facts": {"display": '6.1" Super Retina XDR', "camera": "48 MP dual system",
                  "battery": "3,600 mAh", "weight": "171 g"},
    },
    "_generic": {
        "body": "#2f4550", "screen": "#0b132b", "accent": "#8fd0d0", "colors": {},
        "facts": {"display": "See manufacturer specifications",
                  "battery": "All-day battery"},
    },
}


def phone_svg(look, view, tint=None):
    body = tint or look["body"]
    screen, accent = look["screen"], look["accent"]
    views = {
        "front": f'''<rect x="62" y="28" width="116" height="244" rx="30" fill="{body}" stroke="#ffffff55" stroke-width="1.5"/>
<rect x="72" y="46" width="96" height="208" rx="18" fill="url(#scr)"/>
<circle cx="120" cy="40" r="3.5" fill="#00000066"/>
<path d="M86 50 L150 250" stroke="#ffffff33" stroke-width="11" stroke-linecap="round"/>
<rect x="178" y="92" width="3.5" height="38" rx="2" fill="#00000033"/>
<rect x="90" y="236" width="60" height="5" rx="2.5" fill="#ffffff66"/>''',
        "back": f'''<rect x="60" y="30" width="120" height="240" rx="22" fill="{body}"/>
<rect x="74" y="46" width="40" height="72" rx="14" fill="{screen}"/>
<circle cx="94" cy="66" r="10" fill="#0a0a0a" stroke="{accent}" stroke-width="2"/>
<circle cx="94" cy="96" r="10" fill="#0a0a0a" stroke="{accent}" stroke-width="2"/>
<circle cx="150" cy="60" r="4" fill="{accent}"/>''',
        "side": f'''<rect x="106" y="30" width="28" height="240" rx="12" fill="{body}"/>
<rect x="134" y="80" width="4" height="30" rx="2" fill="{accent}"/>
<rect x="134" y="120" width="4" height="18" rx="2" fill="{accent}"/>''',
    }
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="240" height="300" viewBox="0 0 240 300">
<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="#f4f7f7"/><stop offset="1" stop-color="#dde7e7"/></linearGradient>
<linearGradient id="scr" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="{screen}"/><stop offset="1" stop-color="{accent}"/></linearGradient></defs>
<rect width="240" height="300" rx="18" fill="url(#bg)"/>
{views[view]}
</svg>'''


existing_docs = {d["name"]: d for d in req("GET", DOCS)}


def upload(name, svg):
    if name in existing_docs:
        return existing_docs[name]
    doc = req("POST", DOCS, {
        "name": name, "category": "offering", "mimeType": "image/svg+xml",
        "content": base64.b64encode(svg.encode()).decode()})
    existing_docs[name] = doc
    return doc


offerings = req("GET", f"{CATALOG}/productOffering?limit=100")
devices = [o for o in offerings
           if ((o.get("category") or [{}])[0]).get("name") == "Devices"]

for o in devices:
    look = DEVICES.get(o["name"], DEVICES["_generic"])
    attachments = []
    for view in ("front", "back", "side"):
        doc = upload(f"{o['name']} — {view}", phone_svg(look, view))
        attachments.append({"name": f"gallery-{view}", "mimeType": "image/svg+xml",
                            "url": doc["attachmentUrl"], "@type": "Attachment"})
    # spec colours drive the variant shots — the picture follows the pick
    spec_id = (o.get("productSpecification") or {}).get("id")
    spec = req("GET", f"{CATALOG}/productSpecification/{spec_id}") if spec_id else None
    colors = []
    if spec:
        for ch in spec.get("productSpecCharacteristic") or []:
            if ch["name"].lower() == "color":
                colors = [v["value"] for v in ch.get("productSpecCharacteristicValue") or []]
    for color in colors:
        tint = look["colors"].get(color, look["body"])
        doc = upload(f"{o['name']} — {color}", phone_svg(look, "front", tint))
        attachments.append({"name": f"variant-{color}", "mimeType": "image/svg+xml",
                            "url": doc["attachmentUrl"], "@type": "Attachment"})
    req("PATCH", f"{CATALOG}/productOffering/{o['id']}", {"attachment": attachments})
    print(f"gallery: {o['name']} -> {len(attachments)} images")

    # descriptive facts: configurable=false keeps them OUT of the pickers and
    # IN the "About this device" table
    if spec:
        chars = spec.get("productSpecCharacteristic") or []
        have = {c["name"] for c in chars}
        added = False
        for fact, value in look["facts"].items():
            if fact in have:
                continue
            chars.append({"name": fact, "valueType": "string", "configurable": False,
                          "productSpecCharacteristicValue": [{"value": value}]})
            added = True
        if added:
            req("PATCH", f"{CATALOG}/productSpecification/{spec_id}",
                {"productSpecCharacteristic": chars})
            print(f"facts:   {spec['name']} -> {', '.join(look['facts'])}")

print("done")
