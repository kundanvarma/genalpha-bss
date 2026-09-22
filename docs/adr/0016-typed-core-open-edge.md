# 0016 — Typed core, open edge

**Status:** accepted, 2026-09-22. Replaces the earlier de-facto practice.

## Context

Until this date the services passed `Map<String, Object>` through their
public methods — 605 public untyped returns across the fleet at the
baseline count. It was fast to write and the suites kept it correct, but it
hides exactly the class of error a compiler catches (in typed languages
most of what breaks in generated code is a type error), and it gives an
agent editing a service nothing to read but the tests. This was not a
decision anyone took; it was the path of least resistance, and a
developer's review named it. This ADR says so honestly.

## Decision

- A service's public methods take and return **records or domain classes**,
  never `Map<String, Object>`.
- TM Forum payloads are parsed **once at the wire** into typed DTOs behind a
  mapper per resource. Polymorphism rides `@type` with Jackson
  `@JsonTypeInfo` and a `defaultImpl`; unknown fields land in an
  `extensions` map (`@JsonAnySetter`) so they round-trip. That is the open
  edge; nothing behind the mapper touches raw JSON.
- `Characteristic.value` and extension blocks stay open (`Object` /
  `JsonNode`) — nobody types those beyond an open value, and the standard
  does not either.
- Domain records are exhaustive: sealed interfaces where a `@type` has
  finitely many house meanings; `switch` without `default`.
- **Migration by ratchet, not rewrite.** The count of untyped public
  returns per service may only fall from `ops/arch/baseline.json`. Type the
  controller-to-service boundary first, then the components the demos lean
  on (catalog, ordering, ontology), then the rest arc by arc; every batch
  ends with its suites green. No rewrite arcs.

## Consequences

- Costs: a DTO and a mapper per resource; more files; a batch of typing
  work inside every feature arc until the baseline reaches zero; the
  `extensions` map means a typo in a house field is not a compile error.
- Buys: the compiler is the cheapest, earliest check an agent can run; a
  wrong field name fails the build instead of a suite forty minutes later;
  the public claim "proven by suites, not by its type system" can be
  retired one service at a time.

## Enforced by

`ops/arch/ratchet.sh` (exit 2 on regression; pre-commit and edit hook via
`ops/arch/post-edit-check.sh`); a DTO round-trip test per component;
the compiler for sealed types; `ops/arch/baseline.json` may only be
rewritten deliberately and reviewed.

## Related

`docs/engineering-conventions.md` §1, `docs/engineering-principles-agent-era.md`.
