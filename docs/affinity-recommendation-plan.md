# Item-to-item affinity — "customers who bought this also bought" — plan

*2026-07-22. The recommendation component ranks a personalized rail for a
KNOWN customer ("for you"). The missing piece is item-to-item: on a
product page, "customers who bought THIS also bought THAT" — market-basket
co-purchase, the iPhone→case signal. It lives where the data already is
(the recommendation component reads tenant-wide inventory), reveals only
aggregates, and shows to everyone on the offering page.*

## Design

**`GET /tmf-api/recommendationManagement/v4/affinity?forOfferingId=X`** —
PUBLIC (guests browse product pages; the answer is aggregate, no PII).

**The computation** (`AffinityRecommender`, cached per tenant, 60s TTL like
the popularity ranker):
1. `inventory.allProducts()` — every owned product, with its owner
   (relatedParty role=customer) and its offering.
2. Group into baskets: `owner → set of offering ids`.
3. For offering X: the cohort is owners whose basket contains X; tally the
   OTHER offerings across that cohort.
4. **Minimum support** (default 2): an offering must be co-owned by at
   least two of X's owners to appear — signal over noise, and a privacy
   floor (a single customer's basket can't be reverse-engineered).
5. Rank by co-owner count, top 4, resolve names from the catalog.
6. Response: `[{ offering: {id, name}, coOwners: N }]`.

**The storefront**: a "Customers who bought this also bought" rail on the
offering detail page — public fetch, fail-soft to no section, i18n'd.

## The proof (suite #63, affinity_test.js)
1. Seed a deterministic co-purchase pattern: three customers each own
   offering A **and** offering B; a fourth owns A and a lonely C.
2. `affinity?forOfferingId=A` returns B (coOwners 3) and NOT C
   (below min support) — recency of the market basket, honestly counted.
3. The offering page for A shows the "also bought" rail with B.
4. Tenant wall: nova's affinity never sees genalpha's baskets.
5. Cold offering (nobody owns it) → empty, no crash.

## Order of work
1. AffinityRecommender + controller endpoint + public security matcher.
2. Storefront api.js `alsoBought()` + Offering.jsx rail + i18n.
3. Suite #63; regressions (csr, storefront, personalization); docs, commit.

## Shipped

Suite #63 (`ops/e2e/affinity_test.js`) green: deterministic co-purchase
seeded on UNIQUE per-run offering ids (so real co-ownership can't skew the
min-support assertion) — three customers own phone+case, one owns
phone+solo; affinity(phone) returns the case (co-owners 3) and NOT the
solo (below support 2); public to a guest; the real Samsung page renders
4 genuine co-purchase links; tenant-walled (nova reached via its hostname,
not a header — the gateway rewrites X-Tenant-Id from the host).

Landed: `AffinityRecommender` (baskets by owner, min-support 2, per-tenant
cache), `GET /affinity?forOfferingId=` (public — aggregate only), the
storefront "Customers who bought this also bought" rail on the offering
page (nb-NO), and a real bug fix: `allProducts()` was capped at one 500-row
page while the tenant had 2,211 products — now pages through ALL of them,
which fixed affinity AND sharpened the shared popularity ranker. The demo
data's honest signal: Samsung buyers also take GenAlpha One 157×.
