#!/usr/bin/env python3
"""Contextual help for every screen of a tenant, as knowledge articles.

Each article is tagged with the screen it belongs to (pane:<console tab>,
csr:<desk page>, shop:<shop page>) and an AUDIENCE. The knowledge service gates
by audience from the caller's token, so a customer only ever sees customer
articles, a CSR the CSR shelf, a product owner the how-tos — the tag only says
WHERE the article shows up, the audience says WHO may read it.

Usage: seed_knowledge_help.py [tenant]   (default taranga; enet, genalpha/bss work too)
Idempotent by title: an article with the same title is updated, not duplicated.
"""
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
KB = "/tmf-api/knowledgeManagement/v4"
BRAND = {"taranga": "Taranga", "enet": "ENet", "genalpha": "GenAlpha", "bss": "GenAlpha", "nova": "Nova"}.get(TENANT, TENANT.title())
CUR = {"taranga": "NOK", "enet": "G$", "genalpha": "EUR", "bss": "EUR"}.get(TENANT, "EUR")
USER, PASS = os.environ.get("SEED_USER", "demo"), os.environ.get("SEED_PASS", "demo")


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


existing = {a["title"]: a for a in (req("GET", f"{KB}/article?limit=500") or [])}
made = updated = 0


def article(title, audience, tags, category, body):
    global made, updated
    body = body.strip().replace("{brand}", BRAND).replace("{cur}", CUR)
    dto = {"title": title, "audience": audience, "tags": ", ".join(tags), "category": category, "body": body, "status": "published"}
    if title in existing:
        req("PATCH", f"{KB}/article/{existing[title]['id']}", dto)
        updated += 1
    else:
        req("POST", f"{KB}/article", dto)
        made += 1


# =====================================================================================
# PRODUCT OWNERS — the console (audience productOwner: catalog/marketing/finance staff)
# =====================================================================================
PO = "productOwner"
article("How to create a product offering", PO, ["pane:productOffering", "catalog", "how-to"], "Catalog how-to", """
1. Catalog & Pricing › Product Offerings › New.
2. Name and description are what the shop shows. Pick a Category — it drives placement and fulfilment (Mobile plans, Broadband, Top-ups…).
3. Link a Specification (the facts: Data, Validity, Network…) and one or more Prices.
4. Channels: tick where this offer is sold. Nothing ticked = every channel, including AI shopping agents. A store-only pack is invisible and unorderable from the web shop.
5. Available from / until: the shelf obeys these dates by itself; launch day fires the launch journeys.
Lifecycle: In study → In design → In test → Active (launched) → Retired. With launch governance on, saving as Active does not launch — request the launch (Approvals tab).
""")
article("Channels: where an offer is sold", PO, ["pane:productOffering", "channels"], "Catalog how-to", """
Registered channels: Web shop, Mobile app, Store / dealer, Telesales, Care (assisted), Business console, Partner portal, AI agents via ACP, via MCP, via A2A.
• The list on an offering is enforced by the server: a customer in the web shop cannot see or order a store-only offer.
• Empty list = every channel. Untick the AI agent channels to keep an offer out of agentic shopping.
• Unknown channel ids are refused at save time — pick from the checkboxes, never type.
""")
article("Requesting a launch", PO, ["pane:productOffering", "pane:approvals", "governance"], "Launch governance", """
With launch governance on (this tenant runs it), a saved offer is a DRAFT until it is launched.
1. Approvals tab › Drafts › Request launch (or ask the copilot to "make it real").
2. If the offer is inside a pre-approved envelope, it is approved at once and the trail names the envelope.
3. Otherwise it waits for an approver (commercial / finance). You will see "Waiting for approval" with your note.
4. Readiness owners tick their items (marketing collateral, care briefed, billing checked). Launch now once all are ticked; an approver may launch past open items.
What voids an approval: changing the price, allowance, category, terms, channels or bundle after approval. Dates and copy are free to edit.
""")
article("Approving, rejecting and holding a launch", PO, ["pane:approvals", "governance"], "Launch governance", """
Approve / Reject need the approver role (catalog:approve) — commercial and finance, never the product owner of the offer.
• Approve: good for 60 days; not launched until someone presses Launch now (or the date arrives).
• Reject: state the reason; the requester sees it on the card.
• Hold: anyone may pull the brake ("marketing is not ready"). A dated hold moves the on-sale date and lifts by itself; an open-ended hold withdraws a live offer until Resume.
• Launch on a date: sets Available-from; the shelf and the launch journeys follow the date.
• Unlaunch: sets a sales end date; the trail keeps everything.
The Trail under each card is the audit record; the same steps are mirrored as a TMF701 process flow.
""")
article("Envelopes: pre-approved launch shapes", PO, ["pane:envelopes", "governance"], "Launch governance", """
An envelope is a launch shape the business has already approved: category, price band, allowance, validity, channels, and whether zero-rated apps or bundles are allowed.
• A draft inside an envelope launches without a human; the trail names the envelope.
• Author with the pickers only; the sentence under the form is exactly what the rule means. The preview lists which current drafts the envelope would let through.
• Draft from a sentence: type "top-ups under 199, 30 days or less, app and web only" and adjust.
• Turn off an envelope to make every such launch ask again. Clone to make a variant.
Envelopes are policy rules (domain "launch") — they also appear under Platform › Rules.
""")
article("Decisions: reading a receipt", PO, ["pane:decisions", "ai", "governance"], "Learning how-to", """
Every adaptive choice the BSS makes is one row here: who it was about, what it could choose, what it chose, by which policy and version, with what probability, and what followed.
• Filter by decision point (journey enrolment, campaign treatment, next-best-action, the tuner, advisor proposals, desk suggestions) or by a subject id — a customer, a journey, an offering.
• Open a row for the receipt: five sentences an auditor or a customer can read. The details underneath hold the exact context, eligible actions, constraints and evidence.
• "Propensity" is the probability the policy chose that action. Deterministic rules have none. A row marked fallback means the policy did not answer and the contract's fallback did.
• An outcome (a conversion, an adoption, an accept) joins the row later by id. No outcome yet is normal for young decisions.
Names, addresses and message text are never here — identifiers and numbers only.
""")
article("Learning contracts: writing the intent for a decision point", PO, ["pane:learning-contracts", "ai", "governance"], "Learning how-to", """
A learning contract is the written intent for one decision point, as configuration the seam reads on every decision.
• Objective: the outcome the point is optimised for (conversion for enrolments and treatments).
• Guardrails: the hard rules in words — consent, statute, brand. They are what the constraints enforce; the receipt names them.
• Allowed actions: a candidate outside the list is removed before the policy looks. Empty = every candidate.
• Exploration cap: the most customers a holdout may leave silent. A journey asking for more is capped and the receipt says so.
• Autonomy: high for reversible message choices, medium for recommendations and traffic shifts, low for money, rights or statute.
• Fallback and pause: switch the policy off and the fallback answers every decision — the emergency brake.
Every save is a new version; old receipts keep citing the version they ran under. Use Dry run to see what the point would decide before you save.
""")
article("What the product copilot can and cannot do", PO, ["pane:copilot", "ai"], "Catalog how-to", """
The copilot proposes; you decide. Describe the product ("a social pack with WhatsApp free, 30 days, app and web, from next Monday"). It returns a full proposal: spec, prices, offering, launch date, channels, zero-rated apps, pricing rules.
• Make it real creates the rows and then asks for a launch like anyone else. The verdict is shown: "launches by itself — inside envelope …" or "needs an approver".
• Prices are always positive; discounts are pricing rules. The card lists anything it repaired or dropped.
• Every call is metered under the AI budget (AI & Automation › AI Audit).
""")
article("Specifications and characteristics", PO, ["pane:productSpecification", "catalog"], "Catalog how-to", """
A specification holds the facts the shop shows and the systems read: Data ("20 GB", "Unlimited"), Validity ("30 days"), Network, Calls & texts.
Special characteristics the platform acts on:
• chargingSpecId — the OCS rate plan provisioned at activation.
• sliceProfile / boostHours / guaranteedDlMbps — priority 5G slice (tier or timed boost pass).
• zeroRatedApps — "WhatsApp, Instagram, TikTok": passed to the OCS at activation; those apps never count against the allowance; the shop shows them.
• Configurable characteristics (colour, storage) become pickers in the shop.
""")
article("Prices and pricing rules", PO, ["pane:productOfferingPrice", "catalog"], "Catalog how-to", """
• Recurring (per month) or one-time. Currency is the tenant's ({cur}).
• A price with a prodSpecCharValueUse applies only to a configured characteristic (e.g. 256 GB storage).
• Discounts, bundles-of-two, consumer-only offers are PRICING RULES (Platform › Rules, domain pricing), applied in the cart and the bill run.
• Tax rides on the price (per-price tax); the shop shows the tenant's VAT note.
""")
article("Writing help articles", PO, ["pane:article", "knowledge"], "Catalog how-to", """
Help articles are the shelf behind the ? on every screen — this tab is where they are written.
• Title and body: plain words, numbered steps. One screen or task per article.
• Who is this for: the audience gate. Customers only ever see "customer" and "all"; CSRs the CSR shelf; product owners the how-tos. A customer can never read a product how-to, whatever the tag says.
• Search tags: where the article appears — pane:<console tab> (pane:approvals, pane:envelopes, pane:decisions, pane:learning-contracts), csr:<desk page> (csr:tickets), shop:<shop page> (shop:bills). Several tags = several screens.
• Status: only published articles show. Drafts stay here.
• Unanswered questions at the top of this tab are what people asked that no article answered — Write it prefills a new article; once published, Ask is no longer needed for that question.
""")
article("Journeys and campaigns", PO, ["pane:journeys", "pane:campaigns", "marketing"], "Marketing how-to", """
• A campaign is the umbrella; a journey is the sequence (welcome, winback, running-low, launch day).
• Journeys start on business events (order placed, usage threshold, offer launched) or on an audience.
• Holdout keeps a control group so lift is measurable; auto-tuning shifts traffic between message arms only on evidence.
• Channels: email, SMS, in-app, WhatsApp (where the tenant has it). Quiet hours are per tenant.
""")
article("Audiences and consent", PO, ["pane:audience-builder", "pane:audiences", "marketing"], "Marketing how-to", """
Audiences are built from traits the event bus fills (plan, usage, tenure, sentiment). Marketing consent is enforced at send time, not at build time. Prospects (imported) need documented consent before reach. Activation to Meta/Google sends hashed identifiers only.
""")
article("Device entitlements: what a phone may use", PO, ["pane:device-entitlements", "operations", "network"], "Operations how-to", """
Phones ask this server what their subscription includes — Wi-Fi calling, VoLTE, an eSIM for the watch. The plan decides; the SIM proves itself.
• Lines: one row per mobile line the server knows. Open a line to see what its phone may use, in words: on, off, being set up, "on — emergency address still needed", allowed or not on this plan.
• Phones that checked in: the model, when it was last seen, and whether it registered for push. A phone without push cannot be nudged — it will only learn of a change the next time it asks.
• Watches and tablets: companion eSIMs that share the line, with their status.
• Recent requests from phones: what each phone asked for and how the server answered. "SIM challenge sent" is normal — the phone proves it holds the SIM before it is told anything. "Refused" means the network knows the SIM but the BSS has not bound it to a line — a provisioning gap to close, not a phone fault.
• Ask the phone to refresh: after a plan change, tell the phones on the line to fetch their entitlements again instead of waiting for the next check-in.
""")

# =====================================================================================
# CSRs — the agent desk (audience csr)
# =====================================================================================
CSR = "csr"
article("Finding a customer and reading the 360", CSR, ["csr:customers", "desk"], "Agent desk", """
Search by name, phone, email or party id. The 360 shows subscriptions, orders, bills, tickets, interactions and the AI summary (with ai:use).
• Verify identity before changing anything: name + one of phone / date of birth / last bill amount.
• "Act as" opens the shop as the customer for assisted sales — orders are tagged to the care channel.
""")
article("Tickets: raise, escalate, close", CSR, ["csr:tickets", "desk"], "Agent desk", """
• Raise with the right category; network-area problems auto-link to the outage banner.
• Escalate sets priority and notifies the second line; the SLA clock is on the ticket.
• Close only with a resolution note — it feeds the knowledge gaps and the Voice of Customer.
""")
article("Care chat: what the assistant may do", CSR, ["csr:chats", "desk"], "Agent desk", """
The care chat answers from the knowledge base and the customer's own data, with sources. It escalates to you honestly when it cannot answer or the customer asks for a human. You see the full transcript. Never paste customer identifiers into an external tool.
""")
article("Stock and devices", CSR, ["csr:stock", "csr:devices", "desk"], "Agent desk", """
Stock shows on-hand per store; a reservation is made at order time and released on cancel. Devices: IMEI, financing status, warranty, trade-in. A SIM swap needs identity verification and is logged.
""")
article("Collections and payment plans", CSR, ["csr:collections", "desk"], "Agent desk", """
The dunning ladder follows the tenant's law (reminder, fee, suspension, disconnection). A payment plan pauses the ladder. Disputes stop dunning on the disputed lines only.
""")
article("Using the knowledge base and Ask", CSR, ["csr:knowledge", "desk"], "Agent desk", """
Search first — it is free and instant. Ask (✨) reads the same articles and writes an answer with sources; it costs AI budget, so use it when search does not settle it. Answers are cached until an article changes. If Ask says it found nothing, the question is recorded for the content team.
""")

# =====================================================================================
# CUSTOMERS — the shop (audience customer) — nothing about how the product is built
# =====================================================================================
C = "customer"
article("Choosing a plan", C, ["shop:offers", "faq"], "Getting started", """
Compare plans by data or price. "Unlimited" means full speed all month; a data cap slows you down after it, it never charges you more. Free apps listed under a plan (for example WhatsApp) never count against your data.
""")
article("Priority network and boost passes", C, ["shop:offers", "shop:services", "faq"], "Mobile", """
A priority plan keeps your line on the priority 5G lane all month. A boost pass gives you the priority lane for a few hours — it turns on the moment you buy it and switches itself off after. You can see it on My page while it runs.
""")
article("Checkout, delivery and pickup", C, ["shop:cart", "faq"], "Orders", """
Pay by card, Vipps or Klarna where offered. Physical SIMs and devices ship with tracking; store pickup is ready the same day in most stores. eSIM is activated online — scan the QR from My page.
""")
article("Tracking an order", C, ["shop:orders", "faq"], "Orders", """
My orders shows every step: placed, in progress, shipped (with the carrier's tracking), delivered, active. A physical SIM goes live when the parcel is delivered. Cancel is possible until the order is shipped.
""")
article("Understanding your bill", C, ["shop:bills", "faq"], "Billing", """
Your bill lists the monthly plan, one-time charges (passes, devices), usage above the plan and taxes. Dispute a line from the bill itself — dunning stops on that line while we look at it. Pay from the bill page or set up automatic payment under Account.
""")
article("Data usage, running low and top-ups", C, ["shop:services", "faq"], "Mobile", """
My page shows what is left this month. We warn you at 80 percent. A top-up adds data at once; rollover keeps what you did not use (on plans that include it). Free apps on your plan never count.
""")
article("Family: adding a member and managing their line", C, ["shop:family", "faq"], "Family", """
Add a family member with their own login. A dependent's orders wait for your approval. You can set data limits and see their usage; they cannot see yours.
""")
article("Devices, eSIM and SIM swap", C, ["shop:devices", "faq"], "Devices", """
My devices lists your phones and routers, financing and warranty. Switching to a new phone: order an eSIM or SIM swap here — we verify it is you first. Report a lost phone to block the SIM immediately.
""")
article("Your account and privacy", C, ["shop:account", "faq"], "Account", """
Change contact details, payment method and marketing choices under Account. Download your data or ask what we hold about you from the same page. Every AI use on your data is listed under "AI data flows" with the option to opt out.
""")
article("Getting help", C, ["shop:support", "shop:notifications", "faq"], "Getting started", """
Search the FAQ first. The chat assistant answers from the same FAQ and your own account; it hands you to a person when it cannot help. Raise a ticket for anything else — you will see its progress under Support.
""")

# =====================================================================================
# SALES — dealer / business (audience sales)
# =====================================================================================
S = "sales"
article("Selling a store-only offer", S, ["biz:dealer", "sales"], "Sales how-to", """
Some offers are sold only in stores or by telesales (the catalog says so). They do not appear in the web shop, and a customer cannot order them online. In the dealer console you see them; the order is tagged to your channel and your commission.
""")
article("Business orders and consolidated invoices", S, ["biz:orders", "sales"], "Sales how-to", """
A company account orders on behalf of employees; the invoice is consolidated monthly. Consumer-only discounts never apply to a company order. Quotes turn into orders on acceptance.
""")

print(f"{TENANT}: {made} articles created, {updated} updated ({BRAND}, {CUR})")
