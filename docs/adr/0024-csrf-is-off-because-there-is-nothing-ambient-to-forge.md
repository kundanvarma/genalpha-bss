# 0024 — CSRF is off, because there is nothing ambient to forge

Status: accepted, 2026-10-07

## What forced it

CodeQL reports `java/spring-disabled-csrf-protection`, high severity, in all
**39** components. Each `SecurityConfig` does the same thing:

```java
.csrf(csrf -> csrf.disable())
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

39 identical high-severity alerts is the largest group on the repository after
the tenant-session one (ADR 0023's neighbour, fixed in the same sweep). Left
open they do real harm: they are most of the high-severity volume, and volume
is how a genuine finding goes unnoticed — which is exactly what happened to the
`java/sql-injection` alert on the tenant wall, which sat in the same list.

## The decision

**CSRF protection stays off, and the reason is written where someone would
otherwise turn it back on.**

A cross-site request forgery works by making a victim's browser issue a request
that carries its **ambient** credentials — a session cookie the browser attaches
by itself, without the attacking page ever seeing it. The attack needs a
credential the browser volunteers.

This API has none:

- the session policy is `STATELESS` in all 39 components — there is no session
  and no session cookie;
- no `SecurityConfig` configures form login, HTTP basic, or any cookie-based
  authentication (checked, not assumed);
- the only credential is a bearer token, which a caller must attach to the
  `Authorization` header **deliberately**. A cross-site page cannot add that
  header to a request it causes a browser to make.

So there is nothing to forge. Turning CSRF on would require every channel to
fetch and replay a token it has no way to obtain, breaking all seven of them
while protecting against an attack that cannot occur.

## What it costs

A standing obligation: this reasoning holds **only while authentication stays
out of cookies**. If any component ever authenticates from a cookie, or stops
being stateless, CSRF protection stops being unnecessary and this ADR is wrong.
That is the condition to watch, and it is why the rationale is pasted into each
`SecurityConfig` rather than kept only here — the decision is most likely to be
revisited by someone reading that line, not this file.

## What holds it

- Each of the 39 `SecurityConfig` files carries the reasoning inline, next to
  the call, naming this ADR.
- The alerts are **dismissed in GitHub code scanning with this decision as the
  reason**, not suppressed in configuration and not left open. A dismissal is
  visible, attributable and reversible; an open alert nobody intends to act on
  is none of those.

## Related

- [0003](0003-multitenancy-pool-with-two-locks.md) — tenancy, and the token's role
- `docs/threat-model/` — the per-component trust boundaries
