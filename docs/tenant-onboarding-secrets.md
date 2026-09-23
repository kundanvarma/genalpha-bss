# Tenant onboarding and machine credentials

*Threat model gap 02-#1, closed for NEW operators on 2026-09-23. Existing
realms are untouched and still share credentials — see "What is still
broken" below.*

## What the bug was

Onboarding an operator cloned `infra/keycloak/nova-realm.json` into a new
Keycloak realm. The clone was verbatim apart from the realm name, the
channel hostnames and the object ids:

```python
# ops/onboard-tenant.sh, before
def strip_ids(node):
    if isinstance(node, dict):
        node.pop('id', None)
        node.pop('containerId', None)
```

```java
// TenantOnboardingService.realmClone, before
ArrayNode users = JSON.createArrayNode();
for (JsonNode u : realm.withArray("users")) {
    String username = u.path("username").asText();
    if ("demo".equals(username) || username.startsWith("service-account-")) {
        users.add(u);
    }
}
realm.set("users", users);
stripIds(realm);
```

Neither path touched the template's `"secret"` fields. The template
carries **31 clients, 24 of them confidential with a literal secret**:

```json
{ "clientId": "bss-billing", "publicClient": false,
  "secret": "<a short literal, one per component>",
  "serviceAccountsEnabled": true }
```

Those same literals are the fleet's compose env, one
`OIDC_CLIENT_SECRET` per component, verbatim. So every
onboarded realm ended up with the *same 24 machine-client secrets as every
other realm*. Anyone who could read one operator's environment — that
operator's own staff, for a start — held valid client credentials in
**every** other operator's realm. Tenancy in this system derives from the
verified token issuer (ADR 0003), so a machine token minted in tenant B's
realm *is* tenant B: the second lock (row-level security) sets
`app.tenant_id` from that token and happily hands over tenant B's rows.
That is full cross-tenant impersonation, and it defeated the isolation
ADR 0003 claims.

The registry file made it invisible. Every tenant block reads:

```yaml
machine-client-id: ${OIDC_CLIENT_ID:bss-assurance}
machine-client-secret: ${OIDC_CLIENT_SECRET:assurance-secret}
```

Those two placeholders carry **no tenant suffix**, unlike every other
per-tenant key (`${BANK_TOKEN_NOVA:}`, `${AI_API_KEY_ENET:}`). Each
service resolves them from *its own* `OIDC_CLIENT_ID` /
`OIDC_CLIENT_SECRET` env, so billing used billing's one literal for every
tenant, catalog used catalog's for every tenant, and the whole thing only
worked *because* the realms shared secrets. The design had the
hole baked in as a feature.

The users half was less bad than it looks: both paths already dropped the
personas (`pat@bss.local`, `nils@nova.example`, `agent-anna`, …) and kept
only `demo` plus the service accounts. But `demo` was imported with
nova's password hash, and the service-account records were nova's
records.

## What the fix does for a NEW operator

Both onboarding paths now do the same three things — in one place, see
"One implementation" below.

1. **A machine secret of this operator's own.** 48 bytes from a
   `SecureRandom`, URL-safe base64 (64 chars), generated per onboarding
   and written onto every confidential client in the new realm, replacing
   the template literal. Public clients (`bss-demo`, `bss-storefront`,
   `bss-console`, `bss-csr`, `bss-app`, `bss-biz`, `bss-stepup`) carry no
   secret at all.

   *(Keycloak 26.3.5 was tested both ways: a confidential client imported
   with no `secret` gets a freshly generated 32-char one; a client with a
   literal keeps it verbatim. We supply our own value rather than letting
   Keycloak mint 24 different ones, because the registry has exactly one
   `machine-client-secret` scalar per tenant — see the honest limit.)*

2. **No user travels.** The imported realm has `"users": []`. Nothing of
   another tenant's — no persona, no password hash, no service-account
   record — crosses the boundary. What the dropped entries carried that
   the realm actually needs is *role bindings*, and those are re-applied
   afterwards against the new realm's own accounts:
   - Keycloak creates a service-account user with each
     `serviceAccountsEnabled` client; onboarding reads the template's
     `serviceAccountClientId` / `realmRoles` / `clientRoles` **as
     configuration** and grants exactly those scopes to the new realm's
     own service accounts (22 of them).
   - The staff login is *minted*, not imported: username `demo`, the
     template `demo` user's 67 realm roles, and a password set here. It
     goes through `partialImport` because the admin user endpoint and the
     realm import disagree about this realm's
     `registrationEmailAsUsername: true`.

   **The default-roles trap, found while proving this.** A realm *import*
   gives a service account exactly the roles its user entry lists. An
   account **Keycloak creates itself** — which is what happens once the
   users array is empty — also gets `default-roles-<realm>`, whose
   composite is `customer, manage-account, offline_access,
   uma_authorization, view-profile`. A machine token carrying `customer`
   is confined by `PartyScope` to a party the service account does not
   have, so every fleet callback for that tenant comes back **404, not
   403** (cross-tenant reads as 404 by design, ADR 0003) — SOM's
   order-item callback failed, no product reached inventory, and no bill
   cut. Onboarding now strips `default-roles-<realm>` from each service
   account before granting the template's roles, so the result matches
   the import byte for byte.

   Roles, client definitions, scopes, flows, required actions, groups and
   every other realm setting are untouched.

3. **The secret reaches the runtime through the tenant block.** The
   appended block now reads:

   ```yaml
   machine-client-id: ${OIDC_CLIENT_ID:bss-assurance}
   machine-client-secret: ${OIDC_CLIENT_SECRET_<TENANT>:<generated value>}
   ```

   `machine-client-id` keeps resolving per service (catalog is
   `bss-catalog`, billing is `bss-billing`), so each component still
   authenticates as its own client with its own narrow role set. The
   secret is this operator's, and the `_<TENANT>`-suffixed env name lets a
   real deployment move the value into a secret store and override the
   file without editing it.

   `TenantFileRefresher` re-reads the file every 15 s and resolves
   `${ENV:default}` the same way Spring does, so a new operator joins the
   running fleet with its own credential and **no restart**.

The generated value is never logged, never returned on the receipt and
never printed by either onboarding path. It exists in exactly two places:
the realm in Keycloak, and this tenant's block in
`infra/tenants/tenants.yml`.

### One implementation, two doors

The two paths could drift because they were two copies of the same logic —
and the drift *was* the hole. `ops/onboard-tenant.sh` no longer clones
anything: it takes a host-operator staff token and calls
`POST /onboarding/v1/operator`, the same endpoint the console's operator
form uses. Its CLI contract is unchanged
(`ops/onboard-tenant.sh <id> "<Brand Name>" <locale> <currency> [#color]`),
it no longer restarts the fleet (the live refresh made that unnecessary),
and it finishes by proving the newborn serves its own catalog through the
gateway and then waiting one registry refresh interval.

That wait matters now. The read path only needs the tenant *in* the
registry; the machine path needs its **secret** too. Re-onboarding an
existing id rotates the secret out from under every component's cached
registry entry, and a first order placed in that window fails with a 502.
Both heal on the next refresh tick, so the script outwaits it
(`BSS_TENANTS_REFRESH_MS / 1000 + 6`, 21 s by default) rather than hand
back an operator whose first order 502s.

The realm clone also asserts its own invariant before the import: no
client may leave with a template secret, and the users array must be
empty. A future edit that reintroduces either fails the onboarding
instead of quietly shipping the hole.

## What is still broken (deliberately)

**The realms that exist on this deployment today — `bss` (genalpha),
`nova`, `enet`, `taranga`, and every demo realm beside them — still share
the template's 24 client secrets with each other and with
`docker-compose.yml`.** Nothing here rotated them. Rotating a serving
realm's machine credentials restarts the trust between about twenty
running components; it is an operational decision with a maintenance
window attached, not something an onboarding fix gets to do to a live
fleet. The hosted demo box (`demo.taranga.no`) is in the same position.

Treat every realm created before 2026-09-23 as holding credentials valid
in every other pre-2026-09-23 realm.

## How an operator rotates one realm, deliberately

Do one realm at a time, in a window. `<tenant>` is the realm id.

1. **Generate the new secret** somewhere that is not a shell history or a
   log:
   ```bash
   NEW=$(python3 -c "import base64,secrets;print(base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip('='))")
   ```
2. **Set it on every confidential client in that realm.** Get a master
   admin token, then for each client with `publicClient: false`:
   ```bash
   kcadm.sh update clients/$UUID -r <tenant> -s "secret=$NEW"
   ```
   (or `PUT /admin/realms/<tenant>/clients/{uuid}` with `{"secret": "..."}`).
   Do not skip `bss-agent` and `bss-ecs` — they are confidential without
   service accounts.
3. **Put it in the tenant's block** in `infra/tenants/tenants.yml`:
   ```yaml
   machine-client-secret: ${OIDC_CLIENT_SECRET_<TENANT>:<new value>}
   ```
   In a real deployment set the `OIDC_CLIENT_SECRET_<TENANT>` env from the
   secret store on every component instead, and leave the file's default
   empty.
4. **Wait one refresh interval** (15 s, `BSS_TENANTS_REFRESH_MS`). Every
   component re-reads the file; `MachineTokenInterceptor` caches a token
   per tenant and evicts on the first 401, so the cutover is self-healing
   within one token lifetime. If you changed env instead of the file, the
   components need a rolling restart.
5. **Prove it.** The old secret must be refused and the new one accepted:
   ```bash
   # expect 401
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST http://localhost:8085/realms/<tenant>/protocol/openid-connect/token \
     -d "grant_type=client_credentials&client_id=bss-billing&client_secret=$OLD"
   # expect 200
   curl -s -o /dev/null -w '%{http_code}\n' \
     -X POST http://localhost:8085/realms/<tenant>/protocol/openid-connect/token \
     -d "grant_type=client_credentials&client_id=bss-billing&client_secret=$NEW"
   ```
   Then ride one order to activation in that tenant — the order-to-service
   chain is what actually uses the machine path.
6. **Repeat for the next realm.** Until every realm is done, the ones you
   have not rotated still trust each other.

The genalpha (`bss`) realm is the host operator and the one the fleet's
own `OIDC_CLIENT_SECRET` env values belong to. Rotating it means changing
`docker-compose.yml` / the Helm secrets in the same window. Do it last.

There is no rotation script in the repo on purpose: a script that
rewrites 24 client secrets across a live realm is a loaded gun, and the
decision of when to fire it is Kundan's.

## Honest limits

- **One secret per tenant, not per client.** All of a tenant's
  confidential clients share that tenant's generated secret, because the
  registry has exactly one `machine-client-secret` scalar per tenant and
  every service resolves it from the same shared file. Within a tenant
  that buys nothing anyway — `tenants.yml` is mounted into every
  component, so any component can already read any tenant's line. The
  wall that matters, between tenants, is now real. Per-client secrets
  would need a `machine-client-secrets: {clientId: secret}` map on
  `TenantEntry` in 39 registry copies plus the interceptors; that is a
  separate arc.
- **The generated value sits in a git-tracked file.** For the laptop
  fleet that is no worse than `docker-compose.yml`, which commits every
  component's `OIDC_CLIENT_SECRET` in clear. For anything real, set
  `OIDC_CLIENT_SECRET_<TENANT>` from a secret store and never let the
  generated default into a commit. `docs/hardening.md` already says never
  import the repo realms.
- **The staff login is still `demo` / `demo`.** It is minted for the new
  realm rather than inherited with nova's hash, but the password is the
  fleet's documented dev credential and `TenantOnboardingService.staffToken`
  depends on it for clone, twin-seeding and prospect simulation. A
  production onboarding must set a per-tenant staff password and hand it
  back once on the receipt — the shape already exists
  (`UserView.temporaryPassword`). Not done here.
- **No suite asserts the secret yet.** `ops/e2e/third_operator_test.js`
  exercises the fixed script end to end and passed on a throwaway copy
  (operator born, customer activated, 66.40 DKK prorated bill, walls hold
  both ways), but it never looks at a credential. The threat model's
  proposed check — nova's `bss-catalog` secret refused by a new realm —
  is still owed, as a step in `tenant_test.js`. The rest of the
  verification was by hand against the running fleet: a throwaway realm
  onboarded, proven (24 of 24 confidential clients differ from nova;
  nova's, genalpha's, enet's and taranga's `bss-billing` secrets each get
  401; 1 inherited user, namely none) and deleted.
- **Existing realms are untouched.** Said again because it is the thing
  that will bite: this fix protects operators onboarded from now on.
