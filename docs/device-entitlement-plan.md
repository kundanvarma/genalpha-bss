# Device entitlement — v2 plan (from research, 2026-09-11)

What v1 proved: a GSMA TS.43 Entitlement Configuration Server as an ODA
component, real EAP-AKA over HTTP, plan-driven decisions, ODSA for companion
and primary eSIMs (`docs/entitlement-seam.md`). What v2 closes, in the order
the dependencies dictate. Every item names the spec section or the code it
rests on; nothing here is guessed.

## Research findings that shaped the plan

- **SIM swap in the orchestrator** (`SomController.replaceSim`): blocks the
  old card through the SIM-platform seam, mints a fresh `SimCard` (ICCID +
  PUK), publishes `SimReplacedEvent` with masked ICCIDs. The `SimCard` has no
  notion of form (physical/eSIM) or EID — a transfer to an eSIM needs both.
- **Activation** (`OrchestrationService`, the numbered-line branch): the
  MSISDN is a `ResourceAssignment`, the SIM is minted right there
  (`attachKitSimOrMint`), the OCS is provisioned fail-open. The entitlement
  binding belongs in the same place, the same way.
- **Communication** already forwards `push` and `sms` message types
  (`PushForwarder`, `SmsForwarder`), takes the receiver as
  `relatedParty[role=customer]`, and exempts `category: transactional` from
  the marketing footer. `POST communicationMessage` needs `communication:write`.
- **TS.43 §2.6**: server-initiated refresh = a push (FCM/APNS/WNS token from
  `notif_token`) or an application-port SMS; payload
  `{"app": ["ap2003", …], "timestamp": "<ISO 8601>"}`; transport details are
  implementation-dependent.
- **TS.43 §2.8.2**: OIDC path — the ECS answers `302 Found` to the OIDC
  authorize endpoint (`response_type=code&scope=openid&client_id&redirect_uri=
  <ECS URL>&state&nonce`), receives the code on its own URL, exchanges it for
  an access + ID token, identifies the subscription, and resumes the original
  request. Used when there is no token and no `EAP_ID` (clients without SIM
  access, companion web sheets, SMS-OTP as a second factor).
- **TS.43 XML**: `text/vnd.wap.connectivity-xml`, a `wap-provisioningdoc
  version="1.1"` with `characteristic type="VERS" | "TOKEN" | "APPLICATION"`,
  `parm name/value`, nested `characteristic type=…` for structured values,
  repeated characteristics for arrays (Tables 54–69, 138).
- **More application ids** (v12.0): ap2012 Direct Carrier Billing
  (`EntitlementStatus`, `TC_Status`), ap2013 Private User Identity
  (`EntitlementStatus`, `PrivateUserID`, `PrivateUserIDType`, expiry — an
  encoded identity for Wi-Fi gateways), ap2014 phone number
  (`GetPhoneNumber` → `MSISDN`), ap2016 SatMode (`EntitlementStatus`,
  `ServiceFlow_URL`). **RCS is not a TS.43 application**: RCS
  auto-configuration is GSMA RCC.14 — a separate configuration server with
  the same HTTP/EAP-AKA framework. It stays a plan characteristic on the BSS
  face and a named follow-up, not a fake app id.
- **Cluster**: the EAP relay session (identifier, XRES, K_aut) is the only
  per-request state; a table replaces the in-memory map.

## The slices — all eight shipped 2026-09-11 (suite #124, 15 legs)

1. **Per-tenant AUC** — tenants.yml `auc-base-url` / `auc-token`; the AUC
   adapter resolves per tenant like the OCS adapter. Foundation for MVNOs
   behind different host MNOs.
2. **Bind automatically** — the orchestrator calls the entitlement server
   (fail-open `EntitlementClient`): at activation (party, line, plan, MSISDN,
   ICCID), on plan change, on SIM replacement. The entitlement server resolves
   the IMSI from the ICCID through the AUC seam (a real HSS knows the pairing;
   the mock allocates one). Termination, suspension and resumption keep
   riding `bss.som.events`.
3. **Close the transfer loop** — the orchestrator consumes
   `SubscriptionTransferRequestedEvent`: blocks the old SIM, activates the new
   eSIM profile (`SimCard` gains `form` and `eid`), publishes
   `SimReplacedEvent`; the entitlement server marks the transfer completed,
   re-binds the ICCID and revokes the old device's tokens.
4. **Refresh transport** — `reconfigure` sends the TS.43 §2.6 payload through
   communication: push when the phone registered a token, else an SMS —
   stamped transactional, fail-open, recorded with the channel used.
5. **More apps** — ap2012 (plan `carrierBilling`), ap2013 (an HMAC pseudonym
   of the IMSI, keyed per tenant), ap2014 (the bound MSISDN), ap2016 (plan
   `satellite`); RCS shown on the BSS face from the plan's `rcs`.
6. **XML + OIDC** — XML rendering on `Accept: text/vnd.wap.connectivity-xml`;
   the OIDC path with a confidential `bss-ecs` client in each realm, the
   original request carried in `state`, the subscription found by the
   authenticated party.
7. **Cluster** — `eap_session` table, swept.
8. **Browser legs** — the console page asserted; the OIDC login flow driven
   through a real browser; the whole SOM-bound path (order → phone) in the
   suite.

## Out of scope, still

Apple/Google certification; RCC.14 RCS configuration server; SM-DP+ (the
activation code is minted, the profile download itself is the SM-DP+'s);
SMS-OTP second factor on the OIDC path.
