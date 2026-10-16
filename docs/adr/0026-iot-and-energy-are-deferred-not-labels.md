# 0026 — `iot` and `energy` are deferred, not added as labels

Status: accepted, 2026-10-16 — closes the last step of #143 (tracked as #277).
Superseding it is how either family gets built; see "What reopens it".

## What forced it

The service-taxonomy research (`docs/service-taxonomy-research.md`) found four
provisioning shapes with no fulfilment family. Two were cheap and are built
(`cpe` words, `voice` words, #267). The other two were left with one line:
*"Decide `iot` and `energy` deliberately. Both are real markets and neither is
a weekend. They belong in a spec of their own, not smuggled in as labels."*

A family is not a word. It is a promise that the orchestrator can provision the
thing — a CFS, a seam, rating. `CatalogClient.java` holds the eight families
the orchestrator honours; the desk has a word for each, and the claims gate
keeps those two lists together. A ninth family added as a label would pass
every screen and fulfil nothing.

## The decision

**Neither is built now. Both are deferred, by name, with a trigger.**

| Family | The shape | Today | Reopens when |
|---|---|---|---|
| `iot` | bulk SIMs, often no dialable number, rated per fleet, managed as a fleet | a watch or tracker is sold and provisioned as a `mobile` line — one human, one number. That is a simplification, and it is true for the products in the catalog | a tenant sells **data-only or no-MSISDN SIMs, or SIMs by the hundred to one owner** — the point where "one human, one number" stops being true |
| `energy` | register a meter point with the grid operator; rate consumption | nothing resembles it; no tenant sells it | a tenant **asks to sell electricity**. The step-3 design already sketches it as "data plus three adapters" (`docs/catalog-to-provisioning-step3-design.md`, sequence 6) |

No retail category ("Connected things", "Energy") is created either: the
research is explicit that a shelf with no family behind it is worse than none.

## What holds it

- **The claims gate, `family-taxonomies`** (`ops/arch/claims.sh`): it now READS
  the family set from `CatalogClient.java` instead of a copy of eight names, so
  any family added there must also get a desk word — the copy was blind to a
  ninth. And while this ADR is not superseded, `iot` or `energy` appearing in
  that set fails the gate: building one starts by superseding this record.
- **Launch readiness** (#281): an offering with no category cannot launch where
  launch governance is on — so nothing reaches the shelf as "Service" while
  these families wait.

## What it costs

Smartwatches stay `mobile` lines, with a number and a mobile rating, and a fleet
customer cannot be served well. That is the honest limit of the catalog today,
not a hidden one.

## Related

`docs/service-taxonomy-research.md` · #143 · #267 · #277 · #281 · ADR 0014
(launch governance) · ADR 0006 (seams: an energy grid operator would be one).
