# 0003 — Multitenancy: pooled tables, one issuer per tenant, hostname routes guests

**Status:** accepted, 2026-07-10 (phase 1 piloted in product-catalog; phases 2–4 across the fleet 2026-07-11)

## Context

Two operators must run on one deployment — the pitch is "onboarding an MVNO
is a form, not a project". Silo-per-tenant (a database or a fleet per
operator) is simple but does not scale to a form; a tenant column trusted
from a request header is a form that leaks. The tenant has to come from
something the caller cannot forge.

## Decision

- **Pool model.** Every domain table carries `tenant_id`. There is no
  database, schema or fleet per tenant.
- **Identity.** Tenant = the verified OIDC issuer of the token. Each tenant
  is a realm (Keycloak in dev; a Cognito pool or Entra tenant in production
  works the same). Never a claim a user could edit.
- **Two locks on data.** Every query carries the tenant predicate in code,
  and PostgreSQL Row-Level Security makes predicate-free SQL tenant-safe:
  services run as restricted roles, `SET app.tenant_id` per connection, no
  tenant means zero rows, `__system__` for sweepers.
- **Guests.** Anonymous traffic gets its tenant from the hostname at the
  gateway (`X-Tenant-Id` stamped, inbound copies stripped). Resolution order
  in every service: issuer, then header, then default (`TenantScope`).
- Machine-to-machine calls use the *acting tenant's* client credentials.
- Cross-tenant access reads as **404, never 403**. The one deliberate
  cross-tenant path (wholesale seeker to provider) runs through the gateway
  and `X-Tenant-Id`, never around RLS.
- The same pattern stacks: tenant → org (`org` claim) → party.

## Consequences

- Costs: an RLS migration on every new table; a restricted role per service;
  every consumer must act as the envelope's tenant; realm ids collide (409)
  when a realm is re-created; a wrapped test must set the session tenant.
- Buys: a new operator is a realm, a hostname and a `tenants.yml` entry; a
  forgotten `WHERE tenant_id` is caught by the database, not by a customer.

## Enforced by

RLS proofs in `mvn test` per component; `tenant_test.js` (second operator
live, isolation proven in the browser); `PostgresMigrationTest` fails a table
without RLS; the gateway `TenantHostFilter`.

## Related

`docs/architecture.md` §2 (tenancy view), `README.md` "Multitenancy",
`docs/engineering-conventions.md` §3, `infra/tenants/tenants.yml`.
