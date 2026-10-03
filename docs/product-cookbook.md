# The product cookbook: how to build each kind of thing, on the screens

For a product manager sitting in the back office. Eleven recipes, each one saying
what to create, in what order, and — as often as not — what **not** to create.

Written because the same catalogue currently models one phone two contradictory
ways, so whichever you open first teaches you the opposite lesson.

---

## First, three words that mean more than one thing

These trip everyone. None of them is your fault.

**"Bundle" means three unrelated things on your screens.**

| Where you see it | What it actually means |
|---|---|
| **Is a bundle** — checkbox on the *offering* | this offering is made of other offerings (a Home + Mobile pack). Tick it, then fill **Bundle composition** |
| **Bundle price** — checkbox on the *price* | a composite price. Nothing to do with the above. Leave it alone unless you are pricing a package as one figure |
| **Bundles** — a *category* | just a shelf in the shop. A label, no behaviour |

**One more that catches everyone: the "Item" field on a rule appears for exactly
one rule kind.** Pick *Price: discount / surcharge when the cart has an item*
and an Item selector appears. Pick any other kind — for everyone, a company
deal, a colour campaign — and there is **no Item field at all**, because those
attach to something other than one product. Nothing on screen explains it.

**"Specification" versus "offering".** The specification is *what the thing
is* — its characteristics and how it gets provisioned. The offering is *the
deal* — price, channels, dates. One specification, many offerings.

**"Fulfilment"** on the specification is the pattern the orchestrator runs. It
belongs on the specification, not the offering, because a phone is shipped the
same way whether you sell it at full price or in a campaign.

---

## Recipe 0 — which fulfilment pattern do I pick?

The dropdown on the **specification**. Pick by *how the thing is delivered*,
not by what it is called.

| Pattern | Pick it when | What it runs |
|---|---|---|
| **Mobile line** | a line with a number the customer can be called on | 4 steps: number, SIM, charging, and more |
| **Broadband access** | a fixed line installed at an address | 2 steps |
| **TV entitlement** | switch something on for an existing customer | nothing on the network |
| **Device shipment** | a parcel leaves a warehouse | nothing on the network |
| **Partner activation** | someone else's platform owns the account, we hold the code | 1 step |
| **Security feature** | a flag on a line they already have | nothing on the network |
| **Priority slice** | a network slice sold as a product | 1 step |
| **Edge inference** | compute at the edge | 1 step |
| **Billing-only product** | it bills and nothing is provisioned | nothing |
| **Top-up with boost** | a top-up that also lifts an existing line | 1 step |
| **(none)** | **avoid.** The orchestrator then guesses from the category | — |

Underneath the dropdown the screen spells out what that pattern will read off
your specification. **Read that sentence.** If it names a characteristic you
have not filled in, the order will not fulfil.

Changing the pattern on a live specification is a governed action with a
receipt, not a quiet edit — because you are changing how real orders provision.

---

## Recipe 1 — a phone that comes in colours and sizes

**The question everyone asks: is 256 GB an offering, an add-on, or a product?**

**None of those. It is a value of a characteristic.**

1. **One specification**, "iPhone DUO".
2. On it, **characteristics** with their values:
   - `color` → Deep Blue, Silver, Graphite
   - `storage` → 256GB, 512GB, 1TB
3. **One offering** over that specification, with the base price.
4. For any combination that costs more, **one extra price** with **Applies only when** set to that value — e.g. `storage = 1TB`, +200.

Nine purchasable combinations. **One specification, one offering, two or three
prices.** Stock is tracked per variant, so you can be out of blue without being
out of the phone.

> ⚠️ Your catalogue today contains the wrong pattern too: a second
> "Apple iPhone 17 Pro" specification with **no characteristics**, sold as a
> separate "Apple iPhone 17 Pro 256GB" offering. **Do not copy it.** That is
> where a product-per-colour explosion starts: 3 colours × 3 sizes = 9
> offerings to maintain, nine price lists, nine things to retire.

**The rule:** if two things are provisioned identically and differ only in
something the customer picks, they are **variants of one specification**.

---

## Recipe 2 — a mobile plan

1. Specification "Mobile 20 GB", fulfilment **Mobile line**.
2. Characteristics the pattern asks for (read the sentence under the dropdown).
3. One offering, category **Mobile plans**, a recurring price.
4. Channels: where it is sold. Nothing ticked = everywhere.

---

## Recipe 3 — an add-on (a TV pack, extra data)

An add-on is a normal offering that only makes sense **on top of** something.

1. Specification, fulfilment **TV entitlement** (or **Billing-only product**
   if literally nothing is switched on).
2. Offering, category **TV & Add-ons**.
3. On the offering, **Requires / excludes** → requires the thing it rides on.

That relationship is what stops someone selling a sports pack to a customer
with no TV.

---

## Recipe 4 — a bundle (Home + Mobile)

1. Create each component as its own offering **first**. A bundle composes
   offerings that already exist.
2. New offering, **tick "Is a bundle"**.
3. **Bundle composition** → add the components, mark each mandatory or optional.
4. Price the bundle on the bundle offering.
5. Category **Bundles**.

Do **not** create a specification describing the whole bundle. The bundle is a
commercial wrapper; each component keeps its own fulfilment, and the
orchestrator provisions them separately.

---

## Recipe 5 — a streaming or partner service

1. Specification, fulfilment **Partner activation**.
2. Offering, category **Partner services**.

> ⚠️ **Known gap today:** you cannot yet say *which* partner. The pattern
> activates "a partner" — there is one partner connection for the whole
> deployment and the offering's name is what gets passed to it. Naming Netflix
> rather than Viaplay needs the seam to consume a partner characteristic, and
> that is not built. Raise it before promising a specific partner.

---

## Recipe 6 — a one-off top-up

1. Specification, fulfilment **Billing-only product**, or **Top-up with boost**
   if it should also lift an existing line.
2. Offering, category **Top-ups**, a one-time price.

---

## Recipe 7 — equipment you manage (router, mesh point, set-top box)

1. Specification, fulfilment **Device shipment** for now.
2. Offering, category **Equipment**.

> ⚠️ **Known gap:** equipment managed for its whole life (restart, firmware)
> has no fulfilment pattern of its own yet, even though the machinery exists.
> "Device shipment" ends when the parcel arrives. Until an equipment pattern is
> added, a mesh point is modelled as a shipped device.

---

## Recipe 8 — a campaign price on something you already sell

Do **not** clone the offering. Add a second, dated **price** to the one you
have — full recipe and the three other kinds of discount in **Recipe 10**.

If you find yourself copying an offering to change a price, stop. That is the
signal something is modelled wrong.

---

## Recipe 9 — retiring something

Retire the **offering** to stop selling it. The specification stays, because
customers still own products built on it and their services still reference it.

Retire the specification only when nothing is live on it.

---

## Recipe 10 — a discount

**You never type a negative price.** Prices stay positive; a discount is a
*rule* or a *second price*. That is deliberate: a negative price cannot be
reasoned about, cannot be audited, and cannot be switched off without editing
the thing everyone is buying.

There is no single "discounts" screen. **Which of four places you use depends
on what makes the discount apply.** Work down this table — the first row that
matches is your answer.

| If the discount applies… | Go to | Department |
|---|---|---|
| in a date window, or to one variant | **the offering's own Prices** | Catalog & Pricing |
| because they bought a lot | **Volume pricing** | Sales setup |
| because of who they are, or what else is in the basket | **Rules** | Privacy & governance |
| because they typed a code | **Campaigns** | Marketing |

### 10a — a campaign price (the common one)

*"iPhone DUO is 200 off through December."*

Do **not** clone the offering. On the existing offering, **add a second price**:

- Price: the campaign amount
- **Price from** 1 Dec, **Price until** 31 Dec
- leave **Applies only when** empty — it is for every variant

Both prices now live on the offering. The dated one wins inside its window and
stops on its own. Nothing to remember to undo.

*"…but only the 1 TB one."* Same, plus **Applies only when** → `storage = 1TB`.

### 10b — buy more, pay less

*"10 seats: 10% off. 50 seats: 20% off."*

**Sales setup → Volume pricing.** One row per tier:

| Offering | From quantity | Discount % |
|---|---|---|
| Mobile 20 GB | 10 | 10 |
| Mobile 20 GB | 50 | 20 |

There is also an optional **CDP segment** on that row — a trait value that
beats the volume tier when it matches.

### 10c — conditional on who they are, or what is in the basket

**Privacy & governance → Rules.** Pick the kind that matches the condition,
then set **Adjustment value** — *negative is a discount, positive a surcharge* —
and a **Message** the customer sees.

| Want | Rule kind |
|---|---|
| Cheaper when they already have broadband | Price: discount/surcharge when the cart has an item |
| Cheaper for identity-verified customers | Price: discount/surcharge for verified customers |
| Gold tier pays less | Loyalty tier benefit |
| A negotiated rate for one company | Price: negotiated deal for one company (B2B) |
| Any company with enough people billing together | Price: volume deal (B2B) |
| A campaign on one configured choice, e.g. a colour | Price: campaign on a configured choice |
| Everyone, right now | Price: discount/surcharge for everyone |
| Something none of the above expresses | Price: advanced — raw JSON-logic |

*Worked example — "Icy Blue is 100 off this month":* Rules → **Price: campaign
on a configured choice** → Characteristic `color`, Value `Icy Blue`,
Adjustment value `-100`, Message "Icy Blue campaign".

*Worked example — "Acme pays 15% less on every mobile plan":* Rules →
**Price: negotiated deal for one company** → Company = Acme, adjustment −15%.
This is the B2B contract case; it follows the organization, not the person.

### 10d — a code the customer types

**Marketing → Campaigns.** The promotional code is issued there and included
in the message that carries it. Use this only when the *code itself* is the
trigger; if the discount should apply without anyone typing anything, it
belongs in one of the three above.

### Which one, when two would work

Prefer the **narrowest** thing that expresses the intent:

1. A **dated price** if the rule is "for a while" — it expires by itself.
2. **Volume pricing** if the rule is purely "how many".
3. A **rule** if it depends on the customer, the basket or a contract.
4. A **code** only if a human types it.

A dated price is the safest because it stops on its own. A rule runs until
someone turns it off, so give it a name that says when it should die.

> ⚠️ **Worth knowing:** those four screens sit on three different departments,
> so there is no one place to see every discount a customer might get. If you
> are hunting for "where are all my discounts", that is why. It is a known
> weakness of following the internal model rather than the task.

**Laws still win.** Statutory rules are data a tenant cannot undercut, so a
discount cannot take a price below what the market's rules allow.

## The order that always works

```
1. Specification   — what it IS, and how it is fulfilled
2. Characteristics — the variants, on the specification
3. Offering        — the deal: dates, channels, category
4. Prices          — base price, then conditioned prices for variants
5. Relationships   — requires / excludes, if it rides on something
6. Launch          — governance checks it can actually be fulfilled here
```

Launch governance will refuse a product whose fulfilment plan cannot run in
this deployment. That refusal is a feature: it is the last moment the mistake
is cheap.

---

## Honest limits

- **The "Is a bundle" / "Bundle price" collision is real** and not explained
  anywhere on screen. Both are standard field names on two different resources;
  the screens inherit the clash.
- **The contradictory iPhone specifications are live in the demo catalogue
  right now.** This cookbook says which one is right; nothing in the product
  enforces it yet.
- **You cannot create a category from the console** — the offering form only
  picks from existing ones. New shelves need a developer today.
- **Naming a specific partner is not possible yet** (Recipe 5), and equipment
  has no pattern of its own (Recipe 7).
- Recipes are written against the demo catalogue on one deployment. An operator
  with their own product set will need different categories, and possibly
  different patterns.
