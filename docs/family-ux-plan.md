# Family experience v3 — research and design plan

Status: **proposal, awaiting review** · 2026-07-14

The household feature today is plumbing-first: consent links, payer stamps,
correct bills. It is honest but it is not an *experience*. This plan starts
from how the operators people actually live with — Jio, Orange, Nordic CSPs,
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

**Phase 1 — the Family hub + roles** (storefront + app + party/ordering)
- `household_role` column + promote/demote endpoints (owner only) and
  admin-aware authorization in party, ordering and inventory ("admin may do
  what the payer may do", except role management).
- Family tab with member cards, in-page member view, My-page summary card.
- E2E: wife-as-admin orders for Sonny; admin cannot demote another admin.

**Phase 2 — allowances + ask-to-buy**
- Allowance policy rules + evaluation in the top-up path; held orders with
  `pendingApproval`; approvals inbox with approve/deny; notifications both
  directions.
- E2E: child tops up inside cap (instant), above cap (held → approved →
  delivered), denied (friendly cancel).

**Phase 3 — visibility & polish**
- Per-member usage bars from OCS, spend history per member, `birthDate`
  capture and automatic child role, book/film chapter.

Already fixed ahead of the plan (defects, not design): deep links now survive
the login redirect (`auth.js` return-path), so "Open their page" lands on the
member, not the home page.
