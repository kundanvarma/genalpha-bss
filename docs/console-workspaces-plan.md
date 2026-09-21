# Console workspaces — every stakeholder sees their own desk (plan)

*2026-08-06. Prompted by a persona question with a sharp edge: "should a
product guy even be interested in runbooks and the workforce?" He
shouldn't — and the recon shows WHY he sees them anyway, and how far the
existing machinery already goes toward fixing it.*

## Research findings

**The mechanism exists and is sound.** The back-office console already
role-gates every tab: a `TAB_ROLE` map (tab → one realm role) filters
the tab bar against the token's roles, and the comment in the source
states the doctrine correctly — *hiding a tab is ergonomics, the 403
underneath is the security*. Granularity is therefore a MAPPING and
GROUPING problem, not an architecture problem. No server changes needed.

**Defect 1 — coarse keys.** 33 tabs, but several key on roles broader
than their audience: `runbook`, `audit` and `workforce` all key on
`ai:use`, which pat (product) legitimately holds for his Copilot — so
product sees ops furniture. This is the root of the original question.

**Defect 2 — one role per tab.** The map cannot express "visible to
A or B" (Workforce belongs to `workforce:use` approvers AND `ai:admin`
governors). Trivial to extend: array values, any-of semantics.

**Defect 3 — no grouping.** All 33 tabs render as one flat paginated
row (four pages). Even a correctly-filtered persona gets a junk drawer;
the film's own screen capture shows page 2 of 4. Tabs belong to obvious
departments already — nothing encodes that.

**Defect 4 — the duplicate Disputes tab** (a known backlog item) is
visible in this recon: `dispute` appears twice in RESOURCES and twice in
TAB_ROLE. Dies as a side effect of this arc.

**Personas today:** demo (holds everything), pat (product: catalog,
stock, qualification, policy, ai:use…), jo (care agent: tickets,
interactions, service reads — lives mostly in the CSR console). There is
no ops, finance, or growth staff persona, so tab granularity beyond
"pat vs demo" is currently not demoable.

**The suite constraint (learned the hard way in the proof run):**
several suites drive the console by clicking `.tab` elements by text
(bill_distribution's click-until-listing-proves loop, operator_form,
agentic_workforce's dashboard legs). Whatever grouping does to the DOM,
the `.tab` class and visible tab text MUST stay stable.

## The design

**P1 — a truthful map.** Re-key every tab to the role of the API it
actually fronts; extend `TAB_ROLE` values to accept arrays (any-of).
The changes that matter:

| tab | today | becomes | why |
|---|---|---|---|
| runbook | ai:use | `ai:admin` | deciding is governance; readers who need the library get the role explicitly |
| workforce | ai:use | `['workforce:use','ai:admin']` | approvers work the queue, governors set ceiling/hire |
| audit | ai:use | ai:use (unchanged) | transparency: everyone who USES AI may see their audit trail |
| dispute (dup) | — | removed | one Disputes tab, in Money |

Everything else already keys on its department's role
(billing:admin, catalog:write, campaign:read, quote:read, insight:read,
roles:admin, …) — the map was 90% right; this makes it 100%.

**P2 — workspaces.** Group the tab bar under department headers,
defined as data next to RESOURCES; a group renders only if it has at
least one visible tab, and the console lands on the first visible tab:

- **Catalog & Pricing** — Offerings, Specifications, Prices, Stock,
  Serviceable Areas, Product advisor, Copilot
- **Money** — Bills, Journal, Chart of accounts, Disputes, Dunning,
  Bill formats, Deliveries, Unapplied cash
- **Care & Ops** — Process flows, Appointments, Porting, Knowledge
- **Growth** — Campaigns, Journeys, Audiences, Insight, Guardrails,
  Sales leads, Opportunities
- **AI & Automation** — AI Audit, Runbooks, Workforce
- **Platform** — Operators, Staff, Rules

DOM contract preserved: same `.tab` buttons with the same text, wrapped
in labeled group containers; pagination replaced by groups (33 tabs ÷ 6
groups fit without paging for full-access users; small-role users see
one or two short groups).

**P3 — department personas as data (the demoable half).** Three realm
composite roles — `finance-staff`, `growth-staff`, `ops-staff` —
each a composite of EXISTING granular scopes (no new server roles), plus
three personas: `finn@bss.local` (finance), `gro@bss.local` (growth),
`omar@bss.local` (ops). pat stays the product persona. File+live
doctrine applies (KC container recreate re-imports realms — both must
change). This makes the pitch line real: *"log in as finn, see the
finance desk; the department IS a composite role your IdP admin edits."*

**Suite #87 `console_workspaces_test.js`:**
- per persona (pat, finn, gro, omar, demo): login → the EXACT set of
  visible group headers and tabs matches the department, nothing more
- the negative pair per persona: one server-side 403 probe proving the
  hidden tab's API refuses them too (hiding is UX, the wall is the API)
- the duplicate-Disputes regression: exactly ONE Disputes tab
- DOM-contract leg: bill_distribution's click-until-proven pattern still
  lands (grouped tab, same class, same text)

**Command palette (⌘K / Ctrl+K, `palette.js`, suite `console_palette_test.js`):**
the care desk's palette on the back office — type a page (every page the token
can see, with its department), a recent page, or a button visible on the page in
front of you; Enter runs it through the same path as a rail click; "Ask: …" hands
the question to the ? drawer. The header shows a ⌘K hint; Esc closes.

**Regression set (serial):** copilot, bill_distribution, operator_form,
process_memory, workforce_runtime, agentic_workforce, martech (heaviest
console-clickers), then the two personalization suites.

**Deliberately NOT in scope:** per-field/per-column hiding (server
payloads are already role-shaped where it matters); new server-side
roles; changes to the CSR/dealer/business consoles (each is already a
single-audience surface — the back office is the only shared building).

## Built since: groups in the page row, one verb per row, chips that filter

- **Groups.** A department may carry `groups: [{ label, tabs, short }]` beside its flat `tabs`; the page row above the content then reads `CATALOG Offerings · Specifications | PRICING Prices | AVAILABILITY Stock · Serviceable areas | TOOLS Advisor · Copilot · Simulator · Prospect sim | LAUNCH Approvals · Envelopes` — one line at 1440px. Marketing (Campaigns / Audiences / Content / Insights / Care / Brand & guardrails) and AI & Automation (Work / Decisions / Audit) are grouped the same way; other departments stay flat.
- `tabs` remains the union, so every `ws.tabs.includes(path)` lookup and the suites' `.tab` contract are untouched; `short` renames a page in the row only — the resource title, breadcrumb and rail keep the full name, and a page no group claims still gets a seat.
- **Open + ⋯.** Every list row shows one text verb, **Open** (the editor), and an overflow **⋯** (`More actions`) with the lifecycle step (`→ In test`), **Edit** (same door as Open) and **Delete**. Delete appears only while the row is a draft (`In study` / `In design` / `In test`, or a resource with no lifecycle at all): a live offering is retired via the ladder, never deleted from a list.
- The overflow is a fixed-position `.rowmenu` under its button — one open at a time; outside click, Escape and scroll close it. Suites that clicked `Edit` by text now click `Open` (`console_test.js`, `journey_edit_console_test.js`, `journey_video.js`).
- **Chips filter.** A health chip that counted specific rows (`ids`) is also a filter: click it and the list narrows to those rows (`.kpi.on`, `aria-pressed`), click again and everything is back; chips without `ids` stay plain text. The filter resets when the page changes and survives re-renders (pager, sort) via the row-flag observer.
- Suite `console_nav_test.js`: five group labels and a one-line row at 1440px, Open opens the editor, Delete only behind ⋯ on a draft, the `drafts` chip narrows and clears.

## Estimate

P1+P2+suite #87: one focused evening. P3 (realm file+live, personas,
docs): a short second session, mostly ceremony around Keycloak's
file+live doctrine. Suggested order: after the LinkedIn post and the
overnight sweep — this arc touches the console every suite clicks
through, and the fleet is currently in its best pre-post state.

## Home / My Work — the first screen (built 2026-09-21)

The console now opens on **Home**, one page in a department of its own, for
every member of staff. It answers one question in five seconds: *what needs me
today?* Five sections, top to bottom: **Attention** (offers still in draft or
on sale past their window, launches waiting for a decision, overdue bills,
orders open more than two days), **My work** (launches on hold, AI-workforce
tasks waiting for approval, desk suggestions), **Operational health** (offers
on the shelf, bills open, orders in flight, journeys live, AI workers busy),
**Recent** (the last eight pages you opened) and **Quick actions** (new
offering, the two copilots, Approvals). Every card is gated by the same role
as the page it opens — a product manager sees catalog cards only, finance
sees money only — and a card whose data the API refuses simply stays away.
A quiet desk says "No action needed"; nothing is red unless something is late.
Code: `apps/admin-console/site/home.js`; suite `ops/e2e/console_home_test.js`.
