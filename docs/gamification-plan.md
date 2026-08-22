# Engagement & gamification — the honest game

**Status:** G1 + G2 + G3 + G4 SHIPPED (2026-08-22) — see the shipped notes below · **Depends on:** loyalty points, promotion/pricing rules, the journey engine (holdouts, waitForEvent, NBA), CDP traits/audiences, gifting, family, landing pages, serviceable areas/coverage, dealer commission ledger, TMF696 risk, AI copilots + TenantVoice

> **G1 shipped:** the referral engine — stable per-customer codes, self/dup guards, velocity HOLDS, reward in DATA both sides on the joiner's first completed order, riding the loyalty reward rail; a reward arriving before the joiner's meter exists is PARKED and lands on their first usage (the suite forced that edge). Storefront "Invite a friend" card (NO included); staff report with the program's GB price tag. Suite `referral_test`.
>
> **G2 shipped:** bill-paid streaks as CDP traits (settled by the BANK's remittance, deduped per bill, milestone events for journeys, audience-targetable) + the Right-Plan Guarantee (`POST /ai/v1/fairPlay/sweep`): under-30%-of-allowance customers get the cheaper SAME-CATEGORY plan with 1.5x headroom — a bundle is never the honest answer to an oversized mobile plan. Suite `g2_honest_game_test`.
>
> **G3 shipped:** Community Grid goals (area target, public score — a percentage, never a person, unlock at target) + Klubbdugnad (code tied to a local club org; per-club season tally + leaderboard). One migration on G1's rails. Club revenue-share BOOKING shipped 2026-08-22 (H2): a rewarded, club-linked conversion publishes `ClubShareAccruedEvent` (configurable `club-share-amount`/`-currency`); revenue books DR 6150 Community sponsorship / CR 2150 Payable-to-clubs, idempotent per conversion — the season tally is a balance the operator OWES, not a scoreboard. Suite `g3_community_test`.
>
> **G4 shipped (lean):** the dealer/store leaderboard off the commission ledger (`GET /dealer/v1/leaderboard`, verified live). Honest deferrals: the reward-moment wheel UI, the level trait, and the weekly drop (the last two are journey/audience CONFIG recipes, not code — the manual says how).

## What the research says

- **Referrals are the highest-quality acquisition channel in telecom**: referred
  customers are ~16% more valuable and ~18% less likely to churn (Journal of
  Marketing, via Extole), retaining at a ~37% higher rate. A member-get-member
  engine is table stakes the platform currently lacks as a first-class object.
- **Perks beat price cuts for retention**: T-Mobile Tuesdays' weekly ritual,
  O2 Priority's no-points perks, Verizon's instant-gratification model — the
  winning pattern is a RECURRING REASON to open the operator's app that is not
  a bill. Consistency beats jackpot size.
- **Mechanics that measurably work**: streaks (with freezes), spin/scratch
  moments tied to real events, badges/levels, challenges, prediction games,
  quizzes that double as guided selling. The common failure mode: gamification
  bolted on with no measurement, decaying into coupon spam.

## The stance that differentiates this BSS: gamification with receipts

Every vendor sells spin-the-wheel. Nobody sells PROOF. This platform already
has holdouts, honest lift, attribution and an AI governance culture — so its
gamification module ships with three rules no competitor matches:

1. **Every mechanic is a journey with a holdout.** A streak, a wheel, a quest —
   each runs as a campaign/journey, so its lift (and its cost in given-away
   data) is measured against a control. The operator sees what a mechanic
   EARNS, not what it feels like.
2. **Transparent odds, no dark patterns.** Published win-rates on chance
   mechanics, no fake urgency, no loss-framing, streaks that pause without
   punishment. In a GDPR-first market, "the honest game" is a brand asset.
3. **Fraud is a risk score, not a surprise.** Referral and reward abuse runs
   through the TMF696 risk seam before payout — velocity, device, registry
   signals — so the program scales without a fraud tax.

## Catalog — table stakes, built on what exists

| Mechanic | Built on | Notes |
|---|---|---|
| **Member-get-member** | party relationship + promotion + attribution | Double-sided DATA rewards (giver + joiner); referral code = promo code with a referrer party ref; port-in doubles the reward; risk-scored payout |
| **Streaks** | event bus + journey engine + loyalty | Bill-paid-on-time streak (measurable dunning reduction), top-up streak, app-visit streak; streak-freeze inclusive by design |
| **Reward moments** | journeys + promotion + loyalty | Spin/scratch at REAL events: order completed, bill paid, anniversary, port-in complete — never random nags |
| **Levels & badges** | CDP traits + audiences | Level = a trait; every module can target by level (pricing rules, experiences, journeys); badges surface on My Page |
| **Weekly ritual drop** | partner entitlements + journeys | Tuesdays-style: partner codes minted per week per segment; the recurring reason to open the app |
| **Quizzes & guided games** | landing pages + guided questions | Plan-picker quiz = guided selling that also earns a small reward; prospect-side capture with consent |
| **Win-back games** | churn alerts + journeys | A churn-risk segment gets a "we miss you" challenge instead of a discount mail |

## The Starlink question (asked 2026-08-22, answered honestly)

LEO satellite is real competition for fixed access — but its physics point the
strategy, not kill it: Starlink is capacity-constrained by SUBSCRIBER DENSITY
(quality measurably degrades as neighbors pile onto a shared cell; analysts
place it "best suited to low-density markets" even after V3's capacity jump).
Fibre is the opposite: density is its friend. So the community mechanic gets
an anti-fragile reframe — **"every neighbor who joins makes fibre come sooner
and satellite work worse."** Where density can never support fibre, the
operator RESELLS the sky (LEO wholesale/community gateways) instead of losing
the customer: the BSS is access-agnostic, so the moat is the RELATIONSHIP,
never the medium. Engagement strategy follows: build the moats a global
satellite ISP structurally cannot copy — local community, person-to-person
value, household orchestration, and trust.

## Differentiators — what only this platform can do

1. **Community Grid (the neighborhood unlock, anti-fragile version).** Three
   legs: (a) demand aggregation where density supports fibre — live street
   progress, referral attribution, area leaderboards; (b) **connect-today
   bridge** — FWA/5G (or resold LEO) NOW, price-locked upgrade to fibre at
   unlock, loyalty and streaks carried across the swap: the game sells the
   upgrade path, not the wait; (c) the density story told straight: more
   neighbors = better fibre economics AND worse shared-cell satellite.
1b. **Klubbdugnad (grassroots revenue share) — the sky-proof moat.** The
   Norsk Tipping "Grasrotandelen" model, applied to connectivity: a customer
   ties their subscription to their LOCAL club (sports team, korps, velforening)
   and a slice of every bill funds it; referrals through the club compound it;
   club leaderboards run by season. Party orgs + attribution + the revenue
   ledger make this pure configuration — and a global satellite ISP can never
   be the brand that funds your kid's football team.
2. **Data as social currency.** Gifting already crosses households and (Ice
   model) strangers-by-number. Extend to: team data pots (a friend group's
   shared challenge balance), boost-a-friend, pay-it-forward chains with chain
   length as the leaderboard. Virality that runs on the event bus and is
   therefore fully attributable.
3. **Quests ARE journeys.** No new engine: a quest is a journey whose steps
   are waitForEvent nodes (order, activation, app event), whose conversion is
   the quest goal, and whose holdout keeps the lift honest. The growth copilot
   drafts quests in the tenant's language (TenantVoice); the simulator's
   forecast receipt prices the reward budget before launch.
3b. **Right-Plan Guarantee (fair-play as a mechanic).** The usage meters
   already know who is on the WRONG plan. The honest-machine move nobody in
   the industry dares: proactively offer the cheaper plan, gamified as the
   operator's published "fair play score" (plans right-sized this month: N).
   Short-term ARPU dip, holdout-measured churn and referral lift — trust as a
   growth loop, provable in the attribution report.
3c. **Seasonal play (the hytte rhythm).** Pause machinery exists — make
   seasonality a feature, not a fight: hibernate the cabin line with one tap,
   wake it from the app, number and streaks preserved, a "first fire of the
   season" reward moment on wake. Honest seasonal billing vs the industry's
   12-month contract reflex.
4. **Streaks that respect you.** The honest-machine version: no shame
   mechanics, streak freezes included, quiet by default, and the on-time-bill
   streak explicitly framed as "we both win" — its lift shows up as reduced
   dunning, a number the operator can already see in the ledger.
5. **Channel gamification.** The dealer commission ledger becomes a
   leaderboard + tiered kicker season for stores; the internal workforce KPI
   scoreboard already exists for care teams. Same mechanics, sell-side.
6. **Prospect-side play.** Anonymous visitors are already a consented CDP
   population: coverage-check → launch-offer wheel, quiz-to-plan, referral
   landing pages — retargeting-ready, consent-gated, measured.
7. **White-label mechanics as data.** Every mechanic above is per-tenant
   CONFIG (rewards, odds, copy, brand voice) — a hosted operator turns on
   member-get-member from the console like they edit their tagline. For the
   platform's own sales story: "gamification is a pane, not a project."

## Build plan (on trigger)

- **G1 — the referral engine** (highest ROI, research-backed): referral code
  object (party-linked promo), double-sided data reward via loyalty, port-in
  bonus, TMF696 risk-scored payout, attribution + holdout report, share links
  on My Page + storefront, console pane. One suite proves: refer → join →
  port-in → both meters credited → lift report shows the cohort.
- **G2 — streaks + reward moments**: streak trait engine on the event bus
  (bill-paid, top-up), reward-moment journeys (spin at order-completed with
  published odds), My Page streak card.
- **G3 — Community Grid**: area goal object on serviceable areas, public
  progress page per area, referral wiring from G1, area leaderboard, the
  bridge-product upgrade path (price-locked swap order). Klubbdugnad rides
  the same rails: club org + revenue-share ledger entry + club leaderboard.
- **G4 — levels/season + weekly drop + channel leaderboards.**

Every phase ships with the standing doctrine: holdout-measured, odds
published, risk-scored, per-tenant config, suite-proven.
