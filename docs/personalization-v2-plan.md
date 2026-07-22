# Personalization v2 — the individualized shop — plan

*2026-07-22. The original arc (docs/personalization-plan.md) delivered the
consent spine, guest experience rules, the GA4 seam, segments, NBO at the
CSR desk, A/B and attribution — suites #34–40. v2 turns the machinery
toward the SIGNED-IN customer's own storefront: a "For you" rail that is
genuinely theirs — ranked from their data, captioned by the governed LLM,
churn-aware, consent-honest, and budget-safe under yesterday's AI control
plane.*

## Design — generalize NBO from one-offer-for-the-agent to a rail-for-me

**`GET /ai/v1/forYou`** on intelligence — SELF-scoped: the party is the
token's own subject, never a parameter (a customer can only individualize
their own shop; the CSR desk keeps its existing NBO). The matcher carves
this one path out of `ai:use` to plain `authenticated` — customers don't
carry staff AI roles, and shouldn't.

**The fusion** (receipts before advice, as always):
1. Candidates: TMF680 recommendations for the party (already fused with
   consented insight interests by the earlier arc — the ranking IS the
   personalization arithmetic).
2. Context: interests (insight, consent-gated — empty when not consented),
   holdings (inventory), and an open churn-risk alert if one exists.
3. The rail: top candidates in TMF680 order; a `retentionFlag` when churn
   risk is open (the shop shows a loyalty banner — the offer to KEEP a
   customer is personalization too).
4. The caption: ONE governed FAST call ("CAPTION: <one warm sentence>"
   contract, stub-deterministic) grounding the rail in the customer's own
   interests/holdings — metered, budgeted, kill-switchable by the control
   plane; fail-open to no caption. A 5-minute per-party cache keeps a
   browsing session from burning budget per page view.

**The storefront**: a "For you" rail on the shop for signed-in customers —
ranked cards, the AI caption, the retention banner when flagged; i18n'd;
consent-honest (no personalization consent → the rail is plain BSS-data
recommendations with no browsing-derived caption).

## The proof (suite #60, for_you_test.js)
1. Two customers with different consented browsing → DIFFERENT rails, and
   the caption reflects the interest ("devices").
2. A customer who rejected personalization → rail without browsing-derived
   content; zero interests on the response.
3. The caption is a metered, governed call (a ledger row lands).
4. Tenant wall: nova's rail never leaks genalpha offerings.
5. The storefront renders rail + caption ([data-testid=foryou-*]).

## Order of work
1. ForYouService + controller + security carve-out + stub CAPTION branch.
2. Storefront rail (api.js + Shop.jsx + i18n).
3. Suite #60; regressions (personalization #34, csr, model_routing);
   docs (README/book/manual/plan close-out), commit.

## Shipped

Suite #60 (`ops/e2e/for_you_test.js`) green on its FIRST run — all five
checks: Alice's consented device-browsing became interests and a grounded
caption ("Picked for you after your look at devices"); Bob (no consent)
got zero interests and a caption that borrows nothing; the caption landed
metered on the control-plane ledger (100 tokens / 200 micros); the second
call served from cache; and the storefront rendered caption + rail.
Regressions green: personalization (#34), storefront, ai_control_plane.
Landed: ForYouService + self-scoped controller + security carve-out
(authenticated, not ai:use — customers carry no staff roles), the stub's
deterministic CAPTION branch, the storefront rail with i18n (nb-NO), and
a churn-alert-by-party finder for the retention flag.

## Third wave — copilot-authored experience rules (same day)

Suite #61 (`ops/e2e/copilot_experience_test.js`) green: the owner CHATS
the rule ("what should device-browsing guests see"), the proposal card
shows it beside specs/prices, one click makes it a policy row (domain
`personalization`, owner's token, model never writes), a brand-new
consenting guest is greeted by the chatted banner over the chatted pin,
and the rule deletes as data. Landed: `experienceRules` in the copilot
contract + stub scenario, console validator/card/executor (mirrors the
pricingRules pattern, rollback included). Suite lesson: a `fail()` that
calls `process.exit` skips cleanup — throw instead, so the finally-side
cleanup always runs. Regressions green: copilot, personalization.
Remaining idea on this shelf: next-hit/session-decay.

## Fourth wave — next-hit / session personalization (same day)

Suite #62 (`ops/e2e/next_hit_test.js`) green: page N shapes page N+1.
The event stream already carried timestamped offering views; the fix was
to read them by RECENCY, not frequency. insight `experience()` now
computes a session window (`bss.insight.session-seconds`, default 1800),
returns the most-recent category as `heroCategory` (recency overrides the
all-time favourite) and the last ~4 distinct offerings as
`recentOfferings`; the storefront renders a "Pick up where you left off"
rail (nb-NO: "Fortsett der du slapp"). Proof is a reversal — phones
viewed twice, a plan once last, and the next page leads with the plan.
Consent-gated, tenant-walled. Regressions green: personalization,
for_you, copilot_experience. THE PERSONALIZATION SHELF IS NOW EMPTY —
individualized shop (web+app), chat-authored rules, and next-hit all
shipped.
