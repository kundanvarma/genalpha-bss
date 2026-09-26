# What kind of thing is this? — the taxonomy gaps behind "Service" and "service area"

Research, 26 September 2026. Written after two rows on the agent desk read
**"Wi-Fi Mesh Point · Service"** and **"NFL Season Ticket Package · Service"**,
where "Service" is not a type but the word the desk prints when it cannot tell.

This is not a display bug. Three separate taxonomies describe the same thing
and none of them agrees with the others.

## The three lists

| | Where it lives | Entries | Changed by |
|---|---|---|---|
| **Fulfilment families** | `CatalogClient.Cfs.FAMILIES`, Java | 8 | a code change |
| **Retail categories** | product offerings, data | 11 in use | a product manager |
| **Desk kinds** | `KIND_WORDS` in the agent console, JavaScript | 5 | a code change |

**Families** (how a thing is provisioned): `mobile`, `internet`, `tv`,
`device`, `partner`, `security`, `compute`, `billing-only`.

**Categories** (how a thing is sold): Mobile plans, Broadband, TV & Add-ons,
Devices, Partner services, Security, Insurance, Top-ups, Bundles, Wholesale
access, Wholesale mobile.

**Desk kinds** (how a thing is shown): Mobile, Broadband, TV, Fixed voice,
Service.

Only **8 of the 11** categories map to a family. And the desk's five kinds are
derived by pattern-matching a service's name and category against four regular
expressions — it never reads the family the catalog declares.

## What that produces today

- **`Fixed voice` is a chip the catalog cannot sell.** The desk will render it;
  no family, category or CFS anywhere produces it. It is a word with nothing
  behind it.
- **`cpe` is a seam with no family.** Eight seam adapters are built and `cpe` is
  one of them — the router and set-top box managed over the ACS, with restart
  and firmware. But no family names it as its primary shape, so a Wi-Fi mesh
  point has nowhere to go. It is not `device`: a device is a parcel that ships
  and is done, while a mesh point is managed for its whole life.
- **An offering with no category becomes the literal word "service".** The
  orchestrator asks the catalog for a category, gets nothing, and the service is
  stored with category `service` — which is why the specification reads
  `service service` and the chip reads `Service`. Thirteen offerings in the demo
  catalog carry no category at all.
- **`Bundles` and the two wholesale categories map to no family**, so anything
  sold under them relies on other paths.

## The families that are missing, by fulfilment shape

A family should answer *how is this provisioned*, not *how is this marketed*.
Judged that way, four shapes have no home.

| Missing | The shape | Why it is not an existing family | Cost |
|---|---|---|---|
| **cpe** | ship or swap, then manage for life over ACS: restart, firmware, Wi-Fi clients | `device` ends when the parcel arrives; this one begins there. **The seam already exists** | small — a family label and a CFS; no new code |
| **voice** | a fixed number, no SIM, usually riding the access already installed | `mobile` means a SIM and an online-charging subscriber; a fixed line has neither | small — reuses the number seam |
| **iot** | bulk SIM, often no dialable number, rated differently, managed in fleets | a smartwatch or tracker is a line, but a `mobile` line assumes one human with one number | medium — needs a fleet view |
| **energy** | register a meter point with the grid operator, rate consumption | nothing in the current set resembles it | large — a new seam and a new rating shape |

**Insurance is mis-shelved rather than missing.** It maps to `billing-only`,
meaning nothing is provisioned. Real protection products register a device
serial and open a policy with an underwriter — the shape of a `partner`
activation. Today it bills and does nothing else, which is a deliberate
simplification but not a true one.

**Content subscriptions are a categorisation error, not a gap.** An NFL season
ticket or a streaming pass is a `partner` activation and already fits. It read
as "Service" only because that offering declared no category.

## What I would do, in order

1. **Give the desk the declared answer.** The console should read the
   `fulfilmentFamily` the catalog states and fall back to guessing only when it
   is absent. Today the catalog knows and the desk still guesses. Cheapest fix,
   and it makes every later step visible.
2. **Refuse an offering with no category** at launch, the way launch governance
   already refuses a product whose plan cannot be fulfilled. "Service" as a
   category should be impossible to save, not merely unhelpful.
3. **Add `cpe` as a family.** The seam is built and idle; this is a label and a
   CFS, and it fixes the Wi-Fi mesh point outright.
4. **Add `voice`, or delete the chip.** One or the other — a word on screen with
   nothing behind it is worse than either.
5. **Decide `iot` and `energy` deliberately.** Both are real markets and neither
   is a weekend. They belong in a spec of their own, not smuggled in as labels.
6. **Map the unmapped categories** — Bundles, Wholesale access, Wholesale
   mobile — or state in one place why they have no family.

## Which categories to create — a concrete proposal

### First, the distinction the proposal turns on

There are **two lists**, and confusing them is why this looks harder than it is.

| | What it answers | Where it lives | Who changes it |
|---|---|---|---|
| **Retail category** | how is this **sold** | TMF620 `Category`, a first-class entity — 15 exist, the endpoint answers 200 | a product manager, **no code** |
| **Fulfilment family** | how is this **provisioned** | a Java `Set` of 8 | a developer |

Categories are already data, already creatable through the API, and already
support `parentId` — **which nothing uses today: all 15 are flat.**

### The evidence

40 real offerings (suite debris excluded), across 11 categories. Three carry no
category at all, and two of those three have nowhere to go:

| Offering | Should be | Today |
|---|---|---|
| **Edge AI Inferencing** | compute | **no category exists for it** |
| **Stadium 5G Slice** | a network product | **no category exists for it** |
| Apple iPhone 17 Pro 256GB | Devices | an oversight, not a gap |

The `compute` family has existed since step 3 and has **no retail category to
sell it through**. That is the clearest proof the two lists drifted.

### Create now — retail categories, data, no code

| Category | Why it is not an existing one | Evidence |
|---|---|---|
| **Equipment** | a router, mesh point or set-top box is **managed for its whole life** over the ACS. "Devices" is a handset that ships and is done. Different shopping intent, different fulfilment | the Wi-Fi Mesh Point reading "Service" |
| **Edge & compute** | nothing in the list describes compute sold by the operator | Edge AI Inferencing is homeless |
| **Network services** | a venue slice or priority profile is a **B2B network product**, not a consumer mobile plan | Stadium 5G Slice is homeless |

Three categories, created through the catalog, today, by a product manager.

### Create with the family — needs code first

| Category | Family needed | Note |
|---|---|---|
| **Fixed voice** | `voice` | **Only create the category if the family follows.** The desk already renders a "Fixed voice" chip with nothing behind it; a second empty shelf makes it worse, not better |
| **Connected things** | `iot` | a watch or tracker is a line, but `mobile` assumes one human with one number |
| **Energy** | `energy` | a meter point with a grid operator; a genuinely new seam |

### Fix rather than create

- **Insurance → `billing-only`** is mis-shelved. Protection registers a device serial and opens a policy with an underwriter — the shape of a `partner` activation. The category is right; the family is wrong.
- **Bundles** is not a category of *thing*, it is a container that decomposes. Keep it, and state in one place that it maps to no family **on purpose**.
- **Wholesale access / Wholesale mobile** are B2B2B with their own service specs. Keep them; record why they sit outside the retail family map.

### Use the nesting that already exists

All 15 categories are flat, yet `parentId` is there. A two-level shelf costs
nothing and makes the shop and the desk legible:

```
Connectivity          Mobile plans · Broadband · Fixed voice · Network services
Entertainment         TV & Add-ons · Partner services
Hardware              Devices · Equipment
Add-ons & protection  Top-ups · Security · Insurance
Business              Wholesale access · Wholesale mobile · Edge & compute
Bundles               (a container, not a family)
```

### Recommended order

1. **Three categories now** — Equipment, Edge & compute, Network services. Data, no code, and it gives two homeless products a home.
2. **Categorise the iPhone**, and make an offering with no category impossible to launch (it is what becomes the literal word "service").
3. **`cpe` as a family** — the seam is built and idle; this is the highest value for the least code, and it fixes the mesh point outright.
4. **Decide `voice`** — build the family or delete the chip. Do not create the category alone.
5. **Nest the shelf** using `parentId`.
6. **`iot` and `energy` deliberately**, in their own spec. Neither is a weekend.

### Honest limits

- Drawn from **one tenant's** 40 offerings on a laptop. An operator with a real product set would surface different gaps — and might well merge Equipment back into Devices if they never manage CPE.
- "Create a category" is only cheap because categories are data. The moment a category needs its **own fulfilment**, it stops being cheap and becomes a family, which is code.
- The nesting proposal is a suggestion about shelves, not a claim about anything the code enforces. Nothing reads `parentId` today, so adopting it means somebody must start.

## Honest limits

- This is a reading of the demo catalog on one laptop and one hosted box, not of
  an operator's real product set. A different operator would surface different
  gaps.
- The counted-fallback metric currently reads **0**, so nothing is falling back
  to the category table right now. The gaps here are ones that have not yet been
  hit in anger — the products that would hit them are the ones nobody has tried
  to sell.
- "Missing" is judged by fulfilment shape. Somebody optimising for how a shop
  browses would draw the lines elsewhere, and the two taxonomies do not have to
  match — they only have to know about each other, which today they do not.
- Nothing in this document is built. It is a reading and a recommendation.
