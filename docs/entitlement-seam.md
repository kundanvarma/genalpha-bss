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

**Proof.** `ops/e2e/device_entitlement_test.js` (#124): two plans (with and
without Wi-Fi calling) → a subscriber bound → the simulated phone
(`integrations/mock-ts43-device`, a TS.43 client that reads its USIM secrets
from the HSS mock) runs the EAP-AKA relay and reads VoLTE / VoNR / VoWiFi /
SMSoIP / data plan → the token is honoured → a forged EAP answer is refused
and an authenticated-but-unbound SIM is refused → the service flow completes →
the plan without Wi-Fi calling answers DISABLED → a watch activates a
companion eSIM and a new phone receives a transferred primary profile → a
suspended line answers DISABLED and the BSS face explains it → another
tenant sees nothing. Unit: `EapAkaTest` (cross-implementation vectors,
3GPP TS 35.208 test set 1).

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
  fleet creates authenticates; production binds `AUC_BASE_URL` to an adapter
  in front of the HSS/UDM (SWx/S6a/Nudm), and the ECS never holds K.
- **XML responses and OIDC.** TS.43 allows `text/vnd.wap.connectivity-xml`
  and an OAuth/OIDC websheet path for clients without SIM access; JSON and
  EAP-AKA/token are what current iOS/Android clients use, and are what ships.
- **RCS, satellite, private user identity** (other app ids) — the same
  decision service, one more `case` each when a plan sells them.
- **Push delivery.** Server-initiated refresh records the intent and the
  registered push token; the FCM/APNS/SMS transport is the notification seam.
- **The line-side eSIM swap** after a transfer: announced, not executed —
  wiring `SubscriptionTransferRequestedEvent` to the orchestrator's SIM
  replacement is the natural next slice.
