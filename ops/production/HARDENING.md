# Production hardening checklist (Phase 4)

**Status of the dev defaults:** `POSTGRES_PASSWORD` and
`KEYCLOAK_ADMIN_PASSWORD` are already env-parameterized (dev falls back to
`postgres`/`admin`). Machine client secrets are dev-fixed in compose AND in
the Keycloak realm files — production must regenerate both sides.

## Before first customer data
1. **Secrets**: copy `production.env.template` → `production.env`, fill every
   CHANGE_ME (`openssl rand -base64 24`). Run `./check_hardening.sh` — it
   refuses CHANGE_ME, empty values, and the dev defaults.
2. **Keycloak**: delete the `demo/demo` users in every realm or set real
   passwords; regenerate every confidential client secret; disable
   registration on staff realms.
3. **TLS**: terminate at a reverse proxy (Caddy auto-TLS or the cloud LB).
   Nothing listens on plain HTTP from the internet. Internal traffic stays on
   the private network/VPC.
4. **Exposure**: publish ONLY the proxy's 443. Postgres, Kafka, Keycloak
   admin (8085 admin console), and every service port stay unpublished —
   admin access via Tailscale/VPN only.
5. **Restart policy**: `restart: unless-stopped` everywhere (already set in
   the dev compose); pair with host monitoring (Phase 6 alert rules).
6. **Rotation**: machine secrets and the KC admin password rotate on a
   calendar; `POSTGRES_PASSWORD` rotation requires a coordinated restart.

## Explicitly NOT done by this phase
- No secrets manager integration (Vault/SSM) — the env file + restricted
  filesystem permissions is the honest small-deployment posture; graduate to
  a manager when a second operator joins the box.
- No internal mTLS — single-host deployments rely on the host boundary.
