# Self-service: Home first

*What a signed-in customer sees, and why the shop is no longer the front door.*

Ivan's B2C review (14 September 2026) said the storefront's problem was
information architecture: ten navigation items competing at one level, a sales
hero before the customer's own state, and customer jobs spread across pages that
mirrored the BSS modules behind them. This is what was built from it.

## The five destinations and three utilities

| Primary | Holds |
|---|---|
| **Home** | attention, my services, money & usage, open work, recent activity, one recommendation |
| **Services** | every product and its service, usage, SIM/eSIM, boosts, gifting, points, referrals; links to devices and family |
| **Billing** | bills and delivery, payments, instalments, disputes, collections |
| **Shop** | recommended, then the categories; account-aware |
| **Support** | guided: check the line, find the answer, talk to us; cases |

Header utilities: the cart (bag with a count only when non-empty), the inbox
(bell with unread count), and the profile menu (account, my orders, my devices,
family, sign out). A guest sees Shop and Support with the cart.

## Home, exception-first

The first screen answers "is everything okay, and what should I do next?"

1. **Greeting and health.** "Good evening, Paula — Everything looks good",
   "Everything is working · 2 updates", or "1 thing needs your attention" —
   counting *situations*, not events.
2. **Attention**, only when relevant, each with its next step:
   - a known problem on *the customer's own line*: "We know about a problem on
     your line +47… Our network team is on it — you do not need to do anything."
     This is read from the ontology's customer context with the customer's own
     token (`GET /ontology/v1/context/customer/{id}/recommendations`, signed as
     the registered `shop-home` agent), so the care agent's Assist and the
     customer's Home say the same thing at the same moment. The registry reads
     the network's problem list with its own account and the customer only ever
     sees problems matched to their services.
   - one paused line, with **Resume** right there; several paused lines as
     *one* card — "2 services are paused — no charges apply while they are
     paused" — with **Review services**, which opens the Services page on its
     paused section (every paused service, Resume now, Resume all);
   - the **effective money state**, read from the collection case rather than
     the bill flag: overdue → "Payment overdue: 1 110.66 NOK. Settle it, or
     agree a payment plan" with **Pay now**; a promise to pay → "Payment plan
     agreed: 1 110.66 NOK by 2026-09-25. Nothing else is due until then" with
     **Pay early** and *No action needed*; a broken promise → act now again,
     with no page logic (the ladder cleared the promise); a dispute → "collection
     waits while we look at it"; otherwise the open bill;
   - data nearly used up, with **Buy extra data**;
   - an order in progress; an open support case.
3. **My services** as cards: kind, number, data left or address, state, and the
   actions that service can take. A mobile line: usage & SIM, add data, check my
   line. A broadband line: check the connection, upgrade speed. Six at most on
   Home; the rest under Services.
4. **Money & usage**: the open or latest bill with Pay, the data meter, roaming
   spend, points. With no bill yet: "Your first bill comes at the end of your
   first billing period and covers only the days since your service started."
5. **Open work**: orders in progress with a progress line, open cases, upcoming
   visits — or "No open work".
6. **Recent activity**: the last five orders, messages and cases.
7. **Recommended for you**: placed by health. With a healthy account it sits
   right under the services; with something open it drops below the
   customer's needs; while something critical is open it is not shown at all.
   One **best match** with its own reason, up to two alternatives with theirs.

Three attention cards at most, the most serious first (critical → warning →
info); the rest is "and 2 more — see all". Informational items are compact and
say "No action needed".

## Situations, not events

The ontology's customer context now answers with a `summary` beside the
per-line `situation`: one entry per kind with `severity` (critical, warning,
info), `actionRequired`, `count`, `members` and words that fit the count. The
care desk's Assist renders the same summary, so agent and customer read the
same sentence. New situation kinds: `overdue`, `arranged`, `disputed` (from
`billing.collectionCases`), beside `incident`, `paused`, `bill`. The response
also carries `severity` (the worst) and `healthy`.

## The offer page is a decision

A recommendation on Home is a recommendation of the ontology (kind `offer`,
action `considerOffer`, built from `recommendation.list`), so it carries a
decision id and a reason. Opening it lands on the offer page with a decision
block: why it was recommended, **Now** (what the customer holds in that
category and its monthly price) versus **With this**, the commitment (from the
offering's terms, or "No binding period"), when it takes effect (today; the
next bill is split at the change date). Three honest outcomes: **Add to cart**
(accepted), **Maybe later** (deferred — off Home for 30 days), **Not
interested** (rejected — not suggested again). **Back** is not a verdict and
logs nothing. Outcomes go through `POST /ontology/v1/context/recommendations/
{decisionId}/outcome` into the decision log the desk's ranking reads, so the
loop now learns from customers as well as agents. Offers are never shown while
something critical is open.

Proof: `ops/e2e/home_attention_test.js` (#130) — two paused lines become one
card; Review services → Resume all; the suggestion lifts when the account is
healthy; the lead pick opens a decision; Maybe later and Not interested land in
the decision log and both offers leave Home. The effective money state is
asserted in `collections_test.js` on a real promise to pay and its breaking.

## The shop sells in context

Offers became Shop. The hero is a third of its former height. An existing
broadband customer is not asked for a postcode. Recommended for you leads with
one pick and its reason in the open ("Why this?" — the caption from the
individualised rail, or the plain rule: nothing you already own, nothing that
needs an identity check first), and the rest of the shelf follows. Service
cards on Home carry the facts a customer needs to tell lines apart: number, data
left, the masked SIM, the commitment end date, the broadband speed and address.
Empty states say what happens next (orders, devices, inbox, bills); the inbox
count refreshes on every page.

## Support is guided

Check the line first (the same diagnosis the care desk runs: outage, out of
data, paused), then the answers, then talk to us — the chat bubble or a case.

## What is deliberately later

Lifecycle-aware ranking of the lead card in each Home zone through the ontology
(new customer → activation, travelling → roaming pass). The zones are stable and
the attention list already comes from the ontology; ranking the rest is a pass of
its own.

## Proof

`ops/e2e/shop_home_test.js` (#128): a guest's navigation; a new customer with a
line lands on Home with five destinations and three utilities; a healthy
customer sees no attention items and the zones in order; an alarm on her line
becomes the first thing on Home in her words; a paused line is an attention item
and Resume works from Home; the shop hero height; Support's guided steps with the
line check. Every suite that drove the shop by its old labels was moved with it.

## Saying only what was checked (2026-10-16) — #289

Home reads sixteen things at once and stays fail-soft, so a bad day never
blanks it. But every failed read used to become an empty answer, and Home built
its sentences from those. With billing stopped, Paula's Home said **"Everything
looks good"**. It also said it a second time in a quieter way: the health line
waited only for the ontology, so for the seconds Home's own reads were still
in flight it made the all-clear about facts it had not received yet.

Now:

- **Home remembers which reads failed** (`src/reach.js`) and makes no all-clear
  about them: *"We could not check your bills and your payment status just now
  — so we cannot say everything is fine. Try again in a minute."* The facts an
  all-clear rests on are the customer's plans, services, bills, payment status,
  usage and orders; the extras (offers, loyalty, referrals) may fail quietly.
- **No all-clear before the page has heard back.** The health line says
  "Reading your services…" until Home's own reads answer, not only the ontology.
- **"Nothing active yet"** and **"No data allowance on this account"** are said
  only when service inventory and usage actually answered; otherwise Home says
  it could not load them.
- **Bills: the payment status is "unknown", not "current".** A collections read
  that failed used to return `null`, which is the value for an account in good
  standing — a customer in collections saw no warning while billing was down.
  It throws now, and the page says it cannot tell whether anything is overdue.

The architecture ratchet's rule 8 now also counts the helper spelling
`p.catch(() => fallback)` — the one Home used. It took the mobile app's
`soft` helper into the count (1 → 2: the code did not change, the rule got
sharper). Fleet-wide the count went 115 → 113.

### Proof

`ops/e2e/shop_unreachable_test.js` signs in as Paula, stops `bss-billing`, and
asserts Home makes no all-clear and the Bills page does not say "No bills yet";
restarts it and asserts the notice clears; then answers ONLY the collections
route with a 503 (the same component serves the bills, so a stopped container
cannot separate them) and asserts the bills render with the payment status
marked unknown. Run against `main`'s storefront it fails: Home says
"Everything looks good" with billing down. `shop_home_test` and
`home_attention_test` stay green.

### Honest limits

- **A stopped component costs about 30 seconds** of "Reading your services…"
  on Home: the browser's billing reads wait out the gateway's timeout before
  they fail. Honest, but slow; a faster failure belongs at the gateway.
- **The Bills page itself** still shows the raw error text when the bills
  cannot be read — honest, not yet friendly.
- **Services, Shop, Family, Offering, Cart** keep 60-odd swallowed reads. Most
  are extras (prices, also-bought, specs), but the Services page's usage and
  service rows go quiet on failure rather than saying so. The count stops new
  ones; it does not judge the old.
- **The mobile app** has the same `soft` helper Home had, untouched.
