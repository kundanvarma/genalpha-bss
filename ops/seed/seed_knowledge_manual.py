#!/usr/bin/env python3
"""The BSS explains itself: the Operator's Manual, loaded into the knowledge base.

Every chapter section of docs/manual/manual.html becomes one published knowledge
article (audience: staff), tagged `manual`, `chapter:<id>` and — where a section
belongs to a console page — `pane:<path>`, so the ? drawer's shelf, the free
search and the metered Ask all draw on the same text the manual's readers do.
"How do I use the simulator?" is then answered from the manual's own words, with
the section named as the source.

Usage: seed_knowledge_manual.py [tenant]   (default taranga; enet, genalpha/bss work too)
Idempotent by title: a section with the same title is updated, not duplicated;
manual articles whose section no longer exists are removed.
"""
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

TENANT = (sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TENANT", "taranga")).lower()
REALM = {"genalpha": "bss"}.get(TENANT, TENANT)
API = os.environ.get("API", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK", f"http://localhost:8085/realms/{REALM}/protocol/openid-connect/token")
KB = "/tmf-api/knowledgeManagement/v4"
USER, PASS = os.environ.get("SEED_USER", "demo"), os.environ.get("SEED_PASS", "demo")
MANUAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "manual", "manual.html")
BODY_MAX = 7600  # article.body is VARCHAR(8000)

# Which console pages a CHAPTER speaks for (tag pane:<path> = the ? shelf of that page).
CHAPTER_PANES = {
    "product": ["productOffering", "productSpecification", "productOfferingPrice", "approvals", "envelopes"],
    "csr": [],
    "billing": ["customerBill", "billDistribution", "billFormatProfile", "remittance/unapplied", "dispute"],
    "partners": ["salesLead", "salesOpportunity", "salesPipeline"],
    "host": ["operator", "settings", "integrations", "staff", "myOperator"],
    "ai": ["copilot", "aiflows", "growthCopilot", "decisions", "learning-contracts", "audit"],
    "workforce": ["workforce"],
    "revenue": ["journalEntry", "reporting"],
    "creditnote": ["customerBill"],
    "process": ["processFlow", "runbook"],
    "configurator": ["productSpecification", "configRule"],
    "servicequal": ["coverageMap", "serviceableArea"],
    "aimgmt": ["aiflows"],
    "risk": ["partyRiskAssessment", "scoringRule"],
    "wholesale": ["wholesaleOwners", "accessProduct", "wholesaleSettlement"],
    "wholesaleconsole": ["wholesaleOwners", "accessProduct", "wholesaleSettlement"],
    "mobilewholesale": ["mobileWholesale", "mobileWholesaleProvider"],
    "collections": ["dunning"],
    "devicecommerce": ["productStock"],
    "basemigration": ["accountMapping", "findings"],
    "usagepolicy": ["quota"],
    "entitlement": ["device-entitlements"],
    "loyalty": ["promotion"],
}
# Section titles that name a page regardless of chapter (first keyword match wins, all listed panes apply).
SECTION_PANES = [
    (r"simulat", ["simulate/priceChange", "simulate/prospect"]),
    (r"shadow", ["shadowDrift", "simulate/priceChange"]),
    (r"voice of|voc\b", ["voc"]),
    (r"social", ["socialCare", "socialListening"]),
    (r"journey", ["journey", "campaign"]),
    (r"campaign", ["campaign"]),
    (r"audience", ["audience", "audienceBuilder"]),
    (r"porting", ["numberPortingOrder"]),
    (r"appointment|installation|installer", ["appointment"]),
    (r"dispute", ["dispute"]),
    (r"attribution|lift", ["attribution"]),
    (r"runbook|incident", ["runbook"]),
    (r"quote|lead|opportunit|pipeline", ["salesLead", "salesOpportunity", "salesPipeline"]),
    (r"pricing rule|policy|rules", ["policyRule", "pricingRule"]),
    (r"routing", ["routingRule"]),
    (r"desk|suggestion", ["desk-suggestions"]),
    (r"guided", ["guidedQuestion", "guidedRecommendation"]),
    (r"landing", ["landing"]),
    (r"decision", ["decisions"]),
    (r"learning contract", ["learning-contracts"]),
    (r"knowledge|help", ["article"]),
    (r"stock|device", ["productStock"]),
    (r"order", ["productOrder"]),
]
CSR_CHAPTERS = {"csr"}


def token():
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo", "username": USER, "password": PASS}).encode()
    with urllib.request.urlopen(urllib.request.Request(KEYCLOAK, data=data)) as r:
        return json.load(r)["access_token"]


TOKEN = token()


def req(method, path, body=None):
    r = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        print(f"  ! {method} {path} -> {e.code} {e.read()[:160]!r}")
        return None


class Manual(HTMLParser):
    """Walks manual.html into (chapter id, chapter title, section title, text) tuples."""
    BLOCK = {"p", "div", "li", "tr", "pre", "h4", "h5", "blockquote", "table", "ul", "ol"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.chapter = None
        self.chapter_title = ""
        self.section = "Overview"
        self.buf = []
        self.out = []
        self.in_heading = None
        self.heading_text = []
        self.skip = 0

    def flush(self):
        text = "".join(self.buf)
        text = re.sub(r"[ \t]+", " ", text)
        text = re.sub(r" *\n *", "\n", text)
        text = re.sub(r"\n{3,}", "\n\n", text).strip()
        # the HTML source wraps at ~90 columns: a lone newline inside a paragraph is a space,
        # a blank line, a bullet or a table row keeps its break
        text = re.sub(r"(?<!\n)\n(?!\n|• | \|)", " ", text)
        if self.chapter and self.chapter != "toc" and text:
            self.out.append((self.chapter, self.chapter_title, self.section, text))
        self.buf = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in ("nav", "script", "style"):
            self.skip += 1
            return
        if tag == "h2":
            self.flush()
            self.chapter = a.get("id") or "misc"
            self.section = "Overview"
            self.in_heading = "h2"
            self.heading_text = []
        elif tag == "h3":
            self.flush()
            self.in_heading = "h3"
            self.heading_text = []
        elif tag == "li":
            self.buf.append("\n• ")
        elif tag in ("td", "th"):
            self.buf.append(" | ")
        elif tag == "br" or tag in self.BLOCK:
            self.buf.append("\n")

    def handle_endtag(self, tag):
        if tag in ("nav", "script", "style"):
            self.skip = max(0, self.skip - 1)
            return
        if tag == "h2" and self.in_heading == "h2":
            self.chapter_title = re.sub(r"\s+", " ", "".join(self.heading_text)).strip()
            self.in_heading = None
        elif tag == "h3" and self.in_heading == "h3":
            self.section = re.sub(r"\s+", " ", "".join(self.heading_text)).strip() or "Overview"
            self.in_heading = None
        elif tag in self.BLOCK:
            self.buf.append("\n")

    def handle_data(self, data):
        if self.skip:
            return
        if self.in_heading:
            self.heading_text.append(data)
        else:
            self.buf.append(data)


with open(MANUAL, encoding="utf-8") as f:
    parser = Manual()
    parser.feed(f.read())
    parser.flush()


def chunks(text):
    """Split a long section at paragraph boundaries so every part fits the column."""
    if len(text) <= BODY_MAX:
        return [text]
    parts, cur = [], ""
    for para in text.split("\n\n"):
        while len(para) > BODY_MAX:  # a single giant paragraph: hard cut
            parts.append((cur + "\n\n" + para[:BODY_MAX]).strip()); cur = ""; para = para[BODY_MAX:]
        if len(cur) + len(para) + 2 > BODY_MAX:
            parts.append(cur.strip()); cur = para
        else:
            cur = (cur + "\n\n" + para) if cur else para
    if cur.strip():
        parts.append(cur.strip())
    return parts


def panes_for(chapter, section):
    panes = list(CHAPTER_PANES.get(chapter, []))
    low = section.lower()
    for pattern, ps in SECTION_PANES:
        if re.search(pattern, low):
            panes += ps
            break
    return sorted(set(panes))


existing = {a["title"]: a for a in (req("GET", f"{KB}/article?limit=2000") or [])}
stale = {t for t, a in existing.items() if "manual" in [x.strip() for x in (a.get("tags") or "").split(",")]}
made = updated = 0
for chapter, chapter_title, section, text in parser.out:
    if not chapter_title:
        continue
    parts = chunks(text)
    for i, body in enumerate(parts):
        title = f"Manual · {chapter_title} — {section}" + (f" (part {i + 1})" if len(parts) > 1 else "")
        title = title[:250]
        audience = "csr" if chapter in CSR_CHAPTERS else "productOwner"
        tags = ["manual", f"chapter:{chapter}"] + [f"pane:{p}" for p in panes_for(chapter, section)]
        dto = {"title": title, "audience": audience, "tags": ", ".join(tags), "category": "Operator's Manual",
               "body": body, "status": "published"}
        stale.discard(title)
        if title in existing:
            req("PATCH", f"{KB}/article/{existing[title]['id']}", dto); updated += 1
        else:
            req("POST", f"{KB}/article", dto); made += 1
removed = 0
for title in stale:
    if req("DELETE", f"{KB}/article/{existing[title]['id']}") is None:
        removed += 1  # DELETE returns no body; count the attempt
print(f"{TENANT}: manual loaded — {made} sections created, {updated} updated, {len(stale)} stale removed ({len(parser.out)} sections read)")
