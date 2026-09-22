# 0002 — One ODA component per service, one database each

**Status:** accepted, 2026-07-09 (Flyway per service, one database each; recorded as an ADR 2026-09-22)

## Context

The BSS is 40 ODA components (Spring Boot services) plus seven channels. A
shared schema would be the quickest way to join a bill to an order, and the
surest way to end up with one deployable that cannot be composed, swapped or
sold as a piece. The composer (`docs/composer.html`) and the Helm chart both
assume a component can be left out.

## Decision

- One service is one ODA component. It may serve several TMF APIs that belong
  together (usage serves TMF635/677/654; party-account serves TMF632/666/669),
  but it has one deployable, one port, one event topic, one team-sized scope.
- Each component owns its own PostgreSQL database and its own Flyway history
  (`db/migration` and `db/migration-postgresql`, shared version numbers). A
  stateless component (ontology) owns none.
- No component reads or writes another component's tables. It reaches another
  component only through that component's API (machine call under the acting
  tenant's client credentials) or its events.
- Cross-component calls go through conditional clients with Noop fallbacks so
  an absent component degrades a feature instead of breaking the caller.

## Consequences

- Costs: no cross-component joins — billing calls inventory, catalog, usage
  and promotion in turn; reference data is duplicated by id; the fleet runs
  ~28 databases on a laptop and Azure's smallest managed Postgres ran out of
  connections.
- Buys: any component can be deployed alone, replaced by a vendor's, or
  skipped in Helm; a schema change is local; the same chart ran on k3s, EKS
  and AKS without one application change.

## Enforced by

`PostgresMigrationTest` per component (real Postgres via Testcontainers);
ArchUnit `noClasses().dependOnClassesThat().resideInPackage("..other.entity..")`
where a component carries it; review for the rest. `docker-compose.yml` and
the Helm chart carry one database per service.

## Related

`docs/architecture.md` §1 (component map), `docs/engineering-conventions.md`
§3, `README.md` "The modules", `docs/composer.html`.
