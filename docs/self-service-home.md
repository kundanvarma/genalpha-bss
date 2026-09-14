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

1. **Greeting and health.** "Good evening, Paula — Everything looks good" or
   "2 things need your attention".
2. **Attention**, only when relevant, each with its next step:
   - a known problem on *the customer's own line*: "We know about a problem on
     your line +47… Our network team is on it — you do not need to do anything."
     This is read from the ontology's customer context with the customer's own
     token (`GET /ontology/v1/context/customer/{id}/recommendations`, signed as
     the registered `shop-home` agent), so the care agent's Assist and the
     customer's Home say the same thing at the same moment. The registry reads
     the network's problem list with its own account and the customer only ever
     sees problems matched to their services.
   - a paused line, with **Resume** right there;
   - an overdue amount (the collections case) with **Pay now**, or an open bill;
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
7. **Recommended for you**: one to three, with the "why" caption. Always last.

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
