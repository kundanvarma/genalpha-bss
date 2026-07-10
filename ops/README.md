# Operations scripts

## seed/ — demo catalog and stock

Run against a fresh stack (`docker compose up -d`), in this order:

    python3 ops/seed/seed_genalpha_one.py   # specs, offerings, bundle, prices
    python3 ops/seed/reshape_bundle.py      # phone choice group + characteristics
    python3 ops/seed/link_prices.py         # offering -> price references
    python3 ops/seed/seed_stock.py          # TMF687 stock for the phones
    python3 ops/seed/seed_serviceable_areas.py  # TMF679 fiber footprint (111/222/333)

All scripts are idempotent-ish (safe to re-run) and authenticate as the demo
staff user through the gateway.

## e2e/ — browser end-to-end suites (Playwright)

    cd ops/e2e && npm init -y && npm i playwright && npx playwright install chromium
    node storefront_test.js   # register, configure, cart, ship, pay, stock, bill
    node guest_test.js        # anonymous browse -> register at checkout
    node console_test.js      # admin console incl. stock tab

The storefront suite pins Samsung stock availability to 10 at the start, so
repeated runs stay deterministic. Keycloak access tokens live five minutes;
the suite refreshes its session via SSO before the billing chapter.
