# Dependency-aware bundle decomposition + lifecycle-ready Internet/TV model

*Design doc. The "big one" from the order-experience backlog (#205), reshaped by
research into CSP fulfilment dependencies and the TM Forum product model.*

## 1. The problem the demo surfaced

A customer orders **GenAlpha One Home & Mobile** (= Fiber 1000 + TV Max + Mobile
Unlimited 5G + a chosen phone + optional Sports Pass). The shop shows the whole
thing as one line that reads *"Order completed"* nested inside *"In progress"* —
confusing, and it hides the truth that these are **five different things
fulfilling on five different clocks**. The customer can't see that the phone
shipped, the SIM is on its way, the fiber needs an install, and TV can't turn on
until the fiber is live.

Two domain truths the operator (rightly) insisted we get right *before* building:

1. **TV depends on Internet.** Broadband is the base product; IPTV rides on the
   broadband connection and cannot activate until the line is live. (Mobile is
   independent — its own network. The handset ships independently.)
2. **A physical-SIM number can't fully activate until the SIM arrives.** eSIM
   activates instantly (downloaded over Wi-Fi); a physical SIM line is *reserved*
   at order and completes when the SIM is delivered and bound (ICCID↔MSISDN).

And a third, forward-looking one: **model Internet & TV so upgrade/downgrade and
pack changes never force a rebuild.**

## 2. Research

### 2.1 CSP fulfilment: parallel where possible, sequenced by dependency
A triple-play order splits into per-service activations run **in parallel where
possible but sequenced by dependency** — IPTV relies on broadband being live
first. Order orchestration exists precisely to resolve these interdependencies
between sub-orders.
- CGI, *Order Orchestration in Next-Gen BSS/OSS*
- Oracle OSM, *How OSM Processes Orders* (orchestration plan / dependencies)
- *Order Handling in Convergent Environments* (arXiv 1201.0851)

### 2.2 TM Forum way to express the dependency
`TMF622` order items carry an **`orderItemRelationship`** with type
**`"reliesOn"`** — the TV item *reliesOn* the broadband item. Orchestration reads
that and activates broadband first, then the dependents. (Same mechanism on
`TMF641` service orders.)
- TMF622 Product Ordering; TMF622 relationship types (engage.tmforum.org)

### 2.3 SIM activation
- **eSIM:** instant — no delivery dependency.
- **Physical SIM:** the line is generally not usable until the SIM arrives and is
  activated (keyed to the ICCID). Honest default: number *reserved* at order →
  active on delivery. (T-Mobile / Astound activation guides.)

### 2.4 Modelling Internet & TV so lifecycle changes are free (SID / TMF620)
The SID rule: a subscription's **variable** attributes live as
**`ProductSpecCharacteristic`** values on the subscribed **Product** (TMF637),
never baked into the offering name/price.
- **Internet speed** → a `configurable` `downloadSpeed` characteristic with an
  allowed-value set (100/300/500/1000) and a default. Upgrade/downgrade =
  `TMF622 action=modify` changing the characteristic value — same line, same
  identity. Gated by eligibility (min-term) + `TMF645/679` qualification (does
  the line support the higher tier).
- **TV** → base product + channel packs as **optional bundled components**
  (`bundledProductOfferingOption` lower=0 — how Sports Pass already is) and/or a
  configurable `channelPack`/`points` characteristic. Add/remove a pack =
  `modify` adding/removing a child. TV still **reliesOn broadband**.

## 3. Where we are today (recon)

| Concern | Today | Verdict |
|---|---|---|
| Bundle in the order | Bundle parent names the bundle offering; only the **chosen** phone + optional Sports arrive as nested children. Fixed Mobile/Fiber/TV are **implicit** — not order items. | Bundle is a black box: SOM provisions the parent as ONE MSISDN service. |
| Component fulfilment | SOM branches only insurance/top-ups/partner/security; everything else draws an MSISDN + mints a SIM. | No TV / Broadband / Device awareness. |
| Dependencies | **None.** No `orderItemRelationship`/`reliesOn` anywhere; process/flow components are read-only projections, they don't sequence. | Must add. |
| Order rollup | `updateItemState → rollupState` over **leaf** states; `partiallyCompleted` exists. Components report back by REST PATCH. | Works — needs real leaf items to roll up. |
| Internet speed | **Separate flat offerings**, speed in the name/price. Tiers exist only as coverage data (`maxDownMbps`). | Not a characteristic → upgrade/downgrade would rebuild. |
| TV packs/points | Flat TV offering; Sports/Kids as separate `lower=0` optional offerings. No packs/points characteristic. | Packs are offering-level (fine); no points model. |
| `action=modify` | **Whole-offering swap + relabel only.** Rejects a same-offering characteristic change ("already on that plan"); never writes `productCharacteristic`; SOM relabels, does not re-provision. | Can't upgrade a characteristic. |
| Inventory (TMF637) | **Persists** `productCharacteristic` JSON; honors it on PATCH; `provision()` passes it on initial order. | Mechanism ready; modify just never writes it. |
| TMF760 configurator | **Already** surfaces characteristic-level options + validates + conditions price — but fiber/TV specs carry no characteristics. | Add the characteristics → configurator lights up for free. |

**Bottom line:** decomposition is genuinely missing (build it); the lifecycle
model is *mostly latent* — the plumbing exists, the catalog data + two code seams
are the gap.

## 4. Target model

The order becomes a real tree of leaf components, each on its own clock, sequenced
by dependency:

```
Order — In progress  (rolls up from the leaves below)
├── 🌐 Internet — Fiber            [base]         Install Thu 14:00 → ✓ Active
│      • downloadSpeed = 1000 (configurable: 100/300/500/1000)   ← lifecycle-ready
├── 📺 TV — TV Max                 reliesOn Internet  ⏳ waiting on Internet → ✓ Active
├── 🎬 Sports Pass                 reliesOn Internet  ⏳ waiting on Internet → ✓ Active
└── 📱 Mobile — Unlimited 5G       [independent]      ⏳ in progress
       ├── Number / SIM
       │     • eSIM     → ✓ Active on +4790…  (instant)
       │     • physical → ⏳ +4790… reserved — activates when your SIM arrives (track ↗) → ✓ Active
       │     • porting  → ⏳ Porting from {OtherTelco}, cutover {date}
       └── Handset      [independent]  🚚 Shipped · track ↗
```

- Dependencies expressed as `orderItemRelationship: [{relationshipType:"reliesOn", id}]`
  on the dependent order item; enforced by orchestration (hold the dependent's
  service order until the predecessor's service instance is ACTIVE, then activate).
- Each leaf provisions to a **subscribed Product (TMF637)** carrying its
  characteristics — so a later `action=modify` (bump speed, add a pack) has a real
  target. *This is the hinge that makes the decomposition lifecycle-ready.*

## 5. Phased plan (each phase built + proven before the next)

**Phase 1 — Decomposition foundation (backend).**
Ordering expands a bundle item at create into per-component leaf children (fixed
members + chosen options), each stamped with an id + category. SOM provisions only
**leaves** (bundle parent = container, skipped), classifying each by catalog
category → category-aware fulfilment: Broadband→install, TV/Sports→instant
entitlement, Mobile→MSISDN+SIM, Device→ship. Order rolls up from the leaves.

**Phase 2 — Dependency sequencing.**
Add `reliesOn` (TV/Sports → the Internet leaf) at expansion. SOM holds a
dependent component IN_PROGRESS until its predecessor's service instance is ACTIVE,
then activates it (reuse the deferred-completion fan-out). Physical-SIM number
activation reliesOn SIM delivery; eSIM instant.

**Phase 3 — The two order views (shop + console).**
Storefront `Orders.jsx`: group leaves by category into the tree above, with plain
dependency notes ("Waiting for your broadband install"), and **fix "completed
inside in progress"** — a partiallyCompleted order reads "In progress · 3 of 5
ready", never a bare ✓ under an ✗. Console `Customer360`: per-component rows.

**Phase 4 — Lifecycle-ready product model (catalog data).**
Add a `configurable` `downloadSpeed` characteristic (100/300/500/1000, default
1000) to the fiber spec, priced per tier via the existing characteristic-conditioned
price machinery. Add a `channelPack`/`points` characteristic (or keep packs as
optional offerings) to TV. The TMF760 configurator surfaces both automatically.

**Phase 5 — Upgrade/downgrade behavior (`action=modify`).**
Allow a same-offering characteristic-only modify; write `productCharacteristic`
through to inventory; SOM re-provisions the line speed / TV entitlement (not just
relabel). Eligibility ladder: block downgrade under min-term (exists), gate an
upgrade on TMF645 qualification (line supports the tier).

**Phase 6 — Suite.**
An end-to-end suite proving: bundle decomposes into 5 tracked components; TV waits
for broadband then activates; physical SIM number reserved→active on delivery;
each component tracks independently; the order reads partiallyCompleted correctly;
and a speed upgrade (100→1000) via modify lands on the subscribed product.

## 6. Deliberately deferred / out of scope (for honesty)
- No new network RFS — "re-provision the line speed" is a rate-plan + characteristic
  change against the dev OCS/inventory, not a real DSLAM move.
- TV "points" as a spendable balance (like loyalty) is modeled as a characteristic
  value here, not a full points ledger, unless Phase 4 says otherwise.
- Cross-tenant / white-label variations ride the existing config-as-data seams.
