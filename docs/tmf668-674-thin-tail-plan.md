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

*(recorded as the phases land)*
