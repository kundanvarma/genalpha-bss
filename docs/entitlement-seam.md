# The entitlement seam: who answers "what may this phone use — Wi-Fi calling, VoLTE, an eSIM for the watch?"

## What the industry does

Phones no longer read entitlements off the SIM. Since iOS carrier bundles and
Android's carrier configuration moved to **GSMA TS.43 Service Entitlement
Configuration**, the device's own client asks the operator's **Entitlement
Configuration Server (ECS)** — over HTTPS, authenticated with the SIM itself
(**EAP-AKA relayed over HTTP**, RFC 4187 inside TS.43 §2.8) or a token the
ECS issued — which services the subscription includes, per *application id*:

| app | what | TS.43 answer |
|---|---|---|
| ap2003 | Voice-over-Cellular (VoLTE on 4G, VoNR on 5G) | `EntitlementStatus` per access type |
| ap2004 | Voice-over-Wi-Fi | `EntitlementStatus`, `AddrStatus` (emergency address), `TC_Status` (terms), `ProvStatus`, `ServiceFlow_URL` |
| ap2005 | SMS over IP | `EntitlementStatus` |
| ap2006 | On-Device Service Activation, companion eSIM (watch, tablet) | eligibility, `SubscriptionResult` + SGP.22 download info, configuration |
| ap2009 | ODSA, primary eSIM (new phone, subscription transfer) | the same operations for the primary line |
| ap2010 | Data plan information | metered / unmetered per access type |

`EntitlementStatus` is 0 DISABLED, 1 ENABLED, 2 INCOMPATIBLE, 3 PROVISIONING.
Server-initiated changes reach the phone by push (FCM/APNS token registered
in the request) or by an application-port SMS (TS.43 §2.6). Apple and Google
each certify an operator's ECS before their clients will talk to it — that
onboarding, and tracking spec drift across OS releases, is why GSMA itself
sells a hosted entitlement platform and why an MVNO case study shows eSIM
transfer and RCS switched on in two weeks with one.

Vendors converge on one split: the ECS is a **decision** service fed by the
BSS (which plan, which line state, which device) and an **authentication**
relay to the operator's AUC (3GPP AAA / HSS / UDM — for an MVNO, the host's).
It never owns the subscription; it reads it.

## What genalpha-bss does

`device-entitlement` is that ECS as an ODA component (`services/device-entitlement`,
`:8156`), with two faces:

- **The device door: `/ts43`** (through the gateway, anonymous at HTTP
  level). `GET` or `POST` with TS.43's parameters (`terminal_id`, `app`,
  `EAP_ID`, `token`, `operation`, `operation_type`, `companion_terminal_*`,
  `target_terminal_*`, `notif_token` …). First contact runs **EAP-AKA over
  HTTP**: the ECS asks the **AUC seam** (`AucClient`; `RestAucClient` speaks
  the REST shape of `integrations/mock-hss`, an HSS/UDM adapter in production)
  for a Milenage vector, answers `application/vnd.gsma.eap-relay.v1.0+json`
  with an EAP-Request/AKA-Challenge (AT_RAND, AT_AUTN, AT_MAC) and an
  `ECS_SESSION` cookie; the USIM verifies the network (AUTN) and posts its
  EAP-Response; the ECS checks RES against XRES and AT_MAC against K_aut
  (RFC 4187 §7 key derivation, implemented in `eap/EapAka.java` and proven
  byte-identical to the Node implementation the mocks share). Success mints a
  **token** (`Token` block, configurable validity) the phone reuses.
- **The BSS face: `/tmf-api/deviceEntitlement/v1`** (`entitlement:read` /
  `entitlement:write`): bind an IMSI to a party, line and plan
  (`PUT /subscriber`), read a line's entitlements **in plain words and in
  TS.43 terms** (`GET /subscriber/{imsi}`), the phones that checked in,
  companion eSIMs, the request log, `POST /subscriber/{imsi}/reconfigure`
  (server-initiated refresh: recorded, announced, push or SMS by the
  notification seam), token revocation.

**The plan decides.** A mobile plan's product specification carries
`volte`, `vonr`, `vowifi`, `smsoip`, `companionEsim`, `esimTransfer`,
`dataPlanType` (`ops/seed/seed_entitlement.py` stamps the demo plans). The
ECS reads them through TMF620 with its machine identity (cached a minute) and
combines them with the **line's state** — bound, active / suspended /
terminated (followed from `bss.som.events`), IMS provisioned, emergency
address confirmed, terms accepted — and per-line **feature overrides** (a
barring, a trial). Nothing is stored as a decision: a plan change is live on
the next check-in.

**ODSA.** A companion device (ap2006) checks eligibility (`companionEsim`),
subscribes and receives an **SGP.22 activation code** (`LPA:1$<SM-DP+>$…`)
for a freshly minted profile, then sees it in `AcquireConfiguration`; a
primary eSIM transfer (ap2009, `TRANSFER`) is granted on `esimTransfer` with
download info, recorded as a `subscription_transfer` and announced as
`SubscriptionTransferRequestedEvent` on `bss.entitlement.events` — the
line-side SIM swap is the orchestrator's (its `/service/{id}/sim/replace`
already exists).

**The VoWiFi service flow.** `ServiceFlow_URL` points at `/ts43/flow/vowifi`,
a minimal websheet: the emergency address and the terms, completing with a
POST that flips `AddrStatus` / `TC_Status` for the line.

**Bound by the orchestrator, not by hand.** `service-orchestration` calls the
entitlement seam (`EntitlementClient`, fail-open like the OCS seam) when a
numbered line activates (party, line, plan, MSISDN, ICCID), when it changes
plan, and when its SIM is replaced. The entitlement server resolves the IMSI
behind the ICCID through the AUC seam (a real HSS knows the pairing; the mock
allocates one) and treats a new ICCID on a known line as a SIM swap — the
old device's tokens die. Suspension, resumption and termination keep riding
`bss.som.events`.

**The transfer loop is closed.** An ODSA primary transfer publishes
`SubscriptionTransferRequestedEvent`; the orchestrator's
`EntitlementEventListener` blocks the old card at the SIM platform, makes the
new eSIM profile (ICCID, EID — `sim_card.form = esim`) the line's active SIM
and publishes `SimReplacedEvent`; the entitlement server marks the transfer
completed, re-binds the ICCID and revokes the old phone's tokens.

**Refresh reaches the phone.** `POST …/reconfigure` sends TS.43 §2.6's
`{"app": [...], "timestamp": ...}` through the communication component — a
push when the phone registered a token, else an SMS — stamped transactional;
the request log records the channel and the message id.

**More applications.** ap2012 Direct Carrier Billing (plan `carrierBilling`,
`TC_Status`), ap2013 Private User Identity (an HMAC pseudonym of the IMSI,
keyed per tenant — the Wi-Fi gateway never learns the IMSI), ap2014
GetPhoneNumber (the bound MSISDN), ap2016 SatMode (plan `satellite`). RCS
shows on the BSS face from the plan's `rcs`; its configuration server is
GSMA RCC.14, a separate door (see below).

**Two more ways in.** `Accept: text/vnd.wap.connectivity-xml` renders the
same answer as TS.43's `wap-provisioningdoc` (`Ts43Xml`). A client without
SIM access (no `EAP_ID`, no token) is sent `302 Found` to the tenant's OIDC
authorize endpoint (the realm's confidential `bss-ecs` client, TS.43 §2.8.2);
the code comes back to `/ts43/oidc/callback`, is exchanged over the
backchannel, the subscription is found by the authenticated party, an ECS
token is minted and the original request resumes.

**Per tenant, cluster-ready.** The AUC binding is per tenant
(`auc-base-url` / `auc-token` in tenants.yml — an MVNO's SIMs authenticate
against its host MNO's); the EAP relay session lives in `eap_session`, so
any instance can finish a challenge another started.

**Proof.** `ops/e2e/device_entitlement_test.js` (#124, 15 legs): two plans →
a subscriber bound → the simulated phone (`integrations/mock-ts43-device`, a
TS.43 client that reads its USIM secrets from the HSS mock) runs the EAP-AKA
relay and reads every application (ap2003/4/5/10/12/13/14/16) → the token is
honoured and the XML face renders → a forged EAP answer and an
authenticated-but-unbound SIM are refused → the VoWiFi flow completes → the
plan without Wi-Fi calling answers DISABLED → a watch activates a companion
eSIM → a suspended line answers DISABLED, the BSS face explains it, and a
server-initiated refresh reaches the phone as a push through communication →
an ordered plan's line is bound by the orchestrator itself, its IMSI from the
AUC, and a TMF622 plan change follows → an ODSA primary transfer is granted
and COMPLETED by the orchestrator (old SIM blocked, profile live, binding
moved, old token dead) → a tablet without SIM access signs in through OIDC
in a real browser and comes back entitled → the console page shows it in
words → another tenant sees nothing. Unit: `EapAkaTest` (cross-implementation
vectors, 3GPP TS 35.208 test set 1).

**Console.** Operations › Device entitlements: subscribers in words (plan,
line, what the phone may use), devices that checked in, companions, the
request log, "ask the phone to refresh".

## What is deliberately not here

- **OEM onboarding.** Apple's carrier bundle and Google's carrier config must
  point at this ECS and certify it; that is the operator's (or the host
  MNO's) relationship and calendar, months not weeks. The ECS is built to
  pass it; nothing here shortcuts it. GSMA's hosted platform remains the
  alternative for an MVNO without that relationship — this component can
  feed such a platform its decisions over the same BSS face.
- **A real AUC.** `mock-hss` mints deterministic dev secrets so any IMSI the
  fleet creates authenticates and allocates IMSIs for unseen SIMs; production
  binds each tenant's `auc-base-url` to an adapter in front of the HSS/UDM
  (SWx/S6a/Nudm), which already knows the SIM ↔ IMSI pairing. The ECS never
  holds K.
- **RCS configuration.** RCS is not a TS.43 application: its auto-configuration
  is GSMA RCC.14, a separate configuration server on the same HTTP/EAP-AKA
  framework. The plan's `rcs` shows on the BSS face; the RCC.14 door is a
  follow-up, not a fake app id.
- **The SM-DP+.** Activation codes are minted here; the profile download
  itself is the SM-DP+'s (SGP.22), out of scope.
- **SMS-OTP as a second factor** on the OIDC path (TS.43 §2.8.2.1), and push
  delivery beyond what the communication component's forwarders do with the
  registered token.
