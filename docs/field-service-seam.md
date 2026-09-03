# The field-service seam: who answers "when can an installer come?"

## What the industry does

**TM Forum draws the line between BSS and workforce.** In the Open Digital
Architecture the calendar of technicians is not a BSS concern. It belongs to
the Production block's **Workforce Management** component (TMFC046), which
"provides capabilities to describe teams, organizations, skills and availability
of sales / technical experts … the ability in reserving time slots to carry out
assigned task … the reservation (and their updates / reschedule) of appointments
and interventions" (TMFC046 v1.1.1, eTOM 1.5.4.8 *Manage Field Workforce*).

Its **one mandatory exposed API is TMF646 Appointment Management** — the
`appointment` resource (GET/POST/PATCH/DELETE) and the `searchTimeSlot` task.
Work Order Management (TMFC061) also requires TMF646; Service Order Management
(TMFC007) lists it as optional. In other words: *every* ODA component that
needs a visit asks for it over TMF646, and the component that owns crews
answers over TMF646.

**Vendors converge on the same shape.** ServiceNow Field Service Management for
Telecommunications exposes appointment create/reschedule/cancel over the TMF
APIs. Oracle Field Service answers "when?" through its Capacity API
(`showBookingGrid` / activity booking options) against *capacity categories* —
a work-skill group with a quota per date, time slot and work zone. Salesforce
Field Service does it through appointment booking with scheduling policies
(`getSlots`). All three model the same three ideas:

| Idea | TMF646 field | Oracle FS | Salesforce FS |
|---|---|---|---|
| where | `relatedPlace` | work zone (postcode → bucket) | service territory |
| what kind of work | `category` | capacity category / work-skill group | work type + required skills |
| when | `requestedTimeSlot` → `availableTimeSlot` | booking grid | arrival windows |

**Best practice, distilled.**
1. The BSS never invents a calendar it does not own. It asks, shows what it is told, books, and keeps the external reference (`externalId`).
2. The ask carries *where* and *for what*, so the workforce system can answer by zone and skill.
3. A booking is a two-phase thing at scale (hold → confirm on order placement). TMF646 v5 makes `searchTimeSlot` an explicit task resource for this reason.
4. A dark provider is an error the customer sees honestly ("we can't offer times right now"), never a silently substituted roster.
5. The work order (TMF697) is born from the appointment event — the visit and the appointment are two resources with one lifecycle.

## What genalpha-bss does

The `appointment` component is TMF646 outward in both directions, and one
interface inward:

```
storefront / CSR / SOM ──TMF646──▶ appointment ──ScheduleProvider──▶ roster (built-in)
                                                                  └─▶ tmf646 → the operator's WFM (TMFC046)
```

`ScheduleProvider` has four operations: `search(config, request)`,
`book(config, request)`, `cancel(config, externalId)`, `probe(config)`.

- **`roster`** (default): the tenant's calendar (timezone, working days, window starts, horizon) plus a technician table. Capacity per window is *derived* — active technicians whose shift covers it. No roster → flat default capacity.
- **`tmf646`**: delegates to `providerUrl` with the same TMF646 shapes. `POST {url}/searchTimeSlot` with `relatedPlace`, `relatedEntity`, `relatedParty`, `requestedTimeSlot`, `category` (= `providerCategory`); `POST {url}/appointment` to book (the answer's `id` is stored as `externalId`); `PATCH {url}/appointment/{id}` `{status: cancelled}`. Credential = the environment variable named by `providerSecretRef`, sent as a bearer token. Every failure is a **502**, never a fallback.

Per tenant, in the console's *Installers* card or over the API:

```
PUT /tmf-api/appointment/v4/scheduleConfig
{ "provider": "tmf646", "providerUrl": "https://fsm.operator.example/tmf-api/appointment/v4",
  "providerSecretRef": "FSM_TOKEN", "providerCategory": "fibre-install" }
POST /tmf-api/appointment/v4/scheduleConfig/test      → { ok, detail }
```

The storefront's slot search now sends the install address as `relatedPlace`
and the serviceability-gated offerings as `relatedEntity`, so a zone- or
skill-aware provider has what it needs; the built-in roster ignores what it
does not use. Booked appointments read back with `provider` and `externalId`;
cancel propagates to the provider before our row flips.

**Dev stand-in:** `mock-fsm` (`integrations/mock-fsm`, :8151) speaks TMF646
with its own crews per Guyana postcode region digit (4 = Georgetown ×2, 7 = Bartica ×1) and
half-hour window starts — visibly *not* the BSS roster.

## What is deliberately not here

Oracle- and Salesforce-native adapters (same interface, vendor wire shapes),
slot holds with expiry, technician assignment, routing and van stock. Those
belong to the workforce component; the seam is where they plug in.
