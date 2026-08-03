# TMF668 + TMF674 — the thin tail: partners typed, places named — plan

*2026-08-03, the overnight close of the TMF gap list. Two small arcs,
one method: find the substance the fleet already runs, give it the
standard face, add only the small honest piece that's genuinely
missing. TMF668 Partnership Type Management (partnership types + the
role types they permit — the onboarding API); TMF674 Geographic Site
Management (named, reusable places attached to parties — the branch
address book nobody has).*

## Research findings

- **TMF668**: the fleet is full of partners with no vocabulary for
  them. Dealers are rows (dealer_agreement: org + commission + client,
  power derived from facts not roles); bill-distribution partners are
  per-tenant wire config; event-hub subscribers are partner
  registrations. And the agreement service already speaks
  "parties in named roles" (engagedParty JSON with role +
  @referredType) — but nothing DEFINES which roles a kind of
  partnership permits. A partnership type is exactly that missing
  definition, and validation against it is the honest new substance:
  today an agreement accepts any role string anyone invents.
- **TMF674**: nothing models a named site anywhere. Addresses are
  flat, unnamed, unowned rows; places ride orders and appointments
  per-transaction and are stored verbatim downstream; the B2B org
  model (Organization + membership resolved live, never trusted from
  the request) has no address book — a branch's address is retyped
  into every order. The site is the first reusable named place:
  name + relatedParty + place + status.
- **Hosting**: partnership types + instance validation on the
  `agreement` service (it owns the instance semantics; types are
  tenant catalog data behind the existing write gate). Sites on
  `geographic-address` (the scaffold twin, `address:*` scopes already
  provisioned) with `place` referencing an existing geographic_address
  row — TMF674 leaning on TMF673 exactly as the specs intend.

## The design

**TMF668** at `/tmf-api/partnershipTypeManagement/v4` on agreement:
`partnershipType` CRUD — `{name, description, status, roleType:[{name,
description?}]}`, reads agreement:read, writes agreement:write. The
substance: an agreement with `agreementType: "partnership"` whose
characteristic names a `partnershipTypeId` is VALIDATED at create —
every engagedParty role must be one the type permits, and a bad role
is refused naming the allowed list. The dealer chain becomes the
first typed partnership in the suite.

**TMF674** at `/tmf-api/geographicSiteManagement/v4` on
geographic-address: `geographicSite` CRUD + PATCH —
`{name, description?, status: planned|active|retired,
relatedParty:[{id,role}], place:{id → geographic_address ref}}`,
reads address:read, writes address:write, RLS-walled. The suite
proves the address-book story: the branch address entered ONCE as an
address + site, then reused on an order whose fulfilment parcel
carries it — no retyping.

## The phases

### P1 — TMF668 (suite #83)

Migration (partnership_type + RLS), entity/repo/service/controller,
agreement-create validation hook, gateway path, suite: type
"retail-dealer" permits provider|dealer; a partnership agreement with
those roles signs; a "smuggler" role is refused naming the permitted
list; an untyped commercial agreement is untouched (no ceremony where
none is due); customer cannot author types; nova isolation.

### P2 — TMF674 (suite #84)

Migration (geographic_site + RLS), entity/repo/service/controller,
gateway path, suite: two addresses + two named Acme sites (HQ active,
Branch planned); list by relatedPartyId; PATCH branch → active; the
HQ site's address rides an order and the fulfilment parcel carries it
(entered once, reused); retire + delete; customer 403; nova isolation.

Regressions serial after both: b2b, guest. Books: one chapter + one
manual section for the pair; counts to 84.

## Shipped

**P1 — 2026-08-04 (overnight), suite #83 green (four legs), first
run.** TMF668 lives at `/tmf-api/partnershipTypeManagement/v4` on the
agreement service, riding the agreement scopes. A kind without roles
is refused at authoring ("a partnership kind IS the roles it
permits"); a typed partnership agreement is validated at signature —
the "smuggler" role was refused NAMING the permitted list
(provider, dealer), a ghost type refused outright — and a plain
commercial agreement with an arbitrary role signed exactly as before:
the type system binds only those who claim a type. Customer 403 on
authoring; nova isolation; delete is first-class.

**P2 — 2026-08-04 (overnight), suite #84 green (four legs), first
run.** TMF674 lives at `/tmf-api/geographicSiteManagement/v4` on
geographic-address, leaning on TMF673 exactly as the specs intend: a
site's place MUST reference a stored geographicAddress (a bogus ref
400s) and the address is EMBEDDED on every read. Lifecycle
planned→active→retired enforced as vocabulary (an invented status
400s); the org filter lists exactly its own sites. The address-book
story proven end to end: the HQ address — typed once when the site
was born — rode kai's order and landed intact on fulfilment's parcel.
Customers 403 (the address book is back-office); nova sees nothing;
a retired branch deletes.

Regressions (serial): b2b green; guest FAILED first and the failure
was a real latent bug from the TMF645 arc — the cart's new footprint
line reused the `serviceability ok` CSS classes, so Playwright's
strict locator resolved to TWO elements whenever the footprint fetch
won the race. Fixed deterministically: the footprint line got its own
`serviceability footprint` class (+ CSS); guest green on re-run. The
TMF gap list is CLOSED.
