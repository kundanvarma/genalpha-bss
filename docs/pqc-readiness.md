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
| Data at rest | Postgres, no field-level encryption; SIM **PUKs stored plaintext** (documented dev shortcut — real operators keep card secrets in the SIM vendor's HSM domain) | ⚠️ not a quantum issue, a classical one — listed for completeness |
| Kafka / DB connections | plaintext in dev; SASL/TLS is deployment config | ➖ same story as transport |
| JVM | JDK 17 | ➖ ML-KEM (JEP 496) and ML-DSA (JEP 497) ship in **JDK 24**; BouncyCastle backports both |

## What "quantum-safe" would take, in priority order

1. **Hybrid TLS at the edge (do first — counters harvest-now-decrypt-later).**
   Terminate with `X25519MLKEM768` hybrid key exchange at the ingress/load
   balancer. Zero code changes here — it's the deployment layer, and mainstream
   ingresses/CDNs already offer it. Recorded-today-decrypted-later is the only
   quantum risk with a *present-tense* clock, and it is entirely a transport
   concern.
2. **PQC token signatures (when the IdP ecosystem lands).** The services never
   hardcode an algorithm — they follow the issuer's JWKS. The day Keycloak (or
   any OIDC provider; vendor-neutrality is the hedge) signs with **ML-DSA** and
   the JOSE registrations finalize, the migration is: IdP config + a JVM/Nimbus
   version that knows the algorithm. Prerequisite: move the build to JDK 24+
   (mechanical; the code is 17-compatible Spring Boot).
3. **Symmetric/at-rest hygiene (already adequate, one gap).** AES-256 and
   SHA-256 are PQC-fine. The one real improvement is field-encrypting SIM PUKs
   (AES-256-GCM with a KMS key) — worth doing for classical reasons anyway.
4. **Internal transport** — enable TLS on Kafka/Postgres in production values;
   same hybrid-suite guidance as the edge.

## What we deliberately do not own

Certificate hierarchies, HSMs, card-network crypto, SIM OTA keys and the
clearinghouse's transport are the IdP's, PSP's, SIM platform's and NRDB's
respective problems — each behind a seam that names a real vendor category.
A BSS that embedded its own crypto would have a PQC *rewrite* ahead of it;
this one has a PQC *procurement checklist*.

**Bottom line:** one genuinely quantum-vulnerable primitive (RSA token
signatures), one deployment decision with a deadline-ish urgency (hybrid TLS),
and an architecture whose seams make both swappable without touching business
code. That is what PQC-ready looks like for a BSS; PQC-*proof* arrives when
items 1–2 are flipped on.
