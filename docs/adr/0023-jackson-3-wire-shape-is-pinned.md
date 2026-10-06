# 0023 — Jackson 3's wire shape is pinned, not inherited

Status: accepted, 2026-10-06 (with the Spring Boot 4 migration, #199)

## What forced it

Spring Boot 4 brings Jackson 3. Jackson 3 changed two defaults that are
visible on the wire, and Spring Boot overrides neither:

| Feature | Jackson 2 | Jackson 3 |
|---|---|---|
| `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` | disabled | **enabled** |
| `DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` | disabled | **enabled** |

Taken as they come, the first re-orders every TM Forum body this system
emits — `@type` leads, then a–z, so a payment went out as
`{"@type","amount","authorizationCode","href","id",…}` where it had been
`{"id","href","status","amount",…}`. The second turns a payload that omits
a number into a 400: `Cannot map null into type double`. TM Forum payloads
are sparse by design — a partner sends the fields it has — so that is not
an edge case, it is Tuesday.

Neither was found by reading release notes. Both were found by four
`DtoRoundTripTest` classes going red in the migration's first full reactor
run, which is the argument for having written them.

## The decision

Every component pins both defaults back, in its own
`src/main/resources/application.yml`:

```yaml
spring:
  jackson:
    mapper:
      sort-properties-alphabetically: false
    deserialization:
      fail-on-null-for-primitives: false
```

A framework upgrade does not get to change an API's observable shape. Key
order carries no meaning to a parser, but it is a contract the round-trip
tests assert deliberately ("the house keys lead the document"), the CTK
runs read, and a consumer may have come to rely on. If the shape is ever
to change, that is its own decision with its own note — not a side effect
of a version bump.

Each service declares it rather than inheriting it from a shared parent,
for the same reason each carries its own `SecurityConfig`: a component is
deployable alone, and a setting it does not state is a setting it does not
have. The test mappers in `DtoRoundTripTest` are built the same way, so a
test asserts what its component actually emits.

## What it costs

Two settings duplicated 42 times, and a standing obligation: a new
component must carry them, and anyone who deletes them must mean it.

## What holds it

`ops/arch/claims.sh`, section `wire-shape`: every
`services/*/src/main/resources/application.yml` must pin both, named per
service. Broken on purpose both ways on 2026-10-06 — a missing key and a
`true` — and seen to go red for the right service, then green restored.

## Related

- [0001](0001-tmf-open-apis-are-the-contract.md) — the standard payload is the contract
- `docs/engineering-conventions.md` — a claim is a promise the code keeps
- Issue #199 — the Spring Boot 4 migration
