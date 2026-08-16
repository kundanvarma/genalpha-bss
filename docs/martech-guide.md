# Martech & CDP — operator guide

The growth guide covers *how you reach people* — journeys and campaigns. This guide covers the
other half: **who you reach, where you send them, and how you know it worked.** It's the
customer-data platform built into the BSS — audiences from your own operational data, activation to
the ad platforms, inbound social, and honest measurement. Everything lives in the console under the
**Growth** workspace.

> **The one idea.** A normal CDP is a second database you copy your customers into. This one isn't.
> Audiences are built from the operational event bus you already run — an order completes, a bill is
> issued, a loyalty tier changes, and the trait is *already* there. No nightly export, no
> reverse-ETL round-trip, no stale copy. The BSS *is* the CDP.

---

## 1. The mental model

| Thing | What it is |
|---|---|
| **Trait** | A fact about a party, fed live from a domain event — `product=Fibre 500`, `loyaltyTier=gold`, `region=Oslo`, `churnRisk=high`. You never import these; the bus fills them. |
| **Audience** | A saved rule tree over traits/behaviour, resolved to members on demand. Has a **population**. |
| **Population** | *Who* the audience is drawn from: **customers**, **prospects** (not customers yet), **organizations** (B2B), or **visitors** (anonymous browsers). |
| **Activation** | Pushing an audience out to an ad platform (Meta, Google) as a hashed match list — to *seed* lookalikes or *suppress* paid spend. |
| **Attribution** | The readout: holdout-vs-treated lift and incremental revenue, per program and across the whole portfolio. |

Governed by default: every audience is **consent-gated**, activation **hashes** identifiers (email
never leaves in the clear) and filters your **do-not-contact** list, and measurement always leans on
a **holdout** so lift is real.

---

## 2. Build an audience from your own data

**Example: gold-tier fibre customers in Oslo, for an upgrade offer.**

1. Console → **Growth → Audience builder**.
2. **Population**: `Customers`.
3. Build the rule tree — match **ALL** of:
   - Customer data → `loyaltyTier` `=` `gold`
   - Customer data → `product` `=` `Fibre 500`
   - Customer data → `region` `=` `Oslo`
4. **Name** it `Oslo gold fibre` and **Save**.
5. On the **Saved audiences** tab, open it: **member count** and the resolved members are computed
   live from current traits — no refresh job needed.

> **Range operators.** Numeric traits (`monthlySpend`, `dataUsagePercent`) take `≥ / ≤ / >` as well
> as `=`, so "spends ≥ 600/month" is a leaf, not a report.

> **Behaviour, not just attributes.** Leaf type **Behaviour (browsing)** matches an interest a
> customer showed on the storefront; **Analytics audience** matches a GA4 segment if the tenant has
> a provider bound. Pure-trait audiences resolve as a single set-based SQL query (fast at millions,
> index-backed); behavioural ones layer in per-member work only when the tree asks for it.

### The traits you can target

Traits are filled live off the operational bus (an order completes, a bill is issued, a tier
changes — the trait is *already* there). Single-valued traits **replace on change**, so a customer
whose value changes **moves between audiences automatically** on the next resolution.

| Trait | Where it comes from | Notes |
|---|---|---|
| `product` | a completed order / product inventory | multi-valued (several holdings); **retracts** when a product is cancelled |
| `deviceModel` | the **network** (EIR / device-detection, IMEI→TAC→model) | the handset the SIM is *actually* in — incl. BYOD; a swap re-homes them |
| `region` | the customer's party record (native) | a move re-homes them between region audiences |
| `loyaltyTier` · `monthlySpend` · `churnRisk` | loyalty / billing / intelligence events | single-valued; range operators on the numeric ones |
| `email` | party record | used to reach + to label members |
| `industry` · `orgName` | organization events | the B2B population |

> **"Bought an iPhone" vs "on an iPhone".** `product = Apple iPhone 17` targets who **bought** that
> handset from you; `deviceModel = iPhone 15` targets who is **currently using** one on the network
> (regardless of where they bought it) — the classic upgrade-offer segment.

> **Existing customers are in the CDP.** The trait store is fed by events going forward; a one-shot
> **backfill** (`ops/e2e/backfill_cdp.js` → `POST /insight/v1/traits/backfill`) seeds email/region/
> product for customers who predate the CDP, so your whole base is reachable, not just new signups.

### Seeing and pruning audiences

On **Saved audiences**, **View** expands the actual members (labelled by email, not opaque ids), and
**Delete** removes an audience so the list stays clean. Big audiences preview a bounded slice —
resolution stays flat as the base grows.

### Prospects — people who aren't customers yet

Switch **Population** to `Prospects` to target leads. A bought or imported list lands **captured but
not reachable** — it becomes targetable *only* once an explicit lawful basis records consent. Import
under the prospect panel; the gate is absolute, and prospect audiences resolve consented-only.

### Organizations (B2B) and Visitors (anonymous)

- **Organizations** — the population is companies, not people; match on `industry`, `orgName`, and
  other org traits fed from `OrganizationCreateEvent`.
- **Visitors** — anonymous browsers who consented but never signed in, keyed by `visitorId`
  (cookie/device), resolved by **browsing interest**. This is the retargeting/on-site-personalization
  population: no account, no email, just "people who looked at handsets." See §5.

---

## 3. Activate an audience to the ad platforms

Once an audience exists, push it to where you buy media.

1. On the **Saved audiences** tab, open the audience → **Activate**.
2. **Destination**: `meta` (Custom Audiences) or `google` (Customer Match). Same audience, either
   platform — the connector speaks both wire shapes.
3. **Mode**:
   - **seed** — the list becomes a source for lookalike targeting (find more people like these).
   - **suppress** — the list becomes an exclusion (stop paying to advertise to people you already have).
4. **External audience id**: the id of the list on the ad platform. Activate.

What happens: emails are **SHA-256 hashed** once, the do-not-contact list is filtered out, and the
match list is pushed in batches. Activation is **asynchronous** — you get a `jobId` back and can poll
its lifecycle (`queued → running → done`), so a big export never blocks the console.

> **Adding a third platform** (TikTok, LinkedIn…) is a new destination class and a per-tenant
> credential, not a new flow. `GET …/audience/destinations` lists what's wired.

> **Production credentials.** The demo pushes to a mock ad platform that accepts any token. In
> production each destination carries a real OAuth credential from the tenant's secret store; the
> flow above is unchanged.

---

## 4. Read the results — portfolio attribution

**Where:** Console → **Growth → Attribution**. One page, every campaign *and* journey.

- **Per program**: reached, held out, conversions (treated vs holdout), **lift**, and **incremental
  revenue**.
- **Portfolio**: blended lift and total incremental revenue across everything, plus a by-channel
  split (campaigns vs journeys).

The number that matters is **incremental revenue** — treated-per-head minus holdout-per-head, times
heads reached. It's the money the message *actually made*, not the gross a message cannon would claim
by taking credit for orders that would have happened anyway.

> **The honesty rule, in the open.** A program run with **no holdout** shows its reach and its gross,
> but its incremental column reads **"— (no holdout)"**. You cannot measure lift you never left room
> to see, and the report refuses to pretend otherwise. Keep a 10% holdout and the number becomes
> real.

---

## 5. Anonymous-visitor retargeting

Reach the browser who looked but never signed in.

1. A visitor consents to personalization on the storefront and browses a category — the BSS records
   the interest against their `visitorId`. No account required.
2. Console → **Growth → Audience builder** → **Population**: `Visitors`.
3. Match **Browsing interest** `=` the category (e.g. `Handsets`). Save.
4. The audience resolves the consented browsers by interest, keyed by `visitorId` — a
   retargeting/on-site-personalization list. (This population is device-keyed, so it feeds web
   retargeting and on-site personalization rather than the email path.)

---

## 6. Inbound social — listening and care

Two ears on the market, both under **Growth**.

### Social listening — what's said *about* the brand

Console → **Growth → Social listening** → **Sync mentions**. The BSS pulls brand mentions from the
connected handle, scores each for sentiment, and shows the mood (positive / neutral / negative) and
the feed. It's share-of-voice, not a campaign — the outbound and the inbound in one stack.

### Social care — inbound DMs become tickets

Console → **Growth → Social care** → **Sync DMs**. Direct messages are private support
conversations, so they're triaged differently:

1. Each DM is scored for **sentiment** and **support intent**.
2. A **negative** or **support-seeking** DM is automatically routed to a **trouble ticket** — a
   `major` case for an angry message, `minor` for a plain question. The customer's reply handle rides
   on the ticket so an agent can answer on the same channel.
3. A **happy** DM opens nothing — the care queue is for people who need help, not praise.

The DM never reaches the care team by a phone call or a copy-paste; the CDP raises the ticket over
the event bus and the trouble-ticket service owns the case (TMF621). One DM opens exactly one ticket,
even if the message is delivered twice.

---

## 7. Runs on *any* BSS — the add-on story

Everything above reads the operational bus and writes to owned channels — which means the whole
module can sit on top of a BSS that isn't this one. A small **bridge** service maps a foreign BSS's
own event shapes to the martech envelope; the audience engine, activation, and attribution never
change. That's the product thesis: *composable, but the core is the BSS you already run.*

---

## 8. The consent ledger — reading the Visitor consent tab

Console → **Privacy & governance → Visitor consent** is the **consent ledger**: *who the shop is
watching, under which consent, and what it learned.* It's **read-only by design** — the visitor owns
the data; the operator only gets to *see* what it holds. This is your accountability surface (show me
everything we hold on this browser) and your personalization debugger (why does this person see X, or
nothing). It's a **real ledger** — paginated (not a recent-100 window) with **search across all
visitors by id** (the box queries the server, not just the loaded page). It lives under *Privacy &
governance*, not Growth: it's a consent/accountability surface, not a growth lever — Growth links to
it for debugging.

**List columns:** `visitorId · partyId · analyticsConsent · personalizationConsent · utmSource · lastUpdate`.
**Row detail** adds: the two consent flags, the **event count**, the **interests** with view counts
(e.g. `Devices (3), Plans (1)`), and the **campaign source**.

**How to read a row:**

| You see… | It means… |
|---|---|
| `analyticsConsent = no`, `events = 0` | The visitor declined storage — nothing was kept. Correct, not a bug: reject = zero rows. |
| `partyId` empty | Anonymous browser, not signed in (this is the **visitor** population from §5). |
| `partyId` populated | Stitched to a customer at sign-in — written from the verified token, only under personalization consent. |
| `interests: Devices (3)` | Three consented views in that category — the behavioural signal audiences read. |
| `utmSource` set | Arrived from that campaign — the attribution source. |

> **Two consents here — and they are NOT "marketing consent."** The flags on this tab are
> **first-party tracking + personalization** consent (the cookie-banner family): `analyticsConsent`
> gates *storing* breadcrumbs, `personalizationConsent` gates *using* them for the on-site experience.
> **Permission to *contact* someone is a separate consent, kept elsewhere** — a prospect's
> `consent` + `lawfulBasis` (§2; a bought list is captured but unreachable until a lawful basis is
> recorded) and the **do-not-contact / suppression ledger** filtered at every send and activation.
> Keeping tracking-consent and contact-consent apart is deliberate: the law treats them differently,
> and so does this system.

*Full first-party personalization walkthrough (guest loop, reject-honestly, stitch, GA4 seam): see the
[Personalization guide](personalization-guide.md).*

---

## 9. Marketing preferences & unsubscribe — the opt-out spine

For **existing customers**, marketing is opt-*out* (the legitimate-interest model), and the customer
is always in control:

- **Self-serve** — a customer opens **shop → Account → Marketing preferences** and toggles out. That
  writes a party-keyed opt-out; every subsequent marketing send **skips them** (in-app *and* email),
  and they're excluded from ad-platform activations too.
- **One-click unsubscribe in every message** — every marketing message carries a no-login unsubscribe
  link (an HMAC token, so it can't be forged for someone else). Following it honours the opt-out.
- **Enforced at send** — the send path checks the opt-out *before* the frequency cap; a fully-opted-out
  blast returns `{status: suppressed}` and delivers nothing.

> **The law (EU ePrivacy + GDPR Art 21; Norway markedsføringsloven §15):** an unsubscribe is
> **mandatory in every marketing message** — it cannot be omitted, and a request to stop must be
> honoured. Opt-out alone (no prior opt-in) is allowed only under the existing-customer/similar-product
> "soft opt-in". *(Not legal advice — confirm with counsel.)* Prospects are the opposite: **opt-in**
> only, via a recorded lawful basis (§2).

## 10. Landing pages & lead capture — the acquisition loop

A standalone campaign landing page (separate from the storefront) that an ad or email deep-links to,
authored in **console → Growth → Landing pages**:

1. **Author** — headline, subhead, button label, the **campaign (utm_source)** leads are stamped
   with, and optional branding: **logo, hero image, brand colour, a "learn more" link, a privacy
   link**. (URLs are sanitized — no `javascript:` — and the colour must be `#hex`.) **Edit** pre-fills
   the form; the slug (the public URL) stays fixed so live links never break.
2. **Preview** — **Open page** shows the public URL (`/insight/v1/landing/{slug}/view`) — a
   self-contained branded page with a **consent-first form**.
3. **Capture** — a *ticked* submission becomes a **consented prospect**, stamped with the campaign.
   No consent → no capture (enforced, not wished for).
4. **Close the loop** — build a prospect audience `source = <campaign>` → nurture; **Attribution**
   reads the lift.

So **ad/email → branded landing page → consented lead → prospect audience → nurture** is one
consent-first loop, all authored from the console.

## 11. Quick reference

### Populations

| Population | Drawn from | Keyed by | Typical use |
|---|---|---|---|
| `customer` | trait store (live from the bus) | party id | offers, retention, suppression |
| `prospect` | consented imported leads | email | acquisition (consented only) |
| `organization` | org traits | org id | B2B |
| `visitor` | consented anonymous profiles | visitorId | retargeting, on-site personalization |

### Activation

| Field | Values |
|---|---|
| destination | `meta` · `google` (extensible) |
| mode | `seed` (lookalike source) · `suppress` (exclusion) |
| identifier | email, SHA-256 hashed before it leaves; DNC filtered |
| execution | async job — poll `queued → running → done` |

### Where each thing lives (console → Growth)

| Task | Tab |
|---|---|
| Build/resolve audiences | Audience builder · Saved audiences |
| Import prospects | Audience builder (population = Prospects) |
| Push to ad platforms | Saved audiences → Activate |
| Lift & incremental revenue | **Attribution** |
| Author landing pages | **Landing pages** |
| Brand mentions + mood | Social listening |
| Inbound DMs → tickets | Social care |
| The consent ledger | Privacy & governance → Visitor consent |
| Marketing opt-out (customer) | shop → Account → Marketing preferences |
| Full contact history (incl. martech) | CSR console → customer 360 timeline |

> **Marketing isn't a silo — every send is on the customer's timeline.** Each message the module
> sends (campaign, journey step, or system notice) is logged to the **omnichannel interaction record
> (TMF683)** — who, what, which channel, which system. So a **CSR sees marketing + service messages
> together** on the 360 view and can judge the next best action, and the NBA engine reads the same
> history. A campaign/journey stamps its **name** on the touchpoint, so the CSR reads
> **"Marketing (Winback): we miss you"** — not a bare subject — and knows exactly which programme
> reached out. The loop closes too: an ESP **open** or **click** lands its own touchpoint, so the
> record reads **sent → opened → clicked**. *(Proven by `interaction_timeline_test`.)*

---

**Golden rules.** Keep a 10% holdout so lift is real, not a story. Never target a prospect without a
lawful basis on record, and put an unsubscribe in every message. Trust the incremental number, not the
gross. And remember the traits are *already there* — the operational bus you run every day is the
audience feed.

*Built on TMF Open APIs (TMF688 events · TMF620/GA4 audiences · TMF621 trouble tickets · TMF699
leads). Every capability here is verified by an end-to-end browser suite — see
`ops/e2e/audience_*`, `connector_multidest`, `visitor_retargeting`, `device_model`,
`product_trait_retraction`, `marketing_preference`, `landing_page`, `social_care`,
`attribution_report`. Journeys and campaigns are covered in the [Growth guide](growth-guide.md).*
