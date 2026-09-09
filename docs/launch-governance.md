# Launch governance — intent to launch, with an approval step

*Who decides that customers can buy a new offer, and how the system knows without slowing the people who are allowed to decide for themselves.*

## The problem it answers

In every operator the same three things are true at once:

1. **Most launches are routine.** A new top-up in the usual price band, a seasonal pass, a plan variant — the pricing committee approved the *shape* months ago, nobody wants to see each instance.
2. **Some launches are decisions.** A new price band, a new market, unlimited data, a business tier, anything that touches the P&L or the network in a new way — commercial, finance, sometimes legal, want to look before it goes live.
3. **Approval is not readiness.** Finance said yes on Monday; marketing's collateral slips; care has not been briefed. The offer must be able to wait, be held after approval, and still launch itself on the day without someone pressing a button at midnight.

This module makes those three true in the BSS instead of in e-mail.

## The three tenant modes

Set per tenant in `infra/tenants/tenants.yml`:

| `launch-governance` | Meaning |
|---|---|
| `none` (default) | A write is a launch. Exactly today's behaviour — nothing changes for tenants that do not opt in. |
| `envelope` | Offers **inside a pre-approved envelope launch by themselves** and are ledgered to the envelope that let them through. Everything outside asks an approver. |
| `always` | Every launch asks an approver. Envelopes still describe the shape, they just do not decide. |

Two companions:

- `ai-proposals: trust | approve` — whether a copilot proposal is judged like a human draft (`trust`, the default) or always asks, envelope or not (`approve`).
- `launch-readiness` — the owners who tick before a launch, as `authority|label` lines. The default demo set is marketing (`campaign:write`), care (`ticket:write`) and billing (`billing:write`). An approver may launch past open ticks with `force`, and the ledger names what was skipped.
- `approval-expiry-days` (default 60) — an approval that is never launched goes stale and must be asked again.

## Where it lives in the architecture

```
  product owner / copilot          approver (catalog:approve)        readiness owners
        │ POST /productOffering            │                                  │
        ▼                                  ▼                                  ▼
 ┌───────────────────────────────────────────────────────────────────────────────────┐
 │ TMF620 Product Catalog                                                            │
 │   ProductOffering (STANDARD resource, untouched)                                  │
 │   + governance beside it: state · ledger · readiness · hold · approval expiry     │
 │   doors: /productOffering/{id}/governance/{request|approve|reject|hold|resume|   │
 │          ready|launch|unlaunch}  ·  /governance/{queue|settings|dry-run}          │
 └────────────┬───────────────────────────────┬──────────────────────────────────────┘
              │ evaluate(domain "launch")      │ ProductOfferingGovernanceEvent
              ▼                                ▼
   TMF723 Policy: ENVELOPES          TMF701 Process Flow: the auditable timeline
   (allow rules, picker-authored)    spec "offer-launch-governance", one task per
                                     step with an SLA allowance, held/cancelled/completed
```

- **The TMF620 resource stays standard.** No governance attributes on `ProductOffering`; the CTK sees the same shape as before. Governance state, the ledger and readiness are internal columns read through the governance door. Lifecycle still walks the TMF ladder (`In study → In design → In test → Launched → Retired → Obsolete`) and `validFor` still decides visibility server-side.
- **Envelopes are TMF723 policy rules** (`domain: launch`, `effect: allow`). The console authors them with pickers only — category, price band, allowance, validity, channels — and stores the pickers in `experience.envelope` beside the JSON-logic condition the engine evaluates. Nobody types `"webapp"`.
- **The process service mirrors every step as a TMF701 flow** so the timeline is auditable and SLA-timed (approval allowance five days, readiness three) without the catalog owning a workflow engine.
- **Events** — `ProductOfferingGovernanceEvent` on `bss.catalog.events` with `action` ∈ requested · approved · rejected · held · resumed · ready · launched · unlaunched · voided · expired. The launch itself still fires `ProductOfferingLaunchedEvent` when the window opens, so launch-day journeys are unchanged. Martech listeners were reviewed on purpose: none subscribes to governance events (a launch that is *pending* is not a marketing signal; the launch event stays the trigger).

## The rules, in plain words

- With governance on, a **non-approver's write lands as a draft** even if it says `Active`. An **approver's write lands live** and is ledgered as "created live by … (approver)".
- **Request** evaluates the envelopes (envelope mode). Inside one → approved at once, ledger names the envelope. Outside → `requested`, the approvals desk shows it with the reason (the dry-run context: price, allowance, validity, channels).
- **Approve / reject** need `catalog:approve` — a realm role granted to commercial and finance personas, never to the product persona.
- **Readiness** ticks are owner-scoped: marketing cannot tick billing's item. An approver can tick anything.
- **Hold** may be pulled by any catalog writer (a hold is the safe direction). A dated hold on a live offer pushes `validFor.start` to the date and the offer returns to sale by itself; an open-ended hold withdraws it (`In test`) until resumed. Held offers never announce themselves.
- **A substance edit voids a pending approval** — name, price, spec, category, terms, channels, bundle. Copy and dates are free to edit. The ledger says "voided — request again".
- **Launch** requires approved + not held + ready (or `force` by an approver). It sets `Active`, opens the window (now, or the given `validFrom`) and can narrow the channel list.
- **Unlaunch** sets the end date (Retired when in the past) and keeps the whole trail.
- **Expiry**: an approval older than `approval-expiry-days` flips to `expired` on the launch tick and must be re-requested.

## Channels

`ProductOffering.channel[]` is the TMF620 attribute; the registered ids are `web · app · store · telesales · care · business · partner · agent-acp · agent-mcp · agent-a2a`. Every front end declares its channel (`X-Channel`), the catalog enforces sellability **per channel server-side**, ordering forwards the header so a store-only pack ordered from the web is refused, and the agent surfaces (ACP feed, MCP server) are channels like any other — an offer not opened to `agent-acp` never appears to shopping agents. An empty list means every channel. Unknown ids are refused at authoring with the registered list in the message.

## Zero-rated apps

A spec characteristic `zeroRatedApps` ("WhatsApp, Instagram, TikTok") rides from the catalog through SOM to the OCS at activation. The OCS — not the BSS — makes them free: the mock OCS counts app-tagged usage separately and never touches the bucket. The shop shows the list under the data allowance. A real OCS (Mavenir, Matrixx, Ericsson) maps the list onto its rating groups; the seam is the provisioning call.

## Personas in the demo (taranga)

| Persona | Role | What they can do |
|---|---|---|
| sigrid@taranga.example | product manager | draft, request, tick nothing, launch what is approved and ready |
| henrik@taranga.example | commercial | approve, reject, force-launch, tick any readiness |
| ingrid@taranga.example | marketing | tick marketing readiness, hold / resume |
| demo | admin | everything |

ENet: rohan (product) requests, dwayne (finance) approves.

## How a demo runs

1. Console › Catalog & Pricing › **Envelopes** — show the two envelopes; edit a price band; the live list shows which drafts would launch by themselves.
2. Product copilot: "a social pack with WhatsApp and TikTok free, 30 days, app and web" → proposal shows launch date, channels, zero-rated apps → *Make it real* → verdict: "launches by itself — inside envelope 'Top-ups and passes under 199 NOK'".
3. Ask for an unlimited premium tier at 599 → "needs an approver — outside every envelope" → **Approvals** desk as henrik: the reason line, Approve.
4. Readiness ticks; as ingrid, **Hold** with a date ("collateral slipped"); as sigrid, Launch is refused while held; Resume; Launch — the offer is on the shelf, the TMF701 flow shows the timeline.

Suite: `ops/e2e/launch_governance_test.js` (#119); channels: `ops/e2e/channel_availability_test.js` (#118). Seeds: `ops/seed/seed_launch_governance.py [taranga|enet]`, `ops/seed/realm_governance_roles.py` (live realm roles + personas).
