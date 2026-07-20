# Family experience v3 — research and design plan

Status: **Phases 1–2 delivered** (family hub + roles, suite #29; allowances/ask-to-buy + gifting + rollover, suite #30 `family_phase2_test.js`) · Phase 3 pending · 2026-07-14

The household feature today is plumbing-first: consent links, payer stamps,
correct bills. It is honest but it is not an *experience*. This plan starts
from how the operators people actually live with — Jio, Orange, Nordic CSPs (Telenor and peers),
Verizon, T-Mobile — present a family, and lands on a design for genalpha-bss.

---

## 1. What the big operators do

### Jio (India) — the parent SIM is a hub
- MyJio on the **parent number lists every linked number**; the parent selects
  any member's line and manages it from one place ("Plan Details → Manage
  Plan"). The family is a *navigable object*, not a label on a bill.
- Adding a member sends an **OTP to the member's own phone** — consent is
  built into the add flow, exactly like our two-step accept.
- Data sharing is offered in **simple fixed chunks** (5/10/15/20 % of the
  parent's quota per member) — coarse controls people understand, not free-form
  configuration.

### Orange (France) — the "co-pilot" second parent
- Packs group up to 4 mobile lines under one contract with **centralized,
  per-person consumption tracking**.
- Parental supervision explicitly supports **co-pilots**: a second parent gets
  the same app access, settings and notifications for the children's accounts.
  This is the direct precedent for "my wife can also be the admin".
- Parental control ships **included in every plan** — protection is a default,
  not an add-on.

### Nordic CSPs (Norway) — the invisible family
- Two or more subscriptions on one payer **automatically form a family** —
  zero setup ceremony. One invoice, per-line cost control, data sharing.
- Members can be added and removed at no cost; the family is fluid.

### Verizon / T-Mobile (US) — the role model (literally)
- Verizon has the cleanest role hierarchy in the industry:
  - **Account Owner** — everything, exactly one.
  - **Account Managers** — up to 3, can do nearly everything the owner can
    *except* remove other managers or change the owner's credentials. A
    manager doesn't even need a line on the account.
  - **Account Members** — see and manage **only their own line**.
- T-Mobile **Family Allowances**: the owner sets per-line caps (data,
  purchases); managed lines can *view* their allowance but not change it.

### Google Family Link — the gold standard for purchase approval
- **Ask-to-buy**: the child taps "buy", sees "ask your parent", the parent
  gets a push notification with the item and price, and approves or denies
  from an approvals inbox. No shared payment method needed.
- The key insight: the child keeps a *first-class buying UI* — the purchase
  intent is captured and routed, never blocked with a dead end.

### Patterns worth stealing
1. **A family hub, not scattered labels.** Every operator gives the payer one
   place that lists all members and lets them drill into each line.
2. **Three roles: owner, admin(s), member** — with children as members plus
   guardrails. Admins ≈ owner minus "manage other admins / change payer".
3. **Consent at join, freedom at leave** (Jio OTP, our accept flow — keep it).
4. **Allowances are standing rules; approvals are the exception path.**
   Pre-approve up to a cap (T-Mobile), route anything above it as an
   ask-to-buy request (Family Link).
5. **Coarse, legible controls.** Fixed top-up caps and simple toggles beat
   configuration matrices.
6. **My page stays personal.** Members see their own services plus a "paid by"
   note; the *family* view is where cross-member visibility lives.

---

## 2. Design for genalpha-bss

### 2.1 The two options in the request, settled by the research

*Option A — show every family sub on my page with owner labels* mixes two
mental models (what I use vs. what I pay for) on one page; no major operator
does it. *Option B — a family space where the payer/admin opens each member*
is what Jio, Verizon and Orange all converge on. **Go with B**, keeping a
compact "You pay for N services · €X/mo" summary card on My page that links
into the hub.

### 2.2 Roles

| Role | Who | Can |
|---|---|---|
| **Owner** | the household payer (Paula) | everything: add/remove members, promote/demote admins, order for members, set allowances, approve requests, end the household |
| **Admin** | promoted member (the wife) | everything the owner can **except** promote/demote admins, remove the owner, or change who pays |
| **Member** | consenting adult (Sonny at 19) | own line only; sees "paid by" on funded services; can request top-ups |
| **Child** | payer-created account (under 18, from `birthDate`) | own line; purchases always route through allowance/approval |

Model: extend the existing household link (party V12) with
`household_role` (`admin` | `member` | `child`). The owner *is*
`household_payer_id` — no new table. Admin ≠ payer: promoting the wife does
not touch billing; funding stays per-product payer stamps.

### 2.3 The Family hub

New top-nav tab **Family** (visible to anyone in a household; content varies
by role) on storefront *and* app:

- **Member cards**: name, role chip, their funded services with monthly cost,
  usage bar once OCS v2 lands. Owner/admin actions per card: *View services*,
  *Order for them*, *Set allowance*, *Make admin* (owner only), *Remove*.
- **Member drill-in stays in-page** (the hub replaces the fragile
  new-tab hop; `/family/{id}` remains as a deep link — now fixed to survive
  the login bounce).
- **Approvals inbox** section for owner/admins: pending join requests (exists
  today) plus purchase requests (new).
- Members see the hub too, reduced: who's in the family, who pays, their own
  allowance, "leave household".

### 2.4 Allowances and ask-to-buy (the pre-approval feature)

- **Allowance** = a standing policy rule per member, rules-as-data in the
  policy service (this is exactly what it was built for):
  `{member, categories: [topup], monthlyCap: €10}`.
- **Purchase flow for a member/child**: top-up within the remaining allowance
  → goes through instantly, notification to admins ("Sonny bought a 5 GB
  top-up · €5 — €5 of €10 left this month"). Above the cap or no allowance →
  order is **held**, a purchase request lands in the approvals inbox, admin
  taps approve/deny, the order proceeds or cancels with a friendly note to the
  requester. The child never hits a dead end (Family Link's insight).
- Top-ups ride the existing TMF654 `topupBalance` facade + OCS seam — the
  first allowance category is already plumbed end to end.

### 2.5 What stays sacred

- **Consent**: joining still requires the member's accept (or the payer
  creating the child account). Admins cannot conscript adults.
- **Privacy proportional to age**: owner/admin sees services, costs and
  allowances for everyone — but *content* of an adult member's own-paid
  services (Sonny's Netflix on his own card) stays off the family bill and
  out of the hub. Children's lines are fully visible.
- **TMF alignment**: roles are party roles on the household link (TMF632),
  allowances are policy rules, held orders are TMF622 states — no bespoke
  side-systems.

---

## 3. Delivery phases

**Phase 1 — the Family hub + roles** (storefront + app + party/ordering) — **DONE**
- `household_role` column + promote/demote endpoints (owner only) and
  admin-aware authorization in party, ordering and inventory ("admin may do
  what the payer may do", except role management).
- Family tab with member cards, in-page member view, My-page summary card.
- E2E: wife-as-admin orders for Sonny; admin cannot demote another admin.

**Phase 2 — allowances + ask-to-buy** — **DONE** (plus gifting & rollover
from §4): per-member EUR allowance on the household link; in-cap top-ups
instant on the family bill, over-cap child orders `held` with an approvals
inbox in the hub; adults always self-pay through; notifications both
directions via the event stream; family data gifts with the child/half-plan/
household guardrails; one-cycle capped rollover at month close.

---

## Demo data (seeded 2026-07-14)

| Persona | Login | Where | Has |
|---|---|---|---|
| Paula (family payer/owner) | `paula@family.example` / `paula` | localhost:8080/shop | 2× 30GB lines (`+46701000616/617`), 4.2 GB used, funds Sonny |
| Wilma (wife, family admin) | `wilma@family.example` / `wilma` | localhost:8080/shop | admin of Paula's family, no own line |
| Sonny (adult member) | `sonny@family.example` / `sonny` | localhost:8080/shop | 10GB line `+46701000619`, 3.1 GB used |
| Nils (Nordic-CSP-model tenant) | `nils@nova.example` / `nils` | shop.nova.localhost:8080/shop | Nova Smart 15 GB, line `+46731000041` |
| Norah (stranger to Nils) | `norah@nova.example` / `norah` | shop.nova.localhost:8080/shop | Nova Smart 15 GB, line `+46731000042` |

Gift walk-through: Paula → My page → 🎁 gift to Sonny (dropdown) or type
`+46701000619`. Nils → My page → type `+46731000042` — no family needed,
nova's plan spec carries `giftScope=tenant`. Month close (staff):
`POST /tmf-api/usageManagement/v4/cycleClose`.

---

## 4. Data gifting & rollover (researched 2026-07-14)

### What the field does
- **Jio**: two sharing modes — a RECURRING share (5/10/15/20 % of the parent's
  quota per member, auto-applied each cycle) and a ONE-TIME transfer up to
  50 %; transfer counts are capped per cycle (5 per member, 15 total). Coarse,
  legible chunks.
- **A Norwegian CSP**: unused data **rolls over automatically**; rollover data
  can be **gifted** to anyone on the network from the app — personal
  subscriptions only.
- **T-Mobile Data Stash**: rollover capped (20 GB), 12-month expiry, and the
  order of burn matters: **plan data first, oldest stash after**.
- **AT&T**: rollover lives exactly ONE cycle — the simplest honest model.

### Design for genalpha-bss
**Rollover (the AT&T model with a T-Mobile cap):** at cycle close the unused
remainder of each data bucket becomes a *Rollover data* bucket for the next
cycle, capped at one month's plan allowance; rollover lives one cycle and
lapses if unused. Plan data burns first. Native implementation in the usage
component (a cycle-close endpoint the demo can trigger); when the OCS seam is
live the rate plans in OCS own this and usage merely projects it.

**Gifting (the Norwegian-CSP move, inside our consent boundary):** a member may gift
whole-GB chunks of their remaining data to any ACTIVE member of the same
household — the household link is the trust boundary (that CSP gifts network-wide;
we start family-wide). Guardrails: max 50 % of the plan allowance gifted per
cycle (Jio's cap), and a **child cannot gift** (their data is family-funded),
only receive. The gift lands as a *Gifted data* bucket on the receiver, named
from the giver — generosity should be visible.

### Configuration, not code (added after "is this configurable?")
Gifting and rollover behavior is **product data**, resolved live in this
order (suite #31 `family_config_test.js` proves all of it on one binary):

| Lever | Where | Default (in code) |
|---|---|---|
| `giftable` | plan spec characteristic (TMF620) | true |
| `giftScope` | plan spec characteristic: `household` \| `tenant` | household |
| `giftSharePerCycle` | plan spec characteristic (fraction of plan) | 0.5 |
| `rolloverEligible` | plan spec characteristic | true |
| `rolloverCapGB` | plan spec characteristic | one month's allowance |
| any veto (size, segment, "personal subs only"…) | policy rule, domain `gifting`, authored as data in the console | none |

The **nova** tenant runs the network-wide gifting model (any customer gifts to any customer) by
carrying `giftScope=tenant` on its plan spec — a catalog edit. A child's
inability to gift stays code: it is a safety invariant, not a product choice.
The usage component reads levers through a 30-second cache, so an operator's
edit is live within half a minute, no restart. The Product Copilot can set
these characteristics like any others ("create a plan with network-wide
gifting").

**Allowances + ask-to-buy (Family Link × T-Mobile Family Allowances):** the
owner or an admin sets a monthly top-up allowance per member (EUR). A member's
top-up inside the remaining allowance completes instantly and notifies the
payer; above it (or with no allowance — a child's default) the ORDER HOLDS in
`held` state, a purchase request lands in the hub's approvals inbox and the
admins' inbox notifications, and approve releases it / deny cancels it with a
note to the requester. The member never hits a dead end.

**Phase 3 — visibility & polish**
- Per-member usage bars from OCS, spend history per member, `birthDate`
  capture and automatic child role, book/film chapter.

Already fixed ahead of the plan (defects, not design): deep links now survive
the login redirect (`auth.js` return-path), so "Open their page" lands on the
member, not the home page.
