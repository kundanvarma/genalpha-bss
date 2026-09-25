# 0021 — TMF634 resource catalog is served by the product-catalog component, not a component of its own

**Status:** accepted, 2026-09-25 (catalog-to-provisioning step 2)

## Context

ODA draws Product Catalog, Service Catalog and Resource Catalog as three
components. This BSS already serves TMF620 and TMF633 from one component,
`product-catalog`, and step 2 of the catalog-to-provisioning arc needs a
TMF634 `ResourceSpecification` for the resource-facing services to point
at. A buyer's architect reads the decomposition off the standard APIs; what
deployable answers is invisible to them. Meanwhile the laptop fleet stands
at 66 containers on a 21 GB VM, and every new JVM is a service somebody
else has to stop.

## Decision

- `product-catalog` serves TMF634 on the standard path
  (`/tmf-api/resourceCatalogManagement/v4/resourceSpecification`), routed
  through the gateway exactly like the service catalog, in the same
  database with the same row-level-security shape.
- The resource specification names a **seam**, never a vendor; the tenant
  configuration keeps choosing the vendor behind each seam. That keeps the
  three catalog layers vendor-neutral even though they share a deployable.
- The seam for extraction is the API path: a later `resource-catalog`
  component takes the TMF634 route and its tables and nothing else moves.

## Considered options

- **A separate `resource-catalog` component** — the ODA picture. Rejected
  for now: one more JVM, database and topic for a resource that is read a
  few times per order, and a component boundary nobody outside the repo can
  observe. Conformance is about the wire, and the wire is standard either
  way.
- **Resource specs as characteristics on the RFS** (no TMF634 at all).
  Rejected: it hides the resource layer from the standard API, which is the
  thing the arc exists to expose.

## Consequences

- The README's component count does not grow with this arc; the CTK
  scorecard can grow (a TMF634 kit run is a stretch goal, with the claims
  gate updated alongside).
- Anyone reading ODA's component map against `docker compose ps` will find
  three catalogs in one box; this record is why.
- Extraction later is mechanical, not a redesign, as long as no code outside
  `product-catalog` reaches the resource tables except over TMF634.

## Enforced by

- The gateway's route table (`services/gateway/src/main/resources/application.yml`):
  `/tmf-api/resourceCatalogManagement/**` points at the catalog URL, beside
  the service-catalog route; the fresh-install check in `ops/arch/claims.sh`
  boots that configuration on every pull request.
- product-catalog's `PostgresMigrationTest` (the resource tables and their
  row-level-security policy migrate on real Postgres) and
  `ops/security/rls_check.py` against the live fleet on every pull request.
- ADR 0002 (one component, one database) is what keeps other components off
  the resource tables: they reach them over TMF634 or not at all. Held by
  review; the day a second component needs the tables directly is the day
  the extraction happens.

## Related

- `docs/catalog-to-provisioning.md` — the arc, its steps and honest limits.
- ADR 0001 (TM Forum Open APIs are the contract), ADR 0006 (seams for
  vendor systems: the resource specification names a seam, `tenants.yml`
  names the vendor).
