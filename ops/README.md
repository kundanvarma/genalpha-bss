# Operations scripts

## Building the stack

Service images package the **host-built** jar — always compile first:

    mvn -q package -DskipTests
    docker compose build          # seconds, not minutes
    docker compose up -d

## seed/ — demo catalogs and stock

**One command takes an empty fleet to the state the suites assert on:**

    ops/seed-fleet.sh           # the whole path (51 scripts) — a proof run wants this
    ops/seed-fleet.sh smoke     # the small tier the PR gate runs (6)
    ops/seed-fleet.sh --list    # what would run, in order, without running it

The order lives in `ops/seed/manifest.txt`, and `ops/arch/seed_coverage_check.py`
reads the same file — so the runner, the gate and CI cannot drift apart.
`.github/workflows/browser-proof.yml` calls the script for both tiers.

The full tier needs the **full** fleet (`docker compose up -d`): a seed writes
through the gateway into the service it is seeding, so a stopped service is a
failed seed. The runner checks the gateway before it starts and stops at the
first failure, naming the script — later seeds build on earlier ones, so a
cascade would hide the one that mattered.

### Why it was built (issue #261)

The order used to live in two places and neither was runnable. This file
documented **13 of 54** scripts in prose, and `browser-proof.yml` carried a
22-script list inside its nightly job — a job gated on
`vars.BROWSER_PROOF_RUNNER`, which has never been set, so every scheduled run
reports `skipped` and that order had **never once executed**. The laptop's
fleet state was accumulated by hand instead, which is why the committed proof
run sat at 122 of 258 suites from 24 September and could not be reproduced
anywhere.

The gate is the part that keeps it true: a suite asserting on a fixture no seed
in the manifest creates cannot pass on a fleet seeded from scratch. Measured
against the old 22-script list, **79 fixtures were unreachable** — including
`seed_knowledge_help`, which 26 suites depend on.

Three places where order genuinely matters, and only three:

* `seed_genalpha_one` → `reshape_bundle` → `link_prices` — the bundle is
  reshaped and then priced.
* `seed_content` after `seed_nova` — it writes artwork for both catalogs.
* `seed_service_specifications` and `seed_resource_facing_services` last — they
  stamp the CFS/RFS chain onto whatever specs the other seeds left sellable.

All scripts authenticate as each tenant's demo staff user through the gateway,
and are idempotent: re-running the smoke tier against an already-seeded fleet
is a no-op.

Excluded from the path on purpose: `realm_*.py` (live-Keycloak patches, not
fixtures) and `backfill_payer.py` (a one-time backfill).

## e2e/ — browser end-to-end suites (Playwright)

    cd ops/e2e && npm init -y && npm i playwright && npx playwright install chromium
    node storefront_test.js   # register, configure, cart, ship, pay, stock, usage, bill
    node guest_test.js        # anonymous browse -> register at checkout
    node console_test.js      # admin console incl. stock tab
    node csr_test.js          # CSR channel: ticket workflow + org isolation
    node tenant_test.js       # two operators, one BSS: white-label hosts + isolation
    node app_test.js          # mobile app (web target): register, one-tap plan, SOM number, inbox
    node roles_test.js        # TMF672: tenant admins manage staff over their own IdP
    node martech_test.js      # campaign engine + AI copy + churn scorer -> retention
    node ai_slice_test.js     # AI-slice PoC: intent -> quote -> order -> fibre-cut self-heal
    node bankid_test.js       # verified-identity step-up gate at checkout
    node porting_test.js      # number porting (NRDB): keep-your-number end to end

The storefront suite pins Samsung stock availability to 10 at the start, so
repeated runs stay deterministic. Keycloak access tokens live five minutes;
the suite refreshes its session via SSO before the billing chapter. The
tenant suite uses `*.nova.localhost` hosts, which browsers resolve to
127.0.0.1 without DNS setup.
