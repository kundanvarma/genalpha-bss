# The Operator Console — integrations marketplace + sales/ops reporting

**Status:** P1 shipped (2026-08-11) · **P2 shipped (2026-08-12)** · P3–P4 pending · **Depends on:** `apps/admin-console`, the service seams, the revenue/billing/order data already captured

> **P2 (per-tenant PSP + logistics config) shipped:** landed across the carrier arc (C-P2: `carrier_config` + Logistics card) and the PSP arc (PSP-P1: `payment_provider_config` + Payment card), then completed here — the Payment card exposes the **routing fields** (`priority`, `currencies` — the suite-#96 orchestration levers) and both cards grew **Test connection**: `POST /paymentProvider/{p}/test` and `POST /carrier/{c}/test`, a reachability probe of the provider's `/health` (3s connect timeout) that is **never a payment or a booking** — a bad base URL reads "✗ unreachable" on the card before checkout ever finds out. Global env stays the fallback throughout. Remaining: P3 (the MRR/ARPU/churn engine), P4 (generic connectors + the encrypted secret store).

> **P1 (visibility) shipped:** `GET /revenue/v1/summary` — a governed sales/finance summary computed once from the subledger (net revenue = credit−debit over revenue-family accounts, tax, cash collected, invoices, prior-period delta, revenue-by-account). Two new console workspaces (data-driven `RESOURCES` entries + custom panels): **Reporting** (KPI tiles + date range + by-account table) and **Integrations** (the seam catalog — the Content/DAM card is *live* per-tenant config via `/contentProvider` GET/PUT/DELETE; PSP/logistics/OCS/AI shown as built-in with P2 notes). Verified live in the console.

Two asks, one surface: the console a **digital sales lead** actually lives in. Part A makes
the platform's integration seams *installable and configurable per tenant from a form*
(the OpenCart/Shopify "some built-in, some from a marketplace, easy to configure" feeling).
Part B gives that same person **governed sales & ops reporting** over data the platform
already captures. Both are extensions of the console we have — not a new app.

---

## 1. What we found (grounded in the code)

**The console is a data-driven engine, not hand-built pages.** `apps/admin-console/site/` is a
static vanilla-JS SPA (nginx, no build step). Every tab is a config object in a single
`RESOURCES` array (`app.js`): `path`, `base`, `fields[]` (a form DSL — text/number/money/ref/
select/jsontext/…), `columns[]`, `readOnly`, plus escape hatches for custom panels (`copilot`,
`workforce`, `staff` already are). Tabs group into `WORKSPACES`; `TAB_ROLE` gates each on a
realm role; `authFetch` (OIDC PKCE) calls services same-origin through the gateway. **Adding a
tab is a data change.** This is the decisive fact for both parts.

**The seams are already uniform — but configured three different ways.** Each integration
point is a Java interface + a default/mock + real impls. How they're *selected* differs:

| Seam | Interface | Selection today | Per-tenant config table? |
|---|---|---|---|
| Content/CMS | `AssetProvider` + `ContentStore` | **per-tenant DB** (`content_provider_config`) + `AssetProviderRegistry` runtime lookup + `ContentProviderController` PUT/GET/DELETE | **Yes** ✅ |
| AI provider | `LlmAdapter` | global default **+ per-tenant override** via `LlmRouter` reading the tenant registry | registry (YAML) |
| Payment/PSP | `PspAdapter` | **global env** (`@ConditionalOnProperty bss.payment.psp`) | No |
| Logistics/carrier | `LogisticsClient` | **global env** (`bss.downstream.logistics-*`, default Helthjem) | No |
| OCS/charging | `OcsClient` | **global env** (`bss.downstream.ocs-base-url`) | No |

The CMS seam (just shipped) is the **reference pattern**: per-tenant row, RLS, `secret_ref`
(an env-var *name*, never the token), a registry that resolves by `name()`, and a thin
controller. Part A generalises exactly this to the other seams.

**The reporting data is already there — scattered across services.** No cross-service
`/metrics` aggregate exists; each tab composes its own. What a dashboard would draw on:
- **Revenue subledger** (`/revenue/v1`): `journalEntry` (balanced postings), `journalExport`
  (CSV), `revrecInput` (ASC 606 timelines), `reconciliation`.
- **Billing** (`/tmf-api/customerBillManagement/v4`): `customerBill`, `appliedCustomerBillingRate`
  (granular revenue lines with `taxExcludedAmount`), `creditNote`, `dunning`, `dispute`.
- **Orders** (`productOrder`), **Payment**, **Loyalty** (`/liability` = points liability).
- **Existing stats endpoints** already compute governed aggregates: campaign `/stats`
  (reached, lift, revenue), journey `/stats`, incident `/stats`, workforce `/kpis`, usage
  `usageConsumptionReport`, insight `/audiences`, AI `/audit` (cost ledger), sales leads +
  opportunities pipeline.

So reporting is **governed aggregation of existing data**, not new capture — the important
architectural gift.

---

## 2. Best practice (researched, cited)

**Integrations (Part A)** — the model is **commercetools Connect**: a *marketplace of pre-built
connectors* you browse, deploy, and configure without custom code ([Connect][ct-connect],
[marketplace][ct-mkt]). The *install UX* is **Shopify**: browse → install → grant credentials →
configure on a settings screen → enable, scoped per store; request **minimal scopes**, keep
credentials on a settings pane, prefer managed/token-exchange install ([Shopify OAuth][shopify]).

**Reporting (Part B)** — two lenses converge:
- E-commerce: *revenue, conversion, AOV, repeat rate* tell the story; stay **margin-aware**
  (revenue per visitor, contribution margin, return rate) ([Data Bloo][databloo], [Fusedash][fuse]).
- Telecom + subscription: *ARPU, churn, subscriber/prepaid-postpaid mix, product penetration*,
  drill-down to *who changed plans and when*; the **MRR waterfall** (new / expansion /
  contraction / churned) with cohort + net retention ([telecom KPIs][infoveave],
  [Baremetrics MRR][baremetrics]).
- Governing principle: **one ingestion layer, KPI definitions computed once from governed
  formulas, role-based access** ([telecom BI][vidi]). This is decisive — it argues for a
  *governed metrics endpoint*, not ad-hoc client math.

---

## 3. The design

### PART A — Integrations marketplace

**A1. One config shape for every seam.** Generalise `content_provider_config` into a per-seam,
per-tenant, RLS-scoped binding table (or a single `integration_binding` table keyed by
`(tenant_id, kind)` where `kind ∈ {payment, logistics, ocs, cms, ai}`). Columns mirror the CMS
one: `provider`, `base_url`, typed config JSON, `secret_ref`(s), `enabled`, timestamps. Each
seam gets a registry (`…Registry` resolving by `name()`, like `AssetProviderRegistry`) and a
thin controller (`PUT/GET/DELETE`), so **selection moves from global env → per-tenant row**.
Global env stays as the *fallback default* (exactly how AI provider already does default +
per-tenant override), so nothing breaks and single-tenant deploys need no rows.

**A2. Built-in adapters + a generic connector per seam.** Carry the CMS lesson forward: ship
the named adapters an operator expects (Stripe PSP; Helthjem/Posten/Bring carriers) *and* a
**config-driven generic connector** for the long tail, so "add your own" is a config row, not a
class. Not every seam has a clean generic shape (PSP is the hardest — money is not fire-and-
forget); those stay named-adapter-only, honestly.

**A3. The "Integrations" console tab.** A new `WORKSPACES` group → **Integrations**, rendered by
the same engine: a catalog card per seam × provider (built-in badge, "configure" form, an
enable toggle, a **Test connection** button reusing the existing `tester` field kind). Selecting
a provider opens a form (the field DSL already supports everything: `base_url`, select, jsontext
for connector config, a masked secret field). This is **a `RESOURCES` entry + a small custom
panel**, not new console machinery.

**A4. Secrets — the one real decision.** Today the pattern is `secret_ref`: the row stores an
*env-var name*, the value lives in the container env, never the DB. That's safe but **not
self-serve** — an operator can't paste an API key into a form. A true marketplace wants
paste-and-go. Options: (a) keep `secret_ref` (safe, ops sets env — fine for a managed/single
operator); (b) add an **encrypted-at-rest secret store** (a `tenant_secret` table, envelope-
encrypted with a KMS/`ENCRYPTION_KEY`, the form writes the ciphertext) so operators self-serve.
Recommendation: **(a) now, (b) as the self-serve upgrade** — decision 7a.

### PART B — Sales & ops reporting

**B1. A governed metrics endpoint** — the best-practice "computed once, governed formulas."
Add a small read-only reporting surface (a new `reporting` service, or a `/reporting/v1` face on
an existing one) that computes per-tenant KPIs server-side and returns them role-gated. Phase 1
may *compose* the existing stats endpoints; the endpoint is the governance boundary either way.
Proposed metrics, each traceable to a source already in §1:
- **Sales:** revenue (from `appliedCustomerBillingRate` / subledger), orders + AOV
  (`productOrder`), conversion (insight sessions → orders), repeat rate, top offerings.
- **Subscription:** **MRR waterfall** (new/expansion/contraction/churned from order + bill
  deltas), **ARPU** (revenue ÷ active subscribers), **churn** (subscription terminations),
  prepaid/postpaid + product-penetration mix.
- **Cash & risk:** collected vs outstanding (`dunning`), credit-note leakage, dispute rate,
  loyalty liability (`/liability`).
- **AI/ops cost-to-serve:** `ai/v1/audit` spend, workforce `kpis` — the honest margin line.

**B2. The "Reporting" console workspace** — a dashboard tab (custom panel, like `workforce`):
KPI tiles + sparklines up top, the MRR waterfall, a drill-down table (who changed plans, from
the order/subscription data), a date-range control, and **CSV export** (reuse the revenue
`journalExport` pattern). Role-gated (`billing:read`/a new `reporting:read`) so a sales lead
sees sales, finance sees GL.

**B3. Don't recompute the truth.** Billing/subledger stays the source of truth; reporting reads
and segments, never re-derives money. Where a stat already exists (campaign lift, journey
revenue), the dashboard *links* to it rather than recomputing.

---

## 4. Phasing (each phase ships value on its own)

- **P1 — de-hardcode + surface what exists.** Reporting P1: a `/reporting/v1/summary` that
  composes today's stats + billing/order aggregates into KPI tiles; a Reporting tab. Integrations
  P1: an **Integrations tab that reads current config** (CMS binding, AI provider, and *shows*
  the env-configured PSP/logistics/OCS as read-only "built-in") — visibility first.
- **P2 — per-tenant PSP + logistics config.** Generalise the CMS table pattern to payment and
  logistics (the carrier work slots in here): registry + controller + Integrations forms + Test
  connection. Global env becomes the fallback.
- **P3 — the MRR/ARPU/churn engine.** The governed subscription-metrics computation (waterfall,
  cohort, net retention) + drill-down + CSV/scheduled export.
- **P4 — generic connectors + self-serve secrets.** A generic connector where a seam supports it
  (carriers first), and the encrypted secret store (7a) for paste-and-go credentials.

## 5. Scope boundaries (honest)

- **Operator-facing catalog, not a third-party plugin marketplace.** External devs publishing
  installable plugins that others buy needs a plugin SDK, sandboxing, review, and distribution —
  a strategic bet, not this doc. We deliver "browse built-ins + add your own via connector/config,"
  which captures most of the value without hot-loading foreign code into a multi-tenant runtime.
- **Reporting is BI-lite, not a warehouse.** Governed KPIs + drill-down + export over live
  service data. Not a replacement for a real data warehouse / OLAP; heavy historical analytics
  and ML forecasting stay a downstream export (the `journalExport`/CSV seams already feed them).
- **PSP has no generic connector.** Payment is the one seam where "any provider by config" is
  unsafe; it stays named adapters.
- **No new source-of-truth.** Reporting never writes money or re-derives it.

## 6. Estimate

Each phase ≈ one arc. P1 is small (compose + two console tabs). P2 repeats the proven CMS
pattern twice. P3 (the metrics engine) is the substantive one. P4 is additive.

## 7. Decisions to lock

- **7a. Secrets:** `secret_ref` (env, safe, ops-set) now vs an encrypted `tenant_secret` store
  (self-serve paste-and-go) — *proposed: secret_ref now, encrypted store in P4.*
- **7b. Config home:** one `integration_binding` table (tenant, kind) vs a table per seam (like
  `content_provider_config`) — *proposed: one table, uniform, unless a seam needs bespoke columns.*
- **7c. Reporting home:** a new `reporting` service vs a `/reporting/v1` face on revenue/billing —
  *proposed: face on revenue first (it already owns the subledger + CSV), promote to its own
  service if it grows.*
- **7d. Metrics freshness:** on-read compute vs a periodic rollup table — *proposed: on-read for
  P1/P3, add a nightly rollup if the fleet-age performance work demands it.*
- **7e. Who's the primary user?** a single "digital sales lead" role/workspace, or per-function
  splits (sales / finance / ops) — decides the role gates and tab grouping.

---

[ct-connect]: https://commercetools.com/commerce-platform/connect
[ct-mkt]: https://marketplace.commercetools.com/integrations
[shopify]: https://shopify.dev/docs/apps/auth/oauth
[databloo]: https://www.databloo.com/blog/ecommerce-dashboard/
[fuse]: https://fusedash.ai/blog/post/ecommerce-dashboard-kpis
[infoveave]: https://infoveave.com/resources/blogs/telecom-kpis-complete-guide
[baremetrics]: https://baremetrics.com/blog/saas-metrics-dashboards-examples-templates
[vidi]: https://vidi-corp.com/telecom-business-intelligence/
