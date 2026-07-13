# Modeling a complicated product — from catalog to storefront

How an operator builds a configurable bundle in this BSS, using only TMF620
data — no code, no redeploy. The worked example is **GenAlpha Family Max**
(`ops/seed/seed_family_max.py`), one offering that uses every tool in the box.
Every piece below is enforced twice: rendered by the storefront configurator,
and validated by TMF622 at order time — the UI is convenience, the order is law.

## The building blocks

| You want | TMF620 mechanism | Storefront renders | Ordering enforces |
|---|---|---|---|
| Always-included component | `bundledProductOffering` entry with `bundledProductOfferingOption {lower:1, upper:1}` | "What's included" list | implicit (not an order item; the bundle price covers it) |
| Optional add-on (0..1) | same entry with `{lower:0, upper:1}` | "Optional add-ons" checkbox | counted if sent |
| Pick exactly one of N | `BundledProductOfferingChoice {lower:1, upper:1, options:[…], default}` | radio group | `exactly 1 selection(s)` |
| Pick N–M of K | `BundledProductOfferingChoice {lower:N, upper:M}` | checkbox group with a live cap and an add-to-cart gate | `between N and M selection(s)` |
| Configurable variant (color, storage…) | `productSpecCharacteristic` on the option's **product specification**, with `productSpecCharacteristicValue` lists | dropdowns appear when that option is chosen; picks ride the order item as `productCharacteristic` | stored on the provisioned product |
| Commitment | `productOfferingTerm [{duration:{amount:12, units:'month'}}]` | — | a TMF651 agreement is minted at completion; plan changes are blocked until it ends |
| Bundle price + option prices | prices on the bundle for the base + one-time fees; each option carries its own price | the price table recomposes as you configure; one-time fees show as "due now" at checkout | billing rates the provisioned products |
| Where it appears | `category` refs (Mobile plans / Broadband / Devices / TV & Add-ons / Top-ups / Bundles) | shop grouping, My-page card placement, like-for-like plan-change filters | — |
| Included data | TMF635 `usageAllowance` per offering (`boost:true` = one-time top-up) | usage meters, top-up buttons | overage charges |
| Partner service (Netflix…) | `category: Partner services` | "My subscriptions & protection" card with the **activation code** | SOM mints an **entitlement** through the partner seam — no phone number |
| Security feature (anti-fraud…) | `category: Security` | feature row, active badge | an active feature service, zero network resources |
| Billing-only product (insurance…) | `category: Insurance` | priced like any offering | **no service at all** — the product simply bills |
| Physical stock | TMF687 stock row per device offering | "only N left" / out-of-stock | reserve at order, consume at completion |

## Anatomy of Family Max

```jsonc
{
  "name": "GenAlpha Family Max",
  "isBundle": true,
  "category": [{ "name": "Bundles" }],
  "productOfferingTerm": [{ "name": "12-month commitment",
                            "duration": { "amount": 12, "units": "month" } }],
  // the bundle's own prices: base + a one-time fee (checkout's "due now")
  "productOfferingPrice": [ { "name": "Family Max Base Monthly" },     // 49.00/mo
                            { "name": "Fiber Installation Fee" } ],    // 49.00 once
  "bundledProductOffering": [
    // fixed inclusions — the base price covers them
    { "name": "GenAlpha Fiber 1000",
      "bundledProductOfferingOption": { "numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 1 } },
    { "name": "GenAlpha TV Max",
      "bundledProductOfferingOption": { "numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 1 } },
    // pick 1–2 — multi-select, each option priced on its own offering
    { "@type": "BundledProductOfferingChoice",
      "name": "Family lines — how many do you need?",
      "numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 2,
      "default": "<Mobile 50 GB id>",
      "options": [ "<Mobile 10 GB>", "<Mobile 50 GB>", "<Unlimited 5G>" ] },
    // pick exactly 1 — options carry color/storage characteristics on their specs
    { "@type": "BundledProductOfferingChoice",
      "name": "Choose your phone",
      "numberRelOfferLowerLimit": 1, "numberRelOfferUpperLimit": 1,
      "options": [ "<iPhone 17 Pro>", "<iPhone 17>", "<Galaxy S26>" ] },
    // pick up to 2 — entirely optional
    { "@type": "BundledProductOfferingChoice",
      "name": "Streaming extras",
      "numberRelOfferLowerLimit": 0, "numberRelOfferUpperLimit": 2,
      "options": [ "<Sports Pass>", "<Kids TV>" ] }
  ]
}
```

## How the order is shaped

The storefront sends the bundle as the parent item and the CHOSEN options as
child items (fixed components stay implicit — that's what keeps the bundle
price single-counted):

```json
{ "productOrderItem": [{
    "action": "add",
    "productOffering": { "id": "<family-max>" },
    "productOrderItem": [
      { "action": "add", "productOffering": { "id": "<mobile-10>" } },
      { "action": "add", "productOffering": { "id": "<mobile-50>" } },
      { "action": "add", "productOffering": { "id": "<galaxy-s26>" },
        "product": { "productCharacteristic": [
          { "name": "color", "value": "Icy Blue" },
          { "name": "storage", "value": "512GB" } ] } }
    ]
}]}
```

Send three family lines and TMF622 answers
`bundle 'GenAlpha Family Max': 'Family lines — how many do you need?' requires
between 1 and 2 selection(s), but 3 were made` — the same rule the UI enforced
with a disabled checkbox. `ops/e2e/family_max_test.js` proves both layers.

## Where to author it

- **Console** (`/console` → Product Offerings): the **bundle composer** builds
  everything above by clicking — add components and mark them Included or
  Optional, add choice groups with a name, "pick min–max" numbers, an options
  picker and a default, choose categories and a commitment term. No JSON.
  Anything the composer doesn't understand survives a save untouched
  ("advanced entries kept as-is"). Business rules about the bundle (quantity
  caps, incompatibilities, dynamic pricing) live in the **Rules** tab —
  see [product-rules.md](product-rules.md).
- **API / seed scripts**: everything above is plain TMF620 REST —
  `seed_family_max.py` is the copy-paste template (idempotent, self-healing).

## After the sale

Completion decomposes the order: the bundle parent and every chosen option
become inventory products (the customer's My page nests the components under
the bundle), the SOM activates a service per item and mints SIMs for numbered
lines, the 12-month agreement starts, and billing rates the base price, the
one-time fee, and each option's price onto one bill. A bundle component in a
plan category (the fiber tier, a family line) can later be changed
like-for-like from My page — same line, same number — while the commitment
binds the bundle offering itself.

## Pictures and facts — the product content layer

Devices sell on imagery, so every offering can carry it — as plain TMF620
data the channels read like anything else:

- **`attachment`** on the offering is the imagery list: `{name, mimeType,
  url}`. Names carry semantics: `gallery-*` entries render as the product
  page's photo gallery (hero + thumbnails, and the bundle configurator shows
  each phone option's first image as a thumbnail); `variant-<colour>` entries
  follow the colour picker — pick *Icy Blue* and the hero swaps to that shot.
- **Facts** (display, camera, battery, weight) are spec characteristics with
  `configurable: false` — the storefront renders them as an "About this
  device" table instead of a dropdown. Anything configurable (colour,
  storage) stays a picker, and a standalone device's pick rides the cart
  line as product characteristics.
- **Authoring**: the console's offering form has an *Artwork* control —
  upload an image, it lands in the document component (TMF667) and on the
  offering's attachment list. `seed_device_content.py` shows the same via
  the API.

### Bring your own PIM

Operators who already run a product-information system (Akeneo, inRiver, a
homegrown DAM) keep it: the catalog has a per-tenant content seam
(`pim-base-url` in the tenant registry). When set, offering reads resolve
imagery live from that system — keyed by **product name**, since the
operator's PIM has never heard of our UUIDs — cached for a minute and
failing open to whatever the catalog stores. The demo deployment ships
`mock-pim` and wires **nova** to it: create "Nordic Phone X" in nova's
catalog and its gallery arrives from the external PIM with nothing authored
in the BSS. GenAlpha, one env var away, keeps the internal store. The
channels cannot tell the difference — that is the point.
