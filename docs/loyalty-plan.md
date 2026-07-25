# Loyalty — reward & retain customers (closing the capability-map gap) — plan

*2026-07-25. The capability map's reddest row in Market & Sales: "Reward
& retain customers (loyalty) — ❌ points/tiers/earn-burn need a loyalty
engine." This arc builds that engine as ODA component #34, TMF658-shaped
— and the design insight is that this BSS already owns the three hardest
parts of a TELCO loyalty program: a way to pay rewards in DATA (the
allowance-boost mechanic behind top-ups), a voucher engine (promotions),
and rules-as-data for tier benefits (policy). Loyalty here is mostly a
LEDGER plus wiring between engines that exist.*

## Research findings

- **TMF658 Loyalty Management** (v4.0) is the standard: loyalty program
  SPECIFICATION, program MEMBERS, loyalty ACCOUNTS with balances, RULES
  with conditioned actions, and an earn/burn TRANSACTION JOURNAL. Maps
  cleanly onto the house patterns (spec-as-data, per-tenant, evented).
- Telco loyalty differs from retail: the most-loved reward is **data,
  not points-for-toasters** (gigabytes are zero-marginal-cost,
  instantly deliverable, and churn-relevant); tier benefits are mostly
  PRICING (member pricing, waived fees); and earning follows the
  billing relationship (spend, tenure, on-time payment), not shopping
  trips.
- The honest hard part is not features — it is that **points are a
  liability**: expiry policy, earn caps, and an exportable liability
  number are governance, not garnish.

## Design — component #34: `loyalty` (TMF658)

### The ledger (the one new thing)

`loyalty` service, port next free: program spec per tenant (earn rates,
expiry months, tier thresholds — DATA, editable on the console like
policy rules), member enrollment (OPT-IN at the storefront, consent
style — never auto), loyalty account with a points balance, and an
append-only earn/burn journal (tenant_id + RLS; every movement carries
its cause: the bill id, the redemption, the expiry sweep).

### Earning — event-driven, from the streams that already flow

Kafka listeners on existing events (the campaign engine's pattern):
- `CustomerBillSettledEvent` → points per unit spent (the core earner —
  loyalty follows the BILLING relationship)
- `ProductOrderCreateEvent` (completed) → purchase bonuses per rule
- tenure tick (TickGuard-guarded) → anniversary points
- earn CAPS per window in the program spec — fraud guardrail, not garnish

### Burning — redemption mapped onto engines that exist

| Reward | Mechanism (already built) |
|---|---|
| Gigabytes | the allowance-boost path top-ups use — points → GB on this month's meter, instantly |
| Discount voucher | mint a single-use promotion code (TMF671) — burns at checkout like WELCOME10 |
| Bill credit | a labeled credit line on the next bill (billing adjustment path) |

Redemption is a POST on the loyalty API; the journal row and the
delivered reward commit together or not at all.

### Tiers — computed, with benefits as policy

Tier (e.g. bronze/silver/gold) = rolling-12-month earn vs spec
thresholds, recomputed on earn. **Tier benefits are pricing rules**: the
policy context gains `loyaltyTier` (like `organizationId` did), so
"gold members pay no activation fee" is one console-authored rule — no
loyalty-specific pricing code. `LoyaltyTierChangedEvent` feeds the
campaign engine → the congratulations journey is a normal campaign.

### Retention tie-in (the RETAIN half)

The churn engine's `ChurnRiskDetectedEvent` already exists — a campaign
can offer double-points or a free-GB redemption to at-risk members.
Loyalty gives retention a currency; the wiring is a trigger picker
entry, not new machinery.

### Channels & governance

- Storefront My page: "My points" card (balance, tier, redeem picker —
  GB / voucher / credit) + enrollment opt-in; app parity after.
- Console: program spec editor + the journal view.
- HONEST GOVERNANCE: expiry sweep (TickGuard) with journaled expiries;
  `GET .../liability` — the tenant's outstanding-points number, exportable
  (the GL itself stays ❌ in the capability map — but the number a
  finance team books is a first-class API here); earn caps enforced.

## The proof (suite #69, loyalty_test.js)

1. Opt-in enrolls; a settled bill EARNS (journal row cites the bill);
   a non-member's bill earns nothing.
2. Burn → GB: points fall, THIS month's meter rises — verified at the
   usage API, not the loyalty API's word.
3. Burn → voucher: the minted code discounts a real checkout once, and
   only once.
4. Tier: earn past the threshold → tier up → a tier-conditioned pricing
   rule prices a gold member differently; TierChangedEvent triggers a
   campaign message.
5. Expiry sweep expires old points WITH journal rows; liability endpoint
   equals balances-sum before and after.
6. Insufficient points refuse cleanly; tenant walls hold; the program
   spec is editable data (change earn rate → next bill earns at the new
   rate, no restart).

## Order of work

1. **P1 — the ledger + earn + data-burn**: service #34 (spec, member,
   account, journal, RLS), billing-event earner, GB redemption via
   allowance boost, liability endpoint, storefront card. Suite legs 1–2, 6.
2. **P2 — vouchers, tiers, policy benefits**: promotion-mint burn,
   tier computation + `loyaltyTier` in pricing context, expiry sweep.
   Legs 3–5.
3. **P3 — retention wiring + polish**: churn-offer campaign preset,
   TierChanged trigger in the console picker, app parity, capability map
   flips the row ✅ with the suite number, docs/books.

## Shipped

**Phase 1 — 2026-07-25, suite #69 green.** Component #34 `loyalty`
(TMF658, port 8114) landed: program-as-data (earn rate, GB price —
campaign:write to edit, marketer-owned), OPT-IN membership (party-bound,
never auto), the append-only journal with cause on every movement, and
the liability endpoint. EARNING: a Kafka listener on bss.billing.events
turns CustomerBillStateChangeEvent(state=settled) into points at the
program's rate — idempotent per bill (unique cause index), members only;
proven live: paula's 50 EUR bill earned 500 points. BURNING: points →
gigabytes through the outbox (LoyaltyDataRewardEvent) into the usage
service's allowance-boost mechanic (source="loyalty", idempotent per
redemption) — and the suite verifies AT THE METER (usageConsumptionReport
allowedValue rises), never on the loyalty API's word. Storefront My page
gained the "My points" card (opt-in, balance, redeem 1 GB, nb-NO). The
capability map's reddest Market & Sales row is ✅ #69 with phase-2 items
(tiers, vouchers, expiry) honestly marked ◐. Build notes: nested Spring
Data interfaces don't scan (split top-level); the outbox DDL must match
the shared entity exactly; gateway route edits need the gateway JAR
rebuilt (host-built jars). Regressions green: storefront, ocs.

**Phase 2 — 2026-07-25, suite #69 extended to seven legs, all green.**
- **Vouchers**: points mint a REAL TMF671 promotion under loyalty's OWN
  machine identity (new `bss-loyalty` client, `promotion:write` — the
  fallback identity honestly lacked it) — unique code per redemption
  (LOYAL-XXXXXXXX, program-set percent, 90-day window), valid at the
  same `checkPromotion` door WELCOME10 uses; single-use = unique code +
  the promotion component's once-per-customer redemption (a second
  redemption 409s, proven). Mint-before-debit: a failed mint burns no
  points. Storefront card gained "Redeem voucher".
- **Tiers as policy**: tier computed from the rolling-365-day earn vs
  program thresholds, recomputed on every earn/adjust;
  `LoyaltyTierChangedEvent` on the outbox. `loyaltyTier` joined the
  BILLING pricing context (fail-open machine read of the new
  `GET /tier?partyId=`) exactly as `organizationId` did — and the policy
  engine needed ZERO changes: one console-authorable rule priced gold
  −10% and left bronze alone. Guests can't fish tiers: `loyaltyTier`
  added to the indicative-path identity strip. Console gained the
  `price-loyalty-tier` preset.
- **Expiry**: TickGuard-guarded sweep (tick_lock V3) + on-demand
  `POST /expirySweep`; FIFO by arithmetic (earnedBeforeCutoff − spent,
  capped at balance), journaled per member; `expiryMonths` is program
  data, 0 = never. Proven honest: young points survived a 1-month clock.
- **Adjust**: operator goodwill/service-recovery credits
  (`POST /adjust`, campaign:write, reason REQUIRED — goodwill has a
  cause too); doubles as tier-recompute trigger.
- Build notes: machine calls need `OIDC_TOKEN_URI` backchannel env (the
  agreement template never had machine calls); policy rule `condition`
  is a JSON STRING; adjustment lines carry `label`/`ruleName`.
- On-demand bills don't reprice (recon finding) — the billing-run
  context enrichment is the wired path; a run-billed tier discount
  lands next period. Regressions green: pricing, deals, storefront.

**Phase 3 — 2026-07-25, suite #69 at nine legs, all green. The arc is
complete.**
- **Retention wiring**: the martech ear now listens on `bss.loyalty.events`
  (one topic added to the campaign consumer); `LoyaltyTierChangedEvent`
  carries relatedParty like every business event, so the generic
  extraction just worked. Suite leg 8 proves it live: a REAL tier flip
  (thresholds raised, adjust recomputes) rode the outbox into a campaign
  and landed a REAL TMF681 message in paula's inbox — the congratulations
  journey is a NORMAL campaign, no new machinery.
- **Console recipes**: the campaign form opens with retention plays that
  PREFILL the form and stay fully editable — "Churn save: a loyalty offer
  to at-risk customers" (ChurnRiskDetectedEvent; the message points at the
  points balance that already exists, honestly — no double-earn mechanic
  is promised) and "Loyalty: congratulate a tier change". Implemented as
  a reusable `recipe` field kind (a select whose options fill sibling
  controls); the trigger picker gained "Loyalty tier changed".
- **App parity**: the mobile app's adaptive Home composed the same
  My-points card (opt-in, balance + tier, redeem GB, redeem voucher) over
  the same TMF658 API — no app-only path. Suite leg 9 signs paula into
  the app and reads "…pts · gold" off her Home.
- Build note: the mobile image build died on a full Colima disk — 2.8 GB
  of Android APK outputs were riding into the build context; `android/`
  joined .dockerignore and the build cache got pruned. (Kafka had ALSO
  crash-looped on the same full disk — the outbox retried and nothing was
  lost, which is what the outbox is for.)
- Regressions green: martech, app.

The capability-map row is fully ✅ #69; loyalty left the "bring from
elsewhere" list in the map, the README and the manual. The story is
memoir chapter 51 ("The currency of staying") and manual §20.
