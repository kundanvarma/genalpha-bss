# Browse-path caching — serving the shop to everyone on a campaign day

*Design doc (#196). On a Black Friday surge, thousands of anonymous prospects hit
the same catalog pages. Today every one of those requests goes gateway → catalog
JVM → Postgres (RLS). This caches the anonymous browse responses so the surge is
absorbed at the edge, not by the database.*

## The problem

A shop page fans out to many catalog GETs — `productOffering` (list),
`productOffering/{id}`, `productSpecification/{id}`, `productOfferingPrice` (list) —
and they are **identical for every anonymous visitor of a tenant**. There is **no
caching anywhere today** (greenfield). Baseline anonymous browse: ~680 req/s on a
laptop, all the way to Postgres. A campaign day multiplies that against a price list
that changes maybe once a day.

## The design (tenant-safe, anonymous-only)

Two layers, belt-and-suspenders:

1. **Catalog emits the caching contract.** On the anonymous browse GETs the catalog
   sets `Cache-Control: public, max-age=N` and `Vary: X-Tenant-Id`. When a request
   carries an `Authorization` header (a logged-in visitor) it sets `Cache-Control:
   no-store` instead — so an authenticated response can never populate the shared
   cache, and the anonymous cache is keyed purely by tenant.

2. **Gateway caches at the edge.** Spring Cloud Gateway `LocalResponseCache`
   (Caffeine) on the `product-catalog` route caches those `public` responses. The
   `TenantHostFilter` stamps `X-Tenant-Id` from the Host at highest precedence, and
   the cache keys by it (via the response's `Vary`), so **genalpha's catalog is never
   served to nova**. Non-cacheable (`no-store`) and non-GET responses pass straight
   through.

### Why this is safe
- **Tenant isolation:** the key includes `X-Tenant-Id`; RLS returns different rows
  per tenant, and the cache respects that boundary.
- **No stale personalization:** the catalog GETs are not personalized —
  personalization is a *separate* per-visitor call (`/insight/experience`), never
  cached. Only the pure, tenant-anonymous price list is shared.
- **Auth-safe:** only token-absent responses are cached; a logged-in browse gets
  `no-store`.
- **Freshness:** short TTL (30–120 s) needs no active invalidation and bounds
  staleness; the catalog changes rarely (writes need `catalog:write`).

### Deliberately NOT cached
- `productStock` availability ("only N left") — volatile, must stay honest.
- `/insight/experience`, `/ai/**`, authenticated recommendations, cart — per-visitor.

## Phases (each built + proven)

**P1 — Catalog caching contract.** A filter on the browse GETs that sets
`Cache-Control` + `Vary: X-Tenant-Id` (public when anonymous, no-store when a token
is present), TTL a config dial.

**P2 — Gateway edge cache.** `LocalResponseCache` (Caffeine) on the catalog route,
keyed by tenant, honouring the catalog's `Cache-Control`.

**P3 — Proof.** A suite proving: a second identical anonymous request is a cache HIT
(served without touching the catalog); genalpha and nova get isolated catalogs; an
authenticated browse is NOT cached; and a load run shows the surge lift.

## Boundary (honest)
This is an in-process edge cache (per gateway replica). A production campaign would
add a CDN in front — the `Cache-Control`/`Vary` contract this ships is exactly what a
CDN consumes, so that step is a deployment change, not a rebuild.
