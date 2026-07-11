#!/usr/bin/env python3
"""Seed channel content (TMF667): tenant logos and offering artwork —
generated SVGs, no external assets. Idempotent by document name."""
import base64
import json
import urllib.request
import urllib.parse

GATEWAY = "http://localhost:8080"


def token(realm):
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo",
    }).encode()
    url = f"http://localhost:8085/realms/{realm}/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=data)) as r:
        return json.load(r)["access_token"]


def req(tok, method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp)


def logo_svg(text, color):
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="320" height="80" viewBox="0 0 320 80">
<rect width="320" height="80" rx="14" fill="{color}"/>
<circle cx="46" cy="40" r="20" fill="none" stroke="#fff" stroke-width="5"/>
<circle cx="46" cy="40" r="7" fill="#fff"/>
<text x="84" y="52" font-family="Helvetica,Arial" font-size="30" font-weight="700" fill="#fff">{text}</text>
</svg>"""


def offer_svg(kind, color):
    icons = {
        "bundle": '<path d="M55 95h90M70 65a30 30 0 0 1 60 0" stroke="#fff" stroke-width="7" fill="none"/><circle cx="100" cy="95" r="9" fill="#fff"/>',
        "phone": '<rect x="72" y="38" width="56" height="98" rx="10" fill="none" stroke="#fff" stroke-width="7"/><circle cx="100" cy="118" r="6" fill="#fff"/>',
        "plan": '<path d="M58 110c10-38 74-38 84 0M74 96c8-22 44-22 52 0M90 84c4-9 16-9 20 0" stroke="#fff" stroke-width="7" fill="none"/><circle cx="100" cy="114" r="7" fill="#fff"/>',
        "fiber": '<path d="M48 128l40-56 26 30 38-64" stroke="#fff" stroke-width="8" fill="none" stroke-linecap="round"/>',
        "tv": '<rect x="50" y="48" width="100" height="64" rx="8" fill="none" stroke="#fff" stroke-width="7"/><path d="M80 128h40" stroke="#fff" stroke-width="7"/>',
    }
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="200" height="160" viewBox="0 0 200 160">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0" stop-color="{color}"/><stop offset="1" stop-color="#1a3c3c"/></linearGradient></defs>
<rect width="200" height="160" rx="16" fill="url(#g)"/>{icons[kind]}
</svg>"""


def kind_for(name):
    n = name.lower()
    if "home" in n or "bundle" in n or "one" in n:
        return "bundle"
    if "iphone" in n or "galaxy" in n or "phone" in n:
        return "phone"
    if "fiber" in n:
        return "fiber"
    if "tv" in n:
        return "tv"
    return "plan"


for realm, tenant_color, brand in [("bss", "#0E7C7B", "GenAlpha"), ("nova", "#5B3FA8", "Nova")]:
    tok = token(realm)
    existing = {d["name"] for d in req(tok, "GET", "/tmf-api/documentManagement/v4/document")}

    def upload(name, category, svg):
        if name in existing:
            return next(d for d in req(tok, "GET", "/tmf-api/documentManagement/v4/document")
                        if d["name"] == name)
        return req(tok, "POST", "/tmf-api/documentManagement/v4/document", {
            "name": name, "category": category, "mimeType": "image/svg+xml",
            "content": base64.b64encode(svg.encode()).decode()})

    logo = upload(f"{brand}-logo", "brand", logo_svg(brand, tenant_color))
    print(f"{realm}: logo -> {logo['attachmentUrl']}")

    offerings = req(tok, "GET", "/tmf-api/productCatalogManagement/v4/productOffering?limit=100")
    for o in offerings:
        if o.get("attachment"):
            continue
        art = upload(f"art-{o['name']}", "offering", offer_svg(kind_for(o["name"]), tenant_color))
        req(tok, "PATCH", f"/tmf-api/productCatalogManagement/v4/productOffering/{o['id']}", {
            "attachment": [{"name": "hero", "mimeType": "image/svg+xml",
                            "url": art["attachmentUrl"], "@type": "Attachment"}]})
        print(f"{realm}: art -> {o['name']}")
print("done")
