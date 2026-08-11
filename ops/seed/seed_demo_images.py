#!/usr/bin/env python3
"""Demo product imagery — a shop that LOOKS designed.

For every curated demo offering, attach a hero image:
  - DEVICES: if a real photo exists in ops/demo-assets/devices/<slug>.(png|jpg|webp)
    it is uploaded and linked (LOCAL ONLY — that folder is gitignored, so real
    manufacturer images never enter the public repo). Otherwise a device tile.
  - PLANS: a tile with the DATA ALLOWANCE as the hero number (10 GB / Unlimited),
    telecom-shop style.
  - BUNDLES / TV / BROADBAND / ADD-ONS: a clean, kind-appropriate tile.

All generated art is self-contained SVG (no copyright). Idempotent: re-run any
time; already-good images are left alone unless --force is passed.

Usage:  python3 ops/seed/seed_demo_images.py [--force]
"""
import base64
import json
import os
import re
import sys
import urllib.request

GATEWAY = "http://localhost:8080"
CATALOG = "/tmf-api/productCatalogManagement/v4"
DOCS = "/tmf-api/documentManagement/v4/document"
KC = "http://localhost:8085/realms/bss/protocol/openid-connect/token"
FORCE = "--force" in sys.argv
ASSETS = os.path.join(os.path.dirname(__file__), "..", "demo-assets", "devices")
BRAND = "#0E7C7B"   # GenAlpha teal
INK = "#0b1f2a"

# The data allowance shown as the hero on a mobile-plan tile.
PLAN_HERO = {
    "GenAlpha Mobile 10 GB": "10 GB",
    "GenAlpha Mobile 30GB 5G": "30 GB",
    "GenAlpha Mobile 50 GB": "50 GB",
    "GenAlpha Mobile 60 GB 5G": "60 GB",
    "GenAlpha Mobile Unlimited 5G": "Unlimited",
    "GenAlpha Postpaid Mobile (ID verified)": "Postpaid",
    "Kids Plan 2 GB": "2 GB",
}
DEVICE_SLUGS = {
    "Samsung Galaxy S26": "samsung-galaxy-s26",
    "Apple iPhone 17": "iphone-17",
    "Apple iPhone 17 Pro": "iphone-17-pro",
}


def token():
    data = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "bss-demo",
        "username": "demo", "password": "demo"}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=data)) as r:
        return json.load(r)["access_token"]


import urllib.parse
TOK = token()


def req(method, path, body=None):
    r = urllib.request.Request(
        GATEWAY + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {TOK}"},
        method=method)
    with urllib.request.urlopen(r) as resp:
        return json.load(resp) if resp.length != 0 else None


def upload(name, mime, data_b64):
    doc = req("POST", DOCS, {
        "name": name, "mimeType": mime,
        "content": data_b64, "@type": "Document"})
    # documentManagement returns the servable url (attachmentUrl or the doc id).
    return doc.get("attachmentUrl") or f"{DOCS}/{doc['id']}"


def link(offering, url, mime):
    try:
        req("PATCH", f"{CATALOG}/productOffering/{offering['id']}", {
            "attachment": [{"name": "hero", "mimeType": mime, "url": url, "@type": "Attachment"}]})
        return True
    except Exception as e:
        print(f"  {offering.get('name')}: SKIP ({e})")
        return False


def svg(inner, bg1, bg2):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="440" '
            f'viewBox="0 0 640 440"><defs><linearGradient id="g" x1="0" y1="0" '
            f'x2="1" y2="1"><stop offset="0" stop-color="{bg1}"/>'
            f'<stop offset="1" stop-color="{bg2}"/></linearGradient></defs>'
            f'<rect width="640" height="440" fill="url(#g)"/>{inner}</svg>')


def txt(x, y, s, size, weight=700, fill="#fff", anchor="middle", opacity=1):
    return (f'<text x="{x}" y="{y}" font-family="Inter,Segoe UI,Helvetica,Arial,'
            f'sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}" '
            f'text-anchor="{anchor}" opacity="{opacity}">{s}</text>')


def plan_tile(name, hero):
    signal = ('<g opacity="0.9">'
              '<rect x="470" y="300" width="20" height="30" rx="3" fill="#fff"/>'
              '<rect x="500" y="280" width="20" height="50" rx="3" fill="#fff"/>'
              '<rect x="530" y="255" width="20" height="75" rx="3" fill="#fff"/>'
              '<rect x="560" y="225" width="20" height="105" rx="3" fill="#fff"/></g>')
    big = 150 if len(hero) <= 5 else 92
    return svg(
        txt(50, 90, "GenAlpha", 30, 800, "#fff", "start", 0.85)
        + txt(50, 250, hero, big, 800, "#fff", "start")
        + txt(52, 300, "per month, unlimited calls &amp; texts", 26, 500, "#fff", "start", 0.85)
        + signal, BRAND, "#0a5c5b")


def device_tile(name):
    # A clean, GENERIC front-facing phone render (no logos/trade dress — repo-safe),
    # tinted per device so the shop has visual variety.
    n = name.lower()
    if "samsung" in n:
        body, s1, s2, bg1, bg2 = "#0b1524", "#4b2fd6", "#8a2be2", "#0a1120", "#111a2e"
    elif "pro" in n:
        body, s1, s2, bg1, bg2 = "#26262b", "#0e7c7b", "#1c3b4c", "#15161a", "#0c0d10"
    else:  # iPhone 17
        body, s1, s2, bg1, bg2 = "#1a2733", "#3aa0ff", "#0e7c7b", "#0d1620", "#0a1018"
    inner = (
        f'<defs><linearGradient id="scr" x1="0" y1="0" x2="1" y2="1">'
        f'<stop offset="0" stop-color="{s1}"/><stop offset="1" stop-color="{s2}"/>'
        f'</linearGradient></defs>'
        # body + screen + front camera + a soft reflection + side button
        f'<rect x="250" y="50" width="140" height="300" rx="32" fill="{body}" '
        f'stroke="#ffffff26" stroke-width="2"/>'
        f'<rect x="262" y="64" width="116" height="272" rx="20" fill="url(#scr)"/>'
        f'<circle cx="320" cy="78" r="4.5" fill="#00000066"/>'
        f'<path d="M276 78 L344 320" stroke="#ffffff2e" stroke-width="12" stroke-linecap="round"/>'
        f'<rect x="392" y="118" width="4" height="46" rx="2" fill="#ffffff33"/>'
        + txt(320, 400, name, 30, 700, "#fff"))
    return svg(inner, bg1, bg2)


def simple_tile(name, glyph, bg):
    glyphs = {
        "bundle": '<path d="M255 210h130M285 170a35 35 0 0 1 70 0" stroke="#fff" stroke-width="10" fill="none"/><circle cx="320" cy="210" r="11" fill="#fff"/>',
        "fiber": '<path d="M210 300l60-90 40 46 60-104" stroke="#fff" stroke-width="12" fill="none" stroke-linecap="round"/>',
        "tv": '<rect x="240" y="150" width="160" height="104" rx="12" fill="none" stroke="#fff" stroke-width="9"/><path d="M290 288h60" stroke="#fff" stroke-width="9"/>',
        "addon": '<circle cx="320" cy="205" r="60" fill="none" stroke="#fff" stroke-width="9"/><path d="M320 175v60M290 205h60" stroke="#fff" stroke-width="9"/>',
    }.get(glyph, "")
    return svg(glyphs + txt(320, 360, name, 30, 700, "#fff"), bg, INK)


def local_device(name):
    slug = DEVICE_SLUGS.get(name)
    if not slug or not os.path.isdir(ASSETS):
        return None
    for ext, mime in (("png", "image/png"), ("jpg", "image/jpeg"),
                      ("jpeg", "image/jpeg"), ("webp", "image/webp")):
        p = os.path.join(ASSETS, f"{slug}.{ext}")
        if os.path.isfile(p):
            with open(p, "rb") as f:
                return base64.b64encode(f.read()).decode(), mime, p
    return None


def kind(name, cats):
    cat = cats[0] if cats else ""
    if name in PLAN_HERO:
        return "plan"
    if cat == "Devices":
        return "device"
    if cat == "Bundles":
        return "bundle"
    if cat == "Broadband":
        return "fiber"
    if cat == "TV & Add-ons":
        return "tv"
    return "addon"


offs = req("GET", f"{CATALOG}/productOffering?limit=100")
active = [o for o in offs if (o.get("lifecycleStatus") or "").lower() == "active"]
did = 0
for o in active:
    name = o.get("name") or ""
    has_img = bool((o.get("attachment") or [{}])[0].get("url"))
    cats = [c.get("name") for c in (o.get("category") or [])]
    k = kind(name, cats)
    # real device photo (local only) always wins for devices
    real = local_device(name) if k == "device" else None
    if real:
        b64, mime, path = real
        link(o, upload(f"photo-{name}", mime, b64), mime)
        print(f"  {name}: real photo <- {os.path.basename(path)}")
        did += 1
        continue
    if k == "device":
        # devices keep their per-color GALLERY from seed_device_content.py (the
        # configurator hero follows the colour pick) — don't clobber it with a
        # single tile. Only a real local photo (handled above) overrides it.
        continue
    if has_img and not FORCE:
        continue
    if k == "plan":
        art = plan_tile(name, PLAN_HERO[name])
    elif k == "device":
        art = device_tile(name.replace("Apple ", ""))
    elif k in ("bundle", "fiber", "tv"):
        art = simple_tile(name, k, BRAND if k != "fiber" else "#134e4a")
    else:
        art = simple_tile(name, "addon", "#2a4a52")
    b64 = base64.b64encode(art.encode()).decode()
    link(o, upload(f"tile-{name}", "image/svg+xml", b64), "image/svg+xml")
    print(f"  {name}: {k} tile")
    did += 1

print(f"\ndone — imagery attached to {did} offering(s). "
      f"Drop real device photos in ops/demo-assets/devices/ (gitignored) and re-run.")
