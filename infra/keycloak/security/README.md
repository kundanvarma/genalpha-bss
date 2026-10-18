# Realm security settings (#320)

The one source of truth for every tenant's sign-in rules. `ops/keycloak/converge.sh`
applies them to every realm on each fleet start and box install, and onboarding
applies them to a new realm the moment it is made. The realm fixture files do not
carry them: a realm file holding a policy AND short plain demo passwords is
refused whole, while a policy added to an existing realm keeps every existing
password working (both probed, see #320).

- `password-policy` — NIST SP 800-63B rev 4: at least 15 characters (a password
  is the customer's only factor), 128 allowed, not the username or email; no
  composition rules and no expiry, which the standard forbids. The claims gate
  refuses a policy that breaks those rules.
- `blocklist/` — the common passwords refused whole (`passwordBlacklist`, #325): the
  NCSC top 100k plus the tenants' brand words. Built into the fleet's own Keycloak
  image by `infra/keycloak/Dockerfile`; provenance and licence in `blocklist/SOURCE.md`.
- `messages-<locale>.json` — the plain-language words Keycloak's stock pages
  show for each rule, per realm locale (en, no).
