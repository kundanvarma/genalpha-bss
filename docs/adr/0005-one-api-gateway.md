# 0005 — One API gateway

**Status:** accepted, 2026-07-09 (gateway as the single entry point; browse cache and channel headers added later, recorded 2026-09-22)

## Context

Seven channels, AI shopping agents, crawlers and digital workers all reach
the same 40 components. Without one door, every component would resolve
tenants from hostnames, validate tokens for every issuer, rate-limit and
decide what a channel may see — forty times, forty ways.

## Decision

- All traffic enters through one Spring Cloud Gateway (`:8080`). Channels
  and agents never call a component directly.
- **Tokens** are validated per tenant issuer (multi-issuer resource server,
  per-tenant JWKS). Machine callers use the acting tenant's client.
- **Tenant for guests** is stamped from the `Host` header as `X-Tenant-Id`
  at highest precedence; inbound copies of the header are stripped.
- **`X-Channel`** is sent by every front end and forwarded downstream; the
  catalog enforces sellability per channel and ordering refuses what the
  channel cannot see. **`X-GenAlpha-Agent`** names the acting agent and is
  receipted by the ontology.
- **Rate limits** run in two rings (per-partner buckets for dealers, a
  fleet-wide ceiling for everyone) behind a `RateLimitStore` seam; Redis
  when shared, in-memory otherwise; an unreachable Redis fails open.
- **Per-tenant gates** live here: `agent-commerce off|discovery|full`,
  `ai-visibility` at robots.txt, crawler dual-serve by User-Agent.
- **Browse cache** (`LocalResponseCache`) is on the **catalog route only**,
  keyed by `X-Tenant-Id`, caching only token-absent `public` responses the
  catalog marks cacheable; stock, personalization, cart, order and bill are
  never cached. Freshness is the TTL, not invalidation.

## Consequences

- Costs: the gateway is a single point to scale and a place where routes
  must be registered for every new component (the ontology conformance
  suite checks this); the edge cache is per replica until a CDN sits in
  front.
- Buys: one place for tenancy, auth, fairness and channel policy; a
  campaign-day surge never reaches the JVM or Postgres for the price list.

## Enforced by

`channel_availability_test.js` (#118); the browse-cache suite (cache HIT,
tenant isolation, authenticated browse not cached); `hardening_test.js`
(#57, rate-limit resurrection); `ontology_test.js` (#125, gateway routes).

## Related

`docs/architecture.md` §1 and "The browse cache is anonymous-only",
`docs/browse-cache-plan.md`, `docs/hardening.md`, `docs/launch-governance.md`
"Channels", `docs/engineering-conventions.md` §4.
