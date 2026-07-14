# Personalization — how it works, and how to try it

A hands-on companion to `docs/personalization-plan.md`. The plan says why;
this says what is where, who generates which id, and which buttons to press.

---

## 1. The architecture elements

```
 browser (storefront)                    the BSS                        the operator's world
┌─────────────────────┐   ┌──────────────────────────────────┐   ┌──────────────────────────┐
│ visitorId            │   │ insight (component #33)          │   │ analytics platform        │
│  crypto.randomUUID() │──▶│  visitor_profile (consent flags, │──▶│  GA4 in production;       │
│  in localStorage     │   │   partyId after stitch, utm)     │MP │  mock-analytics in dev    │
│ consent card         │   │  visitor_event (breadcrumbs,     │   │  (port 8120)              │
│ beacons on views     │   │   ONLY after consent)            │◀──│  audiences computed HERE, │
│                      │   │  GET /experience  ───────────────┼───│  fetched per visitor      │
└─────────────────────┘   │        │                          │   └──────────────────────────┘
                           │        ▼                          │
                           │ policy (rules-as-data)            │
                           │  domain 'personalization':        │
                           │  condition over {interests,       │
                           │   segments, channel, utmSource,   │
                           │   knownCustomer} → banner +       │
                           │   pinned offering                 │
                           │                                   │
                           │ recommendation (TMF680)           │
                           │  ranking + interest fusion        │
                           │ intelligence                      │
                           │  🎯 next best offer (the WHY)     │
                           └──────────────────────────────────┘
```

### The two identities
- **`visitorId`** — minted **in the browser** by the storefront
  (`crypto.randomUUID()`), stored in `localStorage['bss.shop.visitor']`.
  First-party, opaque (encodes nothing about the person), per browser and
  per origin: a second browser, a private window, or cleared storage is a
  new visitor. The server only validates its length; it is a *name*, never
  an identity.
- **`partyId`** — the OIDC **token subject**: the UUID Keycloak mints at
  registration (or the pinned id in the realm file for demo personas). All
  BSS records (party, products, bills, usage) hang off it. The **stitch**
  — performed at sign-in — writes `party_id` onto the visitor profile
  **from the verified JWT**, never from the request body: you can only
  claim a browser as *yourself*, and only under personalization consent.

### Consent (the spine)
Stored on `visitor_profile` as two flags, written only by the visitor's
own choice on the shop's consent card ("Privacy choices" reopens it):
- `analyticsConsent` gates STORAGE: without it, `POST /event` silently
  drops everything — a rejecting visitor has **zero rows**, and revoking
  consent **deletes** the breadcrumbs already held.
- `personalizationConsent` gates USE: the experience answer, the stitch,
  and the party-profile fusion all check it.

### The insight component's API (`/insight/v1`, port 8119)
| Endpoint | Who | What |
|---|---|---|
| `POST /consent` | anonymous | the choice; revocation deletes |
| `POST /event` | anonymous | breadcrumb; dropped without consent, 204 either way (consent never leaks) |
| `GET /experience?visitorId=` | anonymous | "what should this person see" — interests + policy rule decision + channel + segments |
| `POST /stitch` | signed-in | visitor → party, from the verified token |
| `GET /profile` / `?visitorId=` | `insight:read` | the consent ledger (console Insight tab) |
| `GET /partyProfile?partyId=` | `insight:read` | a customer's merged interests (recommendation fusion, NBO) |

## 2. Where do audiences live? (what `localhost:8120/audiences` is)

**Audiences live in the operator's analytics platform — never in the BSS.**

`localhost:8120` is **mock-analytics**: a tiny in-memory container that
stands in for the operator's GA4, the same way mock-ocs stands in for the
charging system and mock-pim for a PIM. It speaks two things:

- `POST /mp/collect?measurement_id=&api_secret=` — the shape of GA4's real
  **Measurement Protocol**. When a tenant runs `ANALYTICS_PROVIDER=ga4`
  (nova in dev, with `G-NOVA-DEMO`), the insight component forwards each
  *consented* event here, exactly as it would to
  `www.google-analytics.com` in production.
- `GET/POST /audiences?client_id=` — the mock's stand-in for GA4's
  audience machinery. In production, **Google computes audiences** from
  the events it received ("visitors with 3+ device views this week") and
  exposes membership via its Data/Audience Export APIs. The mock cannot
  compute anything, so the demo *tells* it the answer with a `POST` —
  simulating what GA4 would have concluded.

The flow at experience time: insight asks the tenant's analytics platform
"which audiences is visitor X in?", gets back names
(`high-value-browsers`), and injects them as `segments` into the rule
context — **transiently**. Nothing is persisted in the BSS; if the
analytics platform is unreachable, `segments` is empty and everything
else still works (fail-open). That is the CDP boundary made physical:
behavioral audience *computation* is the analytics platform's job; the
BSS only *consumes* the result, per visitor, per request, under consent.

## 3. The demo walkthrough (test data)

Most personalization data is self-generating — a fresh browser IS the
test data. Steps in order:

1. **Guest loop** — private window → `localhost:8080/shop/` → the loud
   consent card → *Yes, personalize* → open **Samsung Galaxy S26** → back
   home → "✨ Because you were looking at Devices", Devices rail first.
2. **Reject honestly** — another private window → *No thanks* → browse
   the same phone → home stays default; console → Insight tab shows the
   profile with `analytics: no` and 0 events.
3. **Operator rule** — console (demo/demo) → **Rules** → enable the
   seeded **"Example: device browsers see the 60 GB pitch"** → reload the
   consenting window: the banner becomes the rule's copy and the 60 GB
   plan pins on top. (Create your own with the two *Personalize:* presets.)
4. **Stitch + fusion + NBO** — in the consenting window sign in as
   `paula@family.example`/`paula` (the stitch runs). CSR console
   (`agent-anna`/`agent`) → search *paula* → 360 → **🎯 Next best offer**:
   "Samsung Galaxy S26 — they have been browsing Devices…".
5. **The consent ledger** — console → **Insight**: every profile, its
   consent flags, interests with view counts, campaign source.
6. **Nova's GA4 seam** — browse `shop.nova.localhost:8080/shop/` with
   consent, then:
   `curl 'localhost:8120/events?measurement_id=G-NOVA-DEMO'` — the
   forwarded events, with your visitorId as `client_id`.
7. **An audience comes back** — simulate GA4's conclusion:
   `curl -X POST localhost:8120/audiences -H 'Content-Type: application/json' -d '{"client_id":"<visitorId>","audiences":["high-value-browsers"]}'`
   then `GET shop.nova.localhost:8080/insight/v1/experience?visitorId=…`
   shows `segments: ["high-value-browsers"]` — now addressable by a
   *Personalize: an analytics segment* rule.
8. **Social attribution** — visit
   `localhost:8080/shop/?utm_source=instagram`, consent, browse: the
   profile's channel classifies as `social`.

Everything above is asserted nightly by `ops/e2e/personalization_test.js`
(11 checks), including the negatives.
