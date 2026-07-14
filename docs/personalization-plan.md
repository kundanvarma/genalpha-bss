# Personalization — research and plan

Status: **P1 delivered** (insight component #33, consent spine, guest personalization, experience rules, login stitch — suite #34 `personalization_test.js`) · P2 delivered (per-tenant GA4 seam via Measurement Protocol + mock-analytics; TMF680 interest fusion) · P3 (audience import, social APIs, NBO) pending · 2026-07-14

The ask: personalize pages and offers — for **anonymous visitors** using web
analytics / social signals (Google Analytics etc.), and for **known
customers** by fusing those signals with what the BSS already knows. This
plan starts from how the field actually builds it, then maps it onto our
seams-and-rules architecture.

---

## 1. What the field does

**The shape is always the same — a CDP pipeline:** collect events → resolve
identity → unified profile → segments → a decisioning step → the experience.
Adobe's Real-Time CDP feeds Target for "next-hit" personalization (segment
membership computed on one page changes the next page); Salesforce pushes
segment membership back into CRM/service so every channel sees the same
picture; "next best offer" is a decisioning engine over profile + context.

**GA4 is a poor man's CDP, and that's useful.** Every GA4 property exports
raw events to BigQuery free; the **Measurement Protocol** accepts
server-side events IN; the Data/Audience Export APIs let audiences flow OUT
to other systems. So an operator that lives in GA4 can meet us at an API.

**Consent is a design input, not an afterthought.** First-party analytics
can be consent-light; anything third-party (GA included) needs explicit,
purpose-separated consent — analytics and personalization consented
independently, reject as prominent as accept, nothing pre-ticked, and no
data collected before the choice. Several DPAs have found even the IAB's
own TCF wanting — the safe pattern is first-party collection with explicit
purpose gates.

## 2. Design for genalpha-bss

**The stance: we don't become a CDP — we become CDP-ready.** The operator
brings their own analytics (GA4 today, Matomo/Snowplow tomorrow) through a
**seam**, exactly like PIM and OCS. What we own is small and load-bearing:
a first-party insight profile, a consent spine, and decisioning through the
engines we already have.

### Component #33: `insight`
- **Collector**: `POST /insight/v1/event` — page/offering views, searches,
  campaign UTM. Anonymous visitors get a first-party `visitorId` cookie;
  signed-in customers ride their party id. **Nothing is collected before
  consent.**
- **Profile**: per visitor/party — interests (categories viewed), campaign
  source, consent flags, segment tags. On login, the visitor profile
  **stitches** to the party — the CDP identity-resolution moment — only
  under personalization consent.
- **The analytics seam** (per-tenant registry, like the AI seam):
  `ANALYTICS_PROVIDER=internal|ga4`. In `ga4` mode events forward to the
  tenant's GA4 property via Measurement Protocol (measurement id + API
  secret per tenant), and GA4 audience memberships can flow back as segment
  tags. A `mock-analytics` container plays the GA role in dev, as mock-pim
  and mock-ocs do. Social signals start as UTM attribution (the honest 90%);
  platform APIs are a later bolt-on.

### Decisioning = the engines we already have
- **Policy** grows a fourth rules-as-data family, domain `personalization`:
  segment/interest → experience mappings, authored in the console
  ("interest=Devices → hero shows devices + the colour campaign teaser").
  Same JSON-logic, same no-redeploy story, copilot-authorable.
- **Recommendation (TMF680)** already ranks offers for known customers from
  BSS data; it gains the insight profile (interests, campaign) as context.
- **Intelligence** already snapshots churn features per customer — next-best-
  offer scoring reuses that machinery later.

### Channels
The storefront home asks one question — "what should this person see?" —
and renders the answer: hero category, teaser offerings, banner copy.
Guests get interest-driven rails; known customers get TMF680 enriched with
their fused profile. The **consent banner** is part of the product: two
purposes (analytics / personalization), reject as loud as accept, and the
E2E asserts that a rejecting visitor generates zero events and sees the
default page.

## 3. Phases

**P1 — first-party loop (one session):** insight component (collector,
profile, consent), storefront banner + beacons, guest personalization via
`personalization` policy rules. E2E #34: consenting guest browses devices →
home reorders; rejecting guest → no events, default page; tenant walls.

**P2 — fusion + the GA4 seam (one session):** login stitching, TMF680
context enrichment for known customers, Measurement Protocol forwarding +
mock-analytics proving bring-your-own-analytics per tenant (nova on "GA4",
genalpha internal — same binary).

**P3 — later:** GA4 audience import, social platform APIs, NBO scoring in
intelligence, copilot-authored experience rules, book/film chapter.

**What we deliberately don't build:** our own BigQuery, our own ML stack,
or TCF ad-tech plumbing — the operator's CDP/ads stack plugs into the seam
instead of being reimplemented inside a BSS.
