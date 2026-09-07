#!/usr/bin/env python3
"""ENet history seed — a lived-in customer before the first demo.
Devi Persaud (devi@enet.example) gets what a real customer of a few weeks has:
a mobile line on Orange 30 Days 5G with her number as her WhatsApp contact, an
installed-address on file, a fibre order with a booked installation window, a
data top-up, 50 of 60 GB used this month (which trips the "running low"
journey with 10 GB left), a bill from a billing run, and a closed support ticket. Everything is
fictional; nothing here touches a real network or person. Idempotent — each
step checks before it acts, so re-runs add nothing.
Needs the demo slice up; the ticket step is skipped when trouble-ticket is down.
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

API = "http://localhost:8080"
KC = "http://localhost:8085/realms/enet/protocol/openid-connect/token"
OCS = "http://localhost:8115"
ORDERS = "/tmf-api/productOrderingManagement/v4"
INV = "/tmf-api/serviceInventory/v4"
CAT = "/tmf-api/productCatalogManagement/v4"
APPT = "/tmf-api/appointment/v4"
PARTY = "/tmf-api/party/v4"
BILLS = "/tmf-api/customerBillManagement/v4"
TT = "/tmf-api/troubleTicket/v4"
ADDRESS = {"streetName": "Lot 12 Camp Street", "city": "Georgetown", "stateOrProvince": "Demerara-Mahaica",
           "postCode": "4131519", "country": "GY"}


def token(user, pw):
    data = urllib.parse.urlencode({"grant_type": "password", "client_id": "bss-demo", "username": user, "password": pw}).encode()
    with urllib.request.urlopen(urllib.request.Request(KC, data=data)) as r:
        return json.load(r)["access_token"]


def call(method, path, tok, body=None, quiet=False):
    r = urllib.request.Request(path if path.startswith("http") else API + path,
                               data=json.dumps(body).encode() if body is not None else None,
                               headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {tok}"} if tok else {})},
                               method=method)
    try:
        with urllib.request.urlopen(r) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        body_txt = e.read().decode()[:200]
        if not quiet:
            print("ERR", method, path, e.code, body_txt)
        return e.code, None


def chars(sv):
    return {c["name"]: c["value"] for c in (sv.get("serviceCharacteristic") or [])}


def wait_line(tok, pred, tries=30):
    for _ in range(tries):
        time.sleep(2)
        _, svcs = call("GET", f"{INV}/service", tok)
        hit = next((s for s in (svcs or []) if pred(s)), None)
        if hit:
            return hit
    return None


staff = token("demo", "demo")
devi = token("devi@enet.example", "devi")
_, mine = call("GET", f"{PARTY}/individual?limit=1", devi)
if mine:
    me = mine[0]
else:
    # a fresh fleet: the realm user exists, the party record does not until her first
    # sign-in — create it under her identity id, as self-registration would
    import base64
    payload = devi.split(".")[1]
    sub = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["sub"]
    st, me = call("POST", f"{PARTY}/individual", staff, {"id": sub, "givenName": "Devi", "familyName": "Persaud"})
    if not me:
        raise SystemExit("could not create Devi's party record")
    print("party: created for devi@enet.example (fresh fleet)")
print(f"Devi: party {me['id'][:8]}")
_, offers = call("GET", f"{CAT}/productOffering?limit=100", staff)
offer = {o["name"]: o for o in offers}

# ---- 1. the mobile line -------------------------------------------------------------------
line = next((s for s in (call("GET", f"{INV}/service", devi)[1] or []) if s.get("state") == "active" and "Orange" in s.get("name", "")), None)
if not line:
    plan = offer["Orange 30 Days 5G"]
    call("POST", f"{ORDERS}/productOrder", devi, {"productOrderItem": [{"action": "add", "productOffering": {"id": plan["id"], "name": plan["name"]}}]})
    line = wait_line(devi, lambda s: s.get("state") == "active" and "Orange" in s.get("name", ""))
    print("line: Orange 30 Days 5G ordered and activated" if line else "line: NOT activated (SOM/ordering up?)")
else:
    print(f"line: {line['name']} already active")
msisdn = next((r.get("value") for r in (line.get("supportingResource") or []) if r.get("value")), None) if line else None

# ---- 2. her contact details: the line is her WhatsApp number; an address on file -----------
media = [m for m in (me.get("contactMedium") or []) if str(m.get("mediumType", "")).lower() not in ("mobile", "postaladdress")]
if msisdn:
    media.append({"mediumType": "mobile", "preferred": True, "characteristic": {"phoneNumber": msisdn if msisdn.startswith("+") else "+" + msisdn}})
media.append({"mediumType": "postalAddress", "characteristic": ADDRESS})
st, _ = call("PATCH", f"{PARTY}/individual/{me['id']}", staff, {"contactMedium": media})
print(f"contact: WhatsApp {msisdn} + Camp Street address ({st})")

# ---- 3. fibre at home with a booked installation window -----------------------------------
fibre = next((s for s in (call("GET", f"{INV}/service", devi)[1] or []) if "OnFiber" in s.get("name", "")), None)
if not fibre:
    onf = offer["OnFiber 300"]
    st, order = call("POST", f"{ORDERS}/productOrder", devi, {"productOrderItem": [{"action": "add", "productOffering": {"id": onf["id"], "name": onf["name"]}}]})
    if order:
        _, slots = call("POST", f"{APPT}/searchTimeSlot", devi, {"relatedPlace": {"role": "installation", "postCode": ADDRESS["postCode"], "city": ADDRESS["city"]},
                                                                 "relatedEntity": [{"id": onf["id"], "name": onf["name"], "@referredType": "ProductOffering"}]})
        win = (slots or {}).get("availableTimeSlot") or []
        pick = win[min(2, len(win) - 1)] if win else None
        if pick:
            st2, appt = call("POST", f"{APPT}/appointment", devi, {"validFor": pick["validFor"], "description": "Installation: OnFiber 300",
                             "relatedEntity": [{"id": order["id"], "@referredType": "ProductOrder"}],
                             "relatedPlace": {"role": "installation", **ADDRESS}})
            print(f"fibre: OnFiber 300 ordered ({order['id'][:8]}), installation {pick['validFor']['startDateTime'][:16]} ({st2})")
        else:
            print(f"fibre: OnFiber 300 ordered ({order['id'][:8]}), no slot returned")
else:
    print(f"fibre: {fibre['name']} already on file ({fibre.get('state')})")

# ---- 4. a top-up, then a month two-thirds used → the "running low" WhatsApp ----------------
if line:
    topup = offer.get("ENet Data Top-up 5 GB")
    _, orders = call("GET", f"{ORDERS}/productOrder?limit=100", devi)
    had_topup = any(any(i.get("productOffering", {}).get("name") == "ENet Data Top-up 5 GB" for i in (o.get("productOrderItem") or [])) for o in (orders or []))
    if topup and not had_topup:
        call("POST", f"{ORDERS}/productOrder", devi, {"productOrderItem": [{"action": "add", "productOffering": {"id": topup["id"], "name": topup["name"]}}]})
        print("top-up: ENet Data Top-up 5 GB bought")
    try:
        with urllib.request.urlopen(f"{OCS}/subscribers?tenantId=enet") as r:
            subs = json.load(r)
        sub = next((s for s in subs if s.get("serviceId") == line["id"]), None)
        if not sub:
            # a line activated before its plan carried chargingSpecId has no OCS
            # subscriber (dev fleets only; a fresh box provisions at activation)
            req = urllib.request.Request(f"{OCS}/subscribers", data=json.dumps({"tenantId": "enet", "partyId": me["id"], "serviceId": line["id"],
                                         "ratePlanId": "RG-DATA-60", "msisdn": msisdn}).encode(), headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req) as r:
                sub = json.load(r)
            print("usage: OCS subscriber provisioned for the existing line (dev fleet catch-up)")
        if sub:
            used = sum(float(b.get("usedGB", 0)) for b in (sub.get("buckets") or []))
            if used < 45:
                # 50 of the 60 GB counter: past the 80 % threshold, 10 GB left — the
                # "running low" WhatsApp reads right and the meter is not yet empty
                gb = 50 - used
                urllib.request.urlopen(urllib.request.Request(f"{OCS}/subscribers/{sub['id']}/usage", data=json.dumps({"gb": gb}).encode(),
                                                              headers={"Content-Type": "application/json"}, method="POST")).read()
                print(f"usage: +{gb:.0f} GB on the line → 50 of 60 GB used this month (running-low journey fires, 10 GB left)")
            else:
                print(f"usage: {used:.0f} GB already used this month")
        else:
            print("usage: line has no OCS subscriber (plan without chargingSpecId?)")
    except Exception as e:
        print("usage: OCS mock not reachable —", e)

# ---- 5. a bill: run the cycle so My bills and the finance desk have something ---------------
st, run = call("POST", f"{BILLS}/billingRun", staff, quiet=True)
print(f"billing run: {st} {json.dumps(run)[:120] if run else ''}")

# ---- 6. one closed support ticket, the kind an agent expects to see -------------------------
st, tickets = call("GET", f"{TT}/troubleTicket?limit=50", staff, quiet=True)
if st == 200:
    mine = [t for t in (tickets or []) if any(p.get("id") == me["id"] for p in (t.get("relatedParty") or []))]
    if not mine:
        st2, t = call("POST", f"{TT}/troubleTicket", staff, {"name": "Slow speeds in the evening", "severity": "medium", "priority": "medium",
                      "ticketType": "complaint", "description": "Customer reports slow 5G between 20:00 and 22:00 on Camp Street; cell congestion confirmed, Match Day Boost suggested.",
                      "relatedParty": [{"id": me["id"], "name": "Devi Persaud", "role": "customer", "@referredType": "Individual"}]})
        if t:
            call("PATCH", f"{TT}/troubleTicket/{t['id']}", staff, {"status": "resolved", "resolutionDate": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}, quiet=True)
            print("ticket: 'Slow speeds in the evening' opened and resolved")
    else:
        print(f"ticket: {len(mine)} already on file")
else:
    print("ticket: trouble-ticket service not running — skipped")

print("\nDevi now has a line, a WhatsApp number, an address, a fibre order with an installation window, a top-up, "
      "a month two-thirds used, a bill run and a closed ticket.")
