# Post-quantum readiness — an honest inventory

**Verdict: not PQC-proof today — but PQC-*ready* by construction.** The BSS
contains almost no cryptography of its own: identity, transport and payment
crypto all live behind seams. That is exactly the posture NIST's migration
guidance asks for ("crypto-agility"), because becoming post-quantum safe means
swapping what the seams point at, not rewriting the system.

## The crypto inventory (what actually exists, and its quantum status)

| Where | What | Quantum status |
|---|---|---|
| OIDC access tokens | Keycloak signs JWTs, default **RS256** (RSA-2048); every service verifies via the issuer's JWKS | ❌ RSA falls to Shor. **The** vulnerable primitive in the stack |
| Transport | Dev/demo is plain HTTP; production TLS is the platform's (ingress/LB) — nothing in the repo pins TLS config | ❌/➖ classical ECDHE today; **harvest-now-decrypt-later** applies to recorded traffic |
| PKCE (all channels) | `S256` — SHA-256 challenge | ✅ hash-based; Grover only halves the margin, SHA-256 remains fine |
| Our own code | `SecureRandom` for PUKs, invite passwords, entitlement codes; no bespoke ciphers, no RSA/EC anywhere in `services/` | ✅ nothing to migrate |
| Passwords | Keycloak's server-side hashing (pbkdf2/argon2) | ✅ symmetric/hash |
| Payment | PSP seam; the vault stores presentation data only (brand, last4, expiry) — never the PAN | ✅ real card crypto is deliberately the PSP's problem |
| Data at rest | Postgres; SIM PUKs **AES-256-GCM field-encrypted** (ICCID as AAD, key from the environment/KMS; legacy rows self-upgrade) | ✅ closed — and AES-256 is PQ-comfortable |
| Kafka / DB connections | plaintext in dev; SASL/TLS is deployment config | ➖ same story as transport |
| JVM | **Java 25 LTS across the fleet** (Boot 3.5) — ML-KEM (JEP 496) and ML-DSA (JEP 497) in the standard library | ✅ the runtime already speaks post-quantum; verified by the full 21-suite regression sweep |

## What "quantum-safe" would take, in priority order

1. **Hybrid TLS at the edge — ✅ SHIPPED as deployment config.** The Helm chart
   now carries an Ingress (TLS 1.3 only) and
   `deploy/helm/genalpha-bss/files/ingress-nginx-pqc.yaml` — controller values
   enabling `X25519MLKEM768` hybrid key exchange, classical fallback preserved.
   Recorded-today-decrypted-later was the only quantum risk with a
   *present-tense* clock; it is now a `helm -f` away in any deployment.
2. **PQC token signatures (when the IdP ecosystem lands).** The services never
   hardcode an algorithm — they follow the issuer's JWKS. The day Keycloak (or
   any OIDC provider; vendor-neutrality is the hedge) signs with **ML-DSA** and
   the JOSE registrations finalize, the migration is IdP configuration — the
   prerequisite is ✅ DONE: the fleet runs Java 25 LTS (Boot 3.5.7), which
   carries the NIST algorithms in the standard library.
3. **Symmetric/at-rest hygiene — ✅ gap CLOSED.** AES-256 and SHA-256 were
   already PQC-fine; SIM PUKs are now field-encrypted at rest —
   **AES-256-GCM**, fresh IV per value, the ICCID bound in as authenticated
   data, key from `BSS_SIM_PUK_KEY` (KMS/secret store in production, a loudly
   labelled dev fallback otherwise). Legacy plaintext rows upgrade themselves
   on their next reveal.
4. **Internal transport** — enable TLS on Kafka/Postgres in production values;
   same hybrid-suite guidance as the edge.

## What we deliberately do not own

Certificate hierarchies, HSMs, card-network crypto, SIM OTA keys and the
clearinghouse's transport are the IdP's, PSP's, SIM platform's and NRDB's
respective problems — each behind a seam that names a real vendor category.
A BSS that embedded its own crypto would have a PQC *rewrite* ahead of it;
this one has a PQC *procurement checklist*.

**Bottom line:** everything that was ours to do is done — quantum-safe edge
config, encrypted card secrets, and a fleet on a post-quantum-capable Java 25
LTS runtime, all verified by the full regression sweep. What remains is one
quantum-vulnerable primitive that is deliberately not ours: the IdP's RSA
token signatures, swappable at the identity seam the day the OIDC ecosystem
signs with ML-DSA — a configuration change, not a rewrite. That is a
defensible "PQC-ready BSS" claim, and this document is the receipt behind it.
