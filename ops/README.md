# Operations scripts

## Building the stack

Service images package the **host-built** jar — always compile first:

    mvn -q package -DskipTests
    docker compose build          # seconds, not minutes
    docker compose up -d

## seed/ — demo catalogs and stock

Run against a fresh stack (`docker compose up -d`), in this order:

    python3 ops/seed/seed_genalpha_one.py   # specs, offerings, bundle, prices
    python3 ops/seed/reshape_bundle.py      # phone choice group + characteristics
    python3 ops/seed/link_prices.py         # offering -> price references
    python3 ops/seed/seed_stock.py          # TMF687 stock for the phones
    python3 ops/seed/seed_serviceable_areas.py  # TMF679 fiber footprint (111/222/333)
    python3 ops/seed/seed_usage_allowances.py   # TMF635 bundle roaming allowance
    python3 ops/seed/seed_agreement_terms.py    # TMF620/651 bundle commitment term
    python3 ops/seed/seed_promotions.py         # TMF671 WELCOME10 (10% off the bundle)
    python3 ops/seed/seed_resource_pools.py     # TMF685 per-tenant MSISDN pools
    python3 ops/seed/seed_ai_slice.py           # AI-slice PoC: edge GPU pool, slice + token-metered AI
    python3 ops/seed/seed_verified_identity.py  # postpaid offering requiring BankID step-up
    python3 ops/seed/seed_content.py            # TMF667 logos + offering artwork (run AFTER seed_nova)
    python3 ops/seed/seed_nova.py           # second tenant: Nova Telecom's catalog

All scripts are idempotent-ish (safe to re-run) and authenticate as each
tenant's demo staff user through the gateway.

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

The storefront suite pins Samsung stock availability to 10 at the start, so
repeated runs stay deterministic. Keycloak access tokens live five minutes;
the suite refreshes its session via SSO before the billing chapter. The
tenant suite uses `*.nova.localhost` hosts, which browsers resolve to
127.0.0.1 without DNS setup.
