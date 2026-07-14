/*
 * Back-office console: list / create / edit / delete over the TMF APIs
 * through the gateway (same origin — no CORS). Resources are configuration,
 * so extending the console is a data change, not new code.
 *
 * Field kinds: text (default), number, checkbox, money ({unit, value}),
 * ref (single entity reference), reflist (array of entity references),
 * select (static options), longtext, codepick (picklist whose value is a
 * plain attribute of the picked item, not a reference object).
 * Refs load their pick-lists from the API when the editor renders.
 *
 * Resource hooks: assemble(body) reshapes the flat editor output before it
 * is sent; rowAction gives a per-row verb beyond edit/delete (e.g. pause);
 * augmentRow(item, cell) fills a computed column after the row renders.
 */
'use strict';

// The host tenant's brand color themes the console, same as the customer channels.
const BRAND = window.BSS_CONSOLE_CONFIG || {};
if (BRAND.brandColor) {
  document.documentElement.style.setProperty('--teal', BRAND.brandColor);
  document.documentElement.style.setProperty('--teal-soft', BRAND.brandColor + '1F');
}
if (BRAND.brandName) document.title = `${BRAND.brandName} · back office`;

const API_BASE = '/tmf-api/productCatalogManagement/v4';
const STOCK_BASE = '/tmf-api/productStockManagement/v4';
const BILLING_BASE = '/tmf-api/customerBillManagement/v4';
const QUALIFICATION_BASE = '/tmf-api/productOfferingQualification/v4';
const APPOINTMENT_BASE = '/tmf-api/appointment/v4';
const CAMPAIGN_BASE = '/tmf-api/campaignManagement/v4';
const PROMOTION_BASE = '/tmf-api/promotionManagement/v4';
const POLICY_BASE = '/tmf-api/policyManagement/v4';
const KNOWLEDGE_BASE = '/tmf-api/knowledgeManagement/v4';
const PORTING_BASE = '/tmf-api/numberPortingManagement/v1';
const PAGE_SIZE = 10;
const REF_PICKLIST_LIMIT = 100;

// The business moments a campaign can react to (the event stream's editorial map).
const TRIGGER_EVENTS = [
  { value: 'ProductOrderCreateEvent', label: 'Order placed' },
  { value: 'ProductOrderStateChangeEvent', label: 'Order state changed' },
  { value: 'CustomerBillCreateEvent', label: 'Bill issued' },
  { value: 'TroubleTicketStateChangeEvent', label: 'Ticket state changed' },
  { value: 'ShoppingCartAbandonedEvent', label: 'Cart abandoned' },
  { value: 'AgreementCreateEvent', label: 'Agreement started' },
  { value: 'ChurnRiskDetectedEvent', label: 'Churn risk detected (AI scorer)' },
];

const RESOURCES = [
  {
    path: 'productOffering',
    title: 'Product Offerings',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'description', label: 'Description' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
      { name: 'version', label: 'Version' },
      { name: 'productSpecification', label: 'Specification', kind: 'ref', resource: 'productSpecification', referredType: 'ProductSpecification' },
      { name: 'category', label: 'Categories (drive placement & fulfilment)', kind: 'reflist', resource: 'category', referredType: 'Category' },
      { name: 'productOfferingTerm', label: 'Commitment', kind: 'commitment' },
      { name: 'isBundle', label: 'Is a bundle', kind: 'checkbox' },
      { name: 'bundledProductOffering', label: 'Bundle composition', kind: 'bundlecomposer', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'productOfferingPrice', label: 'Prices', kind: 'reflist', resource: 'productOfferingPrice', referredType: 'ProductOfferingPrice' },
      { name: 'attachment', label: 'Artwork — gallery shots & colour variants', kind: 'artwork' },
    ],
    columns: ['name', 'lifecycleStatus', 'isBundle', 'version', 'lastUpdate'],
  },
  {
    path: 'productSpecification',
    title: 'Product Specifications',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'brand', label: 'Brand' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
      { name: 'productSpecCharacteristic', label: 'Characteristics (JSON array)', kind: 'jsontext',
        placeholder: '[{"name": "color", "productSpecCharacteristicValue": [{"value": "Black"}]}]' },
    ],
    columns: ['name', 'brand', 'lifecycleStatus', 'lastUpdate'],
  },
  {
    path: 'productOfferingPrice',
    title: 'Product Offering Prices',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'priceType', label: 'Price type', placeholder: 'recurring' },
      { name: 'price', label: 'Price', kind: 'money' },
      { name: 'recurringChargePeriodType', label: 'Charge period', placeholder: 'month' },
      { name: 'recurringChargePeriodLength', label: 'Period length', kind: 'number', placeholder: '1' },
      { name: 'isBundle', label: 'Bundle price', kind: 'checkbox' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
      { name: 'version', label: 'Version' },
    ],
    columns: ['name', 'priceType', 'price', 'recurringChargePeriodType', 'lifecycleStatus', 'lastUpdate'],
  },
  {
    path: 'productStock',
    base: STOCK_BASE,
    title: 'Product Stock',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'productOffering', label: 'Offering', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'stockedQuantity', label: 'Stocked', kind: 'quantity' },
    ],
    columns: ['name', 'productOffering', 'stockedQuantity', 'reservedQuantity', 'availableQuantity', 'lastUpdate'],
  },
  {
    path: 'customerBill',
    base: BILLING_BASE,
    title: 'Customer Bills',
    readOnly: true,
    fields: [],
    columns: ['billNo', 'relatedParty', 'billingPeriod', 'amountDue', 'state', 'lastUpdate'],
    // a bill is its LINES — expand them in place
    detail: async (item) => {
      const res = await authFetch(`${BILLING_BASE}/customerBill/${item.id}/appliedCustomerBillingRate`);
      const rates = res.ok ? await res.json() : [];
      return rates.map((r) => ({
        line: r.name,
        type: r.type,
        amount: `${Number(r.taxExcludedAmount?.value ?? 0).toFixed(2)} ${r.taxExcludedAmount?.unit || ''}`,
        for: r.forParty?.id ? r.forParty.id.slice(0, 8) + '…' : '—',
      }));
    },
  },
  {
    path: 'serviceableArea',
    base: QUALIFICATION_BASE,
    title: 'Serviceable Areas',
    noEdit: true,
    fields: [
      { name: 'name', label: 'Name' },
      { name: 'productOffering', label: 'Offering', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'postcodePrefix', label: 'Postcode prefix', required: true },
    ],
    columns: ['name', 'productOffering', 'postcodePrefix', 'lastUpdate'],
  },
  {
    path: 'appointment',
    base: APPOINTMENT_BASE,
    title: 'Appointments',
    readOnly: true,
    fields: [],
    columns: ['description', 'validFor', 'status', 'relatedParty', 'lastUpdate'],
  },
  {
    path: 'campaign',
    base: CAMPAIGN_BASE,
    title: 'Campaigns',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'triggerEventType', label: 'Trigger', kind: 'select', required: true, options: TRIGGER_EVENTS },
      { name: 'triggerState', label: 'State filter', placeholder: 'e.g. completed (optional)' },
      { name: 'promotionCode', label: 'Promo code to include', kind: 'codepick',
        base: PROMOTION_BASE, resource: 'promotion', attribute: 'code' },
      { name: 'messageSubject', label: 'Message subject', required: true },
      { name: 'messageContent', label: 'Message — {code} inserts the promo code', kind: 'longtext', required: true },
    ],
    assemble: (body) => {
      const { messageSubject, messageContent, ...rest } = body;
      return { ...rest, message: { subject: messageSubject, content: messageContent } };
    },
    columns: ['name', 'status', 'triggerEventType', 'promotionCode', 'reached'],
    rowAction: {
      label: (item) => (item.status === 'active' ? 'Pause' : 'Resume'),
      apply: (item) => authFetch(`${CAMPAIGN_BASE}/campaign/${item.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: item.status === 'active' ? 'paused' : 'active' }),
      }),
    },
    augmentRow: async (item, cell) => {
      const res = await authFetch(`${CAMPAIGN_BASE}/campaign/${item.id}/execution`);
      const executions = await res.json();
      cell.textContent = `${executions.length} customer${executions.length === 1 ? '' : 's'}`;
    },
    // The intelligence component drafts; the marketer edits and saves.
    aiAssist: {
      placeholder: 'Brief for AI, e.g. "thank first-time buyers, warm tone"',
      draft: async (brief) => {
        const res = await authFetch('/ai/v1/campaignCopy', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            brief,
            brandName: BRAND.brandName || undefined,
            triggerEventType: controls.triggerEventType?.get(),
            promotionCode: controls.promotionCode?.get(),
          }),
        });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
        const copy = await res.json();
        controls.messageSubject.set({ messageSubject: copy.subject });
        controls.messageContent.set({ messageContent: copy.content });
      },
    },
  },
  {
    path: 'article',
    base: KNOWLEDGE_BASE,
    title: 'Knowledge',
    // The library every channel reads: FAQs for customers, cheat-sheets for
    // CSRs, how-tos for product owners. Content is DATA — publish here and
    // the shop's Support page, the CSR desk and the ask-AI answer from it
    // immediately.
    fields: [
      { name: 'title', label: 'Title', required: true },
      { name: 'audience', label: 'Who is this for', kind: 'select', options: [
        { value: 'customer', label: 'Customers (shop Support page)' },
        { value: 'csr', label: 'CSRs (agent desk)' },
        { value: 'sales', label: 'Sales' },
        { value: 'productOwner', label: 'Product owners (console)' },
        { value: 'all', label: 'Everyone' },
      ] },
      { name: 'category', label: 'Category (e.g. Mobile data, Family, Catalog how-to)' },
      { name: 'tags', label: 'Search tags (comma-separated)' },
      { name: 'body', label: 'Article body', kind: 'longtext', required: true },
      { name: 'status', label: 'Status', kind: 'select', options: [
        { value: 'published', label: 'Published' },
        { value: 'draft', label: 'Draft (authors only)' },
      ] },
    ],
    columns: ['title', 'audience', 'category', 'status', 'lastUpdate'],
    detail: async (item) => {
      const res = await authFetch(`${KNOWLEDGE_BASE}/article/${item.id}`);
      const full = await res.json();
      return [{ audience: full.audience, category: full.category || '—', body: full.body }];
    },
  },
  {
    path: 'profile',
    base: '/insight/v1',
    title: 'Insight',
    // The consent ledger: WHO the shop is watching, under WHICH consent —
    // and what it learned. Read-only by design; the visitor owns the data,
    // the operator only gets to SEE what it holds.
    readOnly: true,
    fields: [],
    columns: ['visitorId', 'partyId', 'analyticsConsent', 'personalizationConsent', 'utmSource', 'lastUpdate'],
    detail: async (item) => {
      const res = await authFetch(`/insight/v1/profile?visitorId=${item.visitorId}`);
      const full = await res.json();
      return [{
        consent: `analytics: ${full.analyticsConsent} · personalization: ${full.personalizationConsent}`,
        events: full.eventCount,
        interests: (full.interests || []).map((i) => `${i.category} (${i.views})`).join(', ') || '—',
        campaign: full.utmSource || '—',
      }];
    },
  },
  {
    path: 'policyRule',
    base: POLICY_BASE,
    title: 'Rules',
    // Business rules authored as DATA: create/enable one here and the next
    // order is checked against it — no redeploy. The builder turns plain
    // choices into a JSON-logic condition; "Advanced" lets you write one.
    fields: [
      { name: 'name', label: 'Rule name', required: true },
      { name: 'ruleKind', label: 'What kind of rule', kind: 'select', required: true, options: [
        { value: 'quantity-cap', label: 'Block: limit how many of an item can be ordered' },
        { value: 'incompatibility', label: 'Block: two items cannot be bought together' },
        { value: 'requires-verified-id', label: 'Block: item requires a verified identity (BankID)' },
        { value: 'advanced', label: 'Block: advanced — write raw JSON-logic' },
        { value: 'price-verified', label: 'Price: discount / surcharge for verified customers' },
        { value: 'price-when-item', label: 'Price: discount / surcharge when the cart has an item' },
        { value: 'price-always', label: 'Price: discount / surcharge for everyone' },
        { value: 'price-company', label: 'Price: negotiated deal for one company (B2B)' },
        { value: 'price-characteristic', label: 'Price: campaign on a configured choice (e.g. a colour)' },
        { value: 'price-volume', label: 'Price: volume deal — any company with enough people (B2B)' },
        { value: 'price-advanced', label: 'Price: advanced — raw JSON-logic condition' },
        { value: 'perso-interest', label: 'Personalize: visitors interested in a category see a banner + pinned offer' },
        { value: 'perso-segment', label: 'Personalize: an analytics segment (audience) sees a banner + pinned offer' },
      ] },
      { name: 'organization', label: 'Company (the deal applies to this organization only)', kind: 'ref',
        base: '/tmf-api/party/v4', resource: 'organization', referredType: 'Organization',
        showWhen: { field: 'ruleKind', in: ['price-company'] } },
      { name: 'minMembers', label: 'Minimum people billing together', kind: 'number', placeholder: '10',
        showWhen: { field: 'ruleKind', in: ['price-volume'] } },
      { name: 'characteristicName', label: 'Characteristic (as on the spec, e.g. color)', placeholder: 'color',
        showWhen: { field: 'ruleKind', in: ['price-characteristic'] } },
      { name: 'characteristicValue', label: 'Value the campaign applies to (e.g. Icy Blue)',
        showWhen: { field: 'ruleKind', in: ['price-characteristic'] } },
      { name: 'interestCategory', label: 'Interest — the category they have been browsing (e.g. Devices)',
        showWhen: { field: 'ruleKind', in: ['perso-interest'] } },
      { name: 'segmentName', label: 'Segment — audience name from your analytics (e.g. high-value-browsers)',
        showWhen: { field: 'ruleKind', in: ['perso-segment'] } },
      { name: 'pinnedOffering', label: 'Offering to pin on top of the shop', kind: 'ref',
        resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['perso-interest', 'perso-segment'] } },
      { name: 'offeringA', label: 'Item', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['quantity-cap', 'incompatibility', 'requires-verified-id', 'price-when-item'] } },
      { name: 'maxQuantity', label: 'Max quantity (blank = 1)', kind: 'number',
        showWhen: { field: 'ruleKind', in: ['quantity-cap'] } },
      { name: 'offeringB', label: 'Second item (cannot be bought with the first)', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['incompatibility'] } },
      { name: 'adjustmentType', label: 'Adjustment type', kind: 'select', options: [
        { value: '', label: '—' },
        { value: 'percent', label: 'Percent of subtotal' },
        { value: 'amount', label: 'Fixed amount' },
      ], showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-company', 'price-volume', 'price-advanced'] } },
      { name: 'adjustmentValue', label: 'Adjustment value — negative = discount, positive = surcharge', kind: 'number',
        showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-company', 'price-volume', 'price-advanced'] } },
      { name: 'condition', label: 'JSON-logic condition', kind: 'longtext',
        showWhen: { field: 'ruleKind', in: ['advanced', 'price-advanced'] } },
      { name: 'message', label: 'Message / label shown to the customer', required: true },
      { name: 'priority', label: 'Priority (lower runs first)', kind: 'number', placeholder: '100' },
      { name: 'enabled', label: 'Enabled', kind: 'checkbox' },
    ],
    assemble: (body) => {
      const idOf = (r) => (r && typeof r === 'object' ? r.id : r);
      const a = idOf(body.offeringA);
      const b = idOf(body.offeringB);
      const max = Number(body.maxQuantity);
      const kind = body.ruleKind || '';
      const isPricing = kind.startsWith('price-');
      const isPerso = kind.startsWith('perso-');
      let condition = body.condition;
      switch (kind) {
        case 'perso-interest':
          condition = JSON.stringify({ in: [(body.interestCategory || '').trim(), { var: 'interests' }] });
          break;
        case 'perso-segment':
          condition = JSON.stringify({ in: [(body.segmentName || '').trim(), { var: 'segments' }] });
          break;
        case 'quantity-cap':
          condition = JSON.stringify({ '>': [{ var: a ? `quantityByOffering.${a}` : 'maxLineQuantity' }, Number.isFinite(max) && max > 0 ? max : 1] });
          break;
        case 'incompatibility':
          condition = JSON.stringify({ and: [{ in: [a, { var: 'offeringIds' }] }, { in: [b, { var: 'offeringIds' }] }] });
          break;
        case 'requires-verified-id':
          condition = JSON.stringify({ and: [{ in: [a, { var: 'offeringIds' }] }, { '!': { var: 'verifiedIdentity' } }] });
          break;
        case 'price-verified':
          condition = JSON.stringify({ var: 'verifiedIdentity' });
          break;
        case 'price-when-item':
          condition = JSON.stringify({ in: [a, { var: 'offeringIds' }] });
          break;
        case 'price-always':
          condition = JSON.stringify({ '==': [1, 1] });
          break;
        case 'price-characteristic':
          // the cart preview and the billing run both put the configured
          // picks into the context as "name:value" strings
          condition = JSON.stringify({ in: [
            `${(body.characteristicName || 'color').trim()}:${(body.characteristicValue || '').trim()}`,
            { var: 'characteristicValues' }] });
          break;
        case 'price-company':
          // organizationId only exists in the context when the payer IS a
          // company, so this can never touch a consumer.
          condition = JSON.stringify({ '==': [{ var: 'organizationId' }, idOf(body.organization)] });
          break;
        case 'price-volume':
          condition = JSON.stringify({ '>=': [{ var: 'memberCount' },
            Number.isFinite(Number(body.minMembers)) && Number(body.minMembers) > 0 ? Number(body.minMembers) : 2] });
          break;
        default:
          break;
      }
      return {
        name: body.name,
        description: body.description,
        domain: isPerso ? 'personalization' : isPricing ? 'pricing' : 'order',
        effect: isPerso ? 'experience' : isPricing ? 'adjust' : 'deny',
        priority: body.priority,
        enabled: body.enabled,
        condition,
        message: body.message,
        adjustmentType: isPricing ? (body.adjustmentType || 'percent') : undefined,
        adjustmentValue: isPricing ? body.adjustmentValue : undefined,
        experience: isPerso && body.pinnedOffering
          ? { teaserOfferingId: idOf(body.pinnedOffering) } : undefined,
      };
    },
    columns: ['name', 'domain', 'effect', 'enabled', 'priority', 'adjustmentValue', 'condition', 'lastUpdate'],
    rowAction: {
      label: (item) => (item.enabled ? 'Disable' : 'Enable'),
      apply: (item) => authFetch(`${POLICY_BASE}/policyRule/${item.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: !item.enabled }),
      }),
    },
    // Dry-run: test the ENABLED rules against a sample order/cart without
    // placing anything — same engine, same endpoints the pipeline uses.
    tester: true,
  },
  {
    path: 'numberPortingOrder',
    base: PORTING_BASE,
    title: 'Porting',
    // Back office view of MNP: every port-in/out in the tenant, with the
    // cutover action for scheduled orders. Creating one here is the assisted
    // path (e.g. a customer phoning in a port-out).
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'phoneNumber', label: 'Phone number (E.164, e.g. +4791234567)', required: true },
      { name: 'direction', label: 'Direction', kind: 'select', required: true, options: [
        { value: 'portIn', label: 'Port-in — customer joins us and keeps their number' },
        { value: 'portOut', label: 'Port-out — customer leaves with their number' },
      ] },
      { name: 'country', label: 'Country (ISO alpha-2)', placeholder: 'NO', required: true },
      { name: 'otherOperator', label: 'Other operator', placeholder: 'Telenor' },
      { name: 'customerPartyId', label: 'Customer party id', required: true },
    ],
    assemble: (body) => ({
      phoneNumber: body.phoneNumber,
      direction: body.direction,
      country: body.country,
      otherOperator: body.otherOperator,
      relatedParty: [{ id: body.customerPartyId, role: 'customer' }],
    }),
    columns: ['phoneNumber', 'direction', 'status', 'country', 'otherOperator', 'clearinghouse', 'scheduledCutover'],
    rowAction: {
      label: (item) => (item.status === 'scheduled' ? 'Complete cutover' : ''),
      apply: (item) => authFetch(`${PORTING_BASE}/numberPortingOrder/${item.id}/complete`, { method: 'POST' }),
    },
  },
  {
    path: 'copilot',
    title: 'Copilot',
    copilot: true,      // custom chat panel, not the generic CRUD table
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'staff',
    title: 'Staff',
    staff: true,        // custom panel, not the generic CRUD table
    readOnly: true,
    fields: [],
    columns: [],
  },
  {
    path: 'audit',
    base: '/ai/v1',
    title: 'AI Audit',
    readOnly: true,
    fields: [],
    columns: ['createdAt', 'useCase', 'provider', 'model', 'prompt', 'response'],
  },
];

// Presentation names for raw TMF field keys (fallback: the key itself).
const COLUMN_LABELS = {
  lifecycleStatus: 'Status', isBundle: 'Bundle', lastUpdate: 'Updated',
  productOffering: 'Offering', stockedQuantity: 'Stocked', reservedQuantity: 'Reserved',
  availableQuantity: 'Available', billNo: 'Bill no', relatedParty: 'Customer',
  billingPeriod: 'Period', amountDue: 'Amount due', postcodePrefix: 'Postcode prefix',
  validFor: 'Window', triggerEventType: 'Trigger', promotionCode: 'Promo code',
  adjustmentValue: 'Adjustment', priceType: 'Price type',
  recurringChargePeriodType: 'Charge period', phoneNumber: 'Phone number',
  otherOperator: 'Other operator', clearinghouse: 'Clearinghouse',
  scheduledCutover: 'Cutover', createdAt: 'When', useCase: 'Use case',
};
const EVENT_LABELS = Object.fromEntries(TRIGGER_EVENTS.map((t) => [t.value, t.label]));

const el = (id) => document.getElementById(id);

// Role-scoped tabs: the console only shows the areas this operator's token can
// actually use (the APIs enforce the same roles server-side — hiding a tab is
// ergonomics, the 403 underneath is the security).
// Gates use STAFF-grade roles: the default 'customer' composite carries baseline
// read/write (customers pay bills, book slots), so back-office visibility keys
// on roles customers never hold.
const TAB_ROLE = {
  productOffering: 'catalog:write',
  productSpecification: 'catalog:write',
  productOfferingPrice: 'catalog:write',
  productStock: 'stock:read',
  customerBill: 'billing:admin',
  serviceableArea: 'qualification:write',
  appointment: 'appointment:admin',
  campaign: 'campaign:read',
  policyRule: 'policy:read',
  article: 'knowledge:write',
  profile: 'insight:read',
  numberPortingOrder: 'porting:write',
  copilot: 'catalog:write',
  staff: 'roles:admin',
  audit: 'ai:use',
};
let visible = RESOURCES;
function computeVisible() {
  const roles = (tokenClaims().realm_access || {}).roles || [];
  visible = RESOURCES.filter((r) => !TAB_ROLE[r.path] || roles.includes(TAB_ROLE[r.path]));
}

let active = RESOURCES[0];
let offset = 0;
let editingId = null;
let controls = {}; // field name -> {get, set, reset}

function fmtCell(value) {
  if (value == null) return '—';
  if (typeof value === 'boolean') return value ? 'yes' : '—';
  if (Array.isArray(value)) return value.map((v) => v.name || v.id).join(', ') || '—';
  if (typeof value === 'object') {
    if (value.value != null) return `${value.value} ${value.unit || ''}`.trim();
    if (value.amount != null) return `${value.amount} ${value.units || ''}`.trim();
    if (value.startDateTime) return `${value.startDateTime} → ${value.endDateTime || ''}`.trim();
    return value.name || value.id || '—';
  }
  if (/^\d{4}-\d{2}-\d{2}T/.test(String(value))) {
    return String(value).slice(0, 19).replace('T', ' ');
  }
  return String(value);
}

function refObject(field, option) {
  return {
    id: option.value,
    href: `${field.base || API_BASE}/${field.resource}/${option.value}`,
    name: option.dataset.name,
    '@referredType': field.referredType,
  };
}

async function loadPicklist(field) {
  const res = await authFetch(`${field.base || API_BASE}/${field.resource}?offset=0&limit=${REF_PICKLIST_LIMIT}`);
  return res.json();
}

function renderTabs() {
  el('tabs').replaceChildren(...visible.map((r) => {
    const b = document.createElement('button');
    b.textContent = r.title;
    b.className = r === active ? 'tab on' : 'tab';
    b.addEventListener('click', () => { active = r; offset = 0; stopEditing();
      sessionStorage.setItem('bss.console.tab', r.path); renderTabs(); loadList(); });
    return b;
  }));
}

function textControl(field, type) {
  const input = document.createElement('input');
  input.type = type;
  if (type === 'number') input.step = 'any';
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.required = Boolean(field.required);
  controls[field.name] = {
    get: () => {
      const v = input.value.trim();
      if (!v) return undefined;
      return type === 'number' ? Number(v) : v;
    },
    set: (item) => { input.value = item[field.name] ?? ''; },
  };
  return [input];
}

function checkboxControl(field) {
  const input = document.createElement('input');
  input.type = 'checkbox';
  input.name = field.name;
  controls[field.name] = {
    get: () => input.checked,
    set: (item) => { input.checked = Boolean(item[field.name]); },
  };
  return [input];
}

function jsonTextControl(field) {
  const input = document.createElement('textarea');
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.rows = 3;
  controls[field.name] = {
    get: () => {
      const v = input.value.trim();
      if (!v) return undefined;
      try {
        return JSON.parse(v);
      } catch {
        throw new Error(`${field.label}: not valid JSON`);
      }
    },
    set: (item) => {
      input.value = item[field.name] ? JSON.stringify(item[field.name], null, 1) : '';
    },
  };
  return [input];
}

function selectControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.required = Boolean(field.required);
  select.append(new Option('—', ''));
  for (const opt of field.options) {
    select.append(new Option(opt.label, opt.value));
  }
  controls[field.name] = {
    get: () => select.value || undefined,
    set: (item) => { select.value = item[field.name] ?? ''; },
  };
  return [select];
}

function longTextControl(field) {
  const input = document.createElement('textarea');
  input.name = field.name;
  input.placeholder = field.placeholder || '';
  input.required = Boolean(field.required);
  input.rows = 3;
  input.style.font = 'inherit';
  controls[field.name] = {
    get: () => input.value.trim() || undefined,
    set: (item) => { input.value = item[field.name] ?? ''; },
  };
  return [input];
}

/** Picklist over an API resource whose chosen value is one plain attribute. */
function codePickControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.append(new Option('—', ''));
  loadPicklist(field).then((items) => {
    for (const item of items) {
      const code = item[field.attribute];
      if (code) select.append(new Option(`${code} — ${item.name || ''}`.trim(), code));
    }
  });
  controls[field.name] = {
    get: () => select.value || undefined,
    set: (item) => { select.value = item[field.name] ?? ''; },
  };
  return [select];
}

function moneyControl(field) {
  const amount = document.createElement('input');
  amount.type = 'number';
  amount.step = 'any';
  amount.placeholder = 'amount';
  const unit = document.createElement('input');
  unit.placeholder = 'currency (EUR)';
  unit.className = 'unit';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  row.append(amount, unit);
  controls[field.name] = {
    get: () => {
      if (!amount.value.trim()) return undefined;
      return { unit: unit.value.trim() || 'EUR', value: Number(amount.value) };
    },
    set: (item) => {
      amount.value = item[field.name]?.value ?? '';
      unit.value = item[field.name]?.unit ?? '';
    },
  };
  return [row];
}

function quantityControl(field) {
  const amount = document.createElement('input');
  amount.type = 'number';
  amount.step = '1';
  amount.min = '0';
  amount.placeholder = 'amount';
  const units = document.createElement('input');
  units.placeholder = 'units (unit)';
  units.className = 'unit';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  row.append(amount, units);
  controls[field.name] = {
    get: () => {
      if (!amount.value.trim()) return undefined;
      return { amount: Number(amount.value), units: units.value.trim() || 'unit' };
    },
    set: (item) => {
      amount.value = item[field.name]?.amount ?? '';
      units.value = item[field.name]?.units ?? '';
    },
  };
  return [row];
}

/** Commitment terms without JSON: none / 12 / 24 months. */
/**
 * Product artwork without JSON or a separate DAM: upload an image, it lands
 * in the document component (TMF667) and the offering's attachment list —
 * exactly what the storefront and app render. Name an image "gallery-*" for
 * the product-page gallery or "variant-<colour>" to follow the colour picker.
 * Operators with their own PIM skip this entirely: the catalog resolves
 * their imagery per tenant through the same attachment contract.
 */
function artworkControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px';
  let entries = [];

  const strip = document.createElement('div');
  strip.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap';

  function redraw() {
    strip.replaceChildren(...entries.map((a, i) => {
      const cell = document.createElement('div');
      cell.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:3px;font-size:10.5px;color:var(--dim,#8a979c)';
      const img = document.createElement('img');
      img.src = a.url;
      img.alt = a.name || '';
      img.style.cssText = 'width:52px;height:64px;object-fit:cover;border:1px solid var(--line);border-radius:6px;background:#fff';
      const cap = document.createElement('span');
      cap.textContent = a.name || 'image';
      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'ghost';
      del.textContent = '✕';
      del.style.cssText = 'padding:0 6px;font-size:10px';
      del.addEventListener('click', () => { entries.splice(i, 1); redraw(); });
      cell.append(img, cap, del);
      return cell;
    }));
    if (!entries.length) {
      strip.innerHTML = '<span style="font-size:12px;color:var(--dim,#8a979c)">No images yet — the shop shows this offering text-only.</span>';
    }
  }

  const row = document.createElement('div');
  row.style.cssText = 'display:flex;gap:8px;align-items:center;flex-wrap:wrap';
  const role = document.createElement('input');
  role.placeholder = 'name, e.g. gallery-front or variant-Icy Blue';
  role.style.cssText = 'flex:1;min-width:220px';
  role.dataset.testid = 'art-role';
  const file = document.createElement('input');
  file.type = 'file';
  file.accept = 'image/*';
  file.dataset.testid = 'art-file';
  const status = document.createElement('span');
  status.style.fontSize = '12px';
  file.addEventListener('change', async () => {
    const picked = file.files[0];
    if (!picked) return;
    status.textContent = 'uploading…';
    try {
      const bytes = new Uint8Array(await picked.arrayBuffer());
      let binary = '';
      for (const b of bytes) binary += String.fromCharCode(b);
      const offeringName = el('fields').querySelector('input[name="name"]')?.value || 'offering';
      const res = await authFetch('/tmf-api/documentManagement/v4/document', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: `${offeringName} — ${role.value || picked.name}`,
          category: 'offering', mimeType: picked.type || 'image/png', content: btoa(binary),
        }),
      });
      if (!res.ok) throw new Error(`upload failed: HTTP ${res.status}`);
      const doc = await res.json();
      entries.push({ name: role.value || `gallery-${entries.length + 1}`,
        mimeType: picked.type || 'image/png', url: doc.attachmentUrl, '@type': 'Attachment' });
      role.value = '';
      file.value = '';
      status.textContent = '✓ added — save the offering to publish';
      redraw();
    } catch (e) {
      status.textContent = e.message;
    }
  });
  row.append(role, file, status);
  box.append(strip, row);
  redraw();

  controls[field.name] = {
    get: () => entries,
    set: (item) => { entries = [...(item[field.name] || [])]; redraw(); },
  };
  return [box];
}

function commitmentControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  for (const [label, months] of [['No commitment', 0], ['12-month commitment', 12], ['24-month commitment', 24]]) {
    select.append(new Option(label, String(months)));
  }
  controls[field.name] = {
    get: () => {
      const months = Number(select.value);
      return months > 0 ? [{ name: `${months}-month commitment`,
        duration: { amount: months, units: 'month' } }] : undefined;
    },
    set: (item) => {
      const term = (item[field.name] || [])[0];
      select.value = String(term?.duration?.amount || 0);
    },
  };
  return [select];
}

/**
 * The bundle composer: a product owner assembles what used to take raw
 * TMF620 JSON — mandatory/optional components and pick-N-of-M choice groups
 * with defaults. Unknown entry shapes survive a round trip untouched.
 */
function bundleComposerControl(field) {
  const box = document.createElement('div');
  box.className = 'composer';
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px';
  let entries = [];       // {kind:'component',refId,role,original} | {kind:'choice',name,lower,upper,def,optionIds}
  let passthrough = [];   // shapes the composer does not understand
  let picklist = [];      // [{id,name}]

  const offeringRef = (id) => {
    const item = picklist.find((p) => p.id === id);
    return { id, href: `${API_BASE}/${field.resource}/${id}`,
      name: item ? (item.name || id) : id, '@referredType': field.referredType };
  };

  function offeringSelect(value, onChange) {
    const sel = document.createElement('select');
    sel.append(new Option('— offering —', ''));
    for (const item of picklist) {
      const option = new Option(item.name || item.id, item.id);
      option.disabled = item.id === editingId;
      sel.append(option);
    }
    sel.value = value || '';
    sel.addEventListener('change', () => onChange(sel.value));
    return sel;
  }

  function removeButton(entry) {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'ghost'; b.textContent = '✕';
    b.addEventListener('click', () => { entries = entries.filter((e) => e !== entry); render(); });
    return b;
  }

  function render() {
    box.replaceChildren();
    for (const entry of entries) {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:6px;align-items:center;flex-wrap:wrap';
      if (entry.kind === 'component') {
        row.dataset.composerRow = 'component';
        const role = document.createElement('select');
        role.append(new Option('Included (mandatory)', 'mandatory'),
          new Option('Optional add-on', 'optional'));
        role.value = entry.role;
        role.addEventListener('change', () => { entry.role = role.value; });
        row.append(offeringSelect(entry.refId, (v) => { entry.refId = v; }), role, removeButton(entry));
      } else {
        row.dataset.composerRow = 'choice';
        row.style.cssText += ';border-left:3px solid var(--teal);padding-left:8px';
        const name = document.createElement('input');
        name.placeholder = 'Choice group name (e.g. Choose your phone)';
        name.value = entry.name; name.style.minWidth = '220px';
        name.addEventListener('input', () => { entry.name = name.value; });
        const lower = document.createElement('input');
        lower.type = 'number'; lower.min = '0'; lower.value = entry.lower;
        lower.style.width = '4.5em'; lower.title = 'minimum picks';
        lower.addEventListener('input', () => { entry.lower = Number(lower.value); });
        const upper = document.createElement('input');
        upper.type = 'number'; upper.min = '1'; upper.value = entry.upper;
        upper.style.width = '4.5em'; upper.title = 'maximum picks';
        upper.addEventListener('input', () => { entry.upper = Number(upper.value); });
        const opts = document.createElement('select');
        opts.multiple = true; opts.size = 4; opts.style.minWidth = '220px';
        for (const item of picklist) {
          const option = new Option(item.name || item.id, item.id);
          option.selected = entry.optionIds.includes(item.id);
          option.disabled = item.id === editingId;
          opts.append(option);
        }
        const def = document.createElement('select');
        const syncDefault = () => {
          entry.optionIds = [...opts.selectedOptions].map((o) => o.value);
          def.replaceChildren(new Option('no default', ''));
          for (const id of entry.optionIds) {
            def.append(new Option('default: ' + (picklist.find((p) => p.id === id)?.name || id), id));
          }
          def.value = entry.optionIds.includes(entry.def) ? entry.def : '';
          entry.def = def.value;
        };
        opts.addEventListener('change', syncDefault);
        def.addEventListener('change', () => { entry.def = def.value; });
        const pickLabel = document.createElement('span');
        pickLabel.className = 'dimhint';
        pickLabel.textContent = 'pick';
        const dash = document.createElement('span');
        dash.className = 'dimhint';
        dash.textContent = '–';
        row.append(name, pickLabel, lower, dash, upper, opts, def, removeButton(entry));
        syncDefault();
      }
      box.append(row);
    }
    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:8px';
    const addComponent = document.createElement('button');
    addComponent.type = 'button'; addComponent.className = 'ghost';
    addComponent.textContent = '+ Component';
    addComponent.dataset.composerAdd = 'component';
    addComponent.addEventListener('click', () => {
      entries.push({ kind: 'component', refId: '', role: 'mandatory' }); render();
    });
    const addChoice = document.createElement('button');
    addChoice.type = 'button'; addChoice.className = 'ghost';
    addChoice.textContent = '+ Choice group';
    addChoice.dataset.composerAdd = 'choice';
    addChoice.addEventListener('click', () => {
      entries.push({ kind: 'choice', name: '', lower: 1, upper: 1, def: '', optionIds: [] }); render();
    });
    actions.append(addComponent, addChoice);
    if (passthrough.length) {
      const note = document.createElement('span');
      note.className = 'dimhint';
      note.textContent = `${passthrough.length} advanced entr${passthrough.length > 1 ? 'ies' : 'y'} kept as-is`;
      actions.append(note);
    }
    box.append(actions);
  }

  loadPicklist(field).then((items) => { picklist = items; render(); });

  controls[field.name] = {
    get: () => {
      const out = [];
      for (const entry of entries) {
        if (entry.kind === 'component' && entry.refId) {
          const base = entry.original && entry.original.id === entry.refId
            ? { ...entry.original } : offeringRef(entry.refId);
          base.bundledProductOfferingOption = {
            numberRelOfferLowerLimit: entry.role === 'optional' ? 0 : 1,
            numberRelOfferUpperLimit: 1,
          };
          out.push(base);
        } else if (entry.kind === 'choice' && entry.name && entry.optionIds.length) {
          out.push({
            '@type': 'BundledProductOfferingChoice',
            name: entry.name,
            ...(entry.def ? { default: entry.def } : {}),
            numberRelOfferLowerLimit: entry.lower,
            numberRelOfferUpperLimit: entry.upper,
            options: entry.optionIds.map(offeringRef),
          });
        }
      }
      out.push(...passthrough);
      return out.length ? out : undefined;
    },
    set: (item) => {
      entries = []; passthrough = [];
      for (const e of (item[field.name] || [])) {
        if (Array.isArray(e.options)) {
          entries.push({ kind: 'choice', name: e.name || '',
            lower: e.numberRelOfferLowerLimit ?? 1, upper: e.numberRelOfferUpperLimit ?? 1,
            def: e.default || '', optionIds: e.options.map((o) => o.id).filter(Boolean) });
        } else if (e.id) {
          entries.push({ kind: 'component', refId: e.id, original: e,
            role: e.bundledProductOfferingOption?.numberRelOfferLowerLimit === 0 ? 'optional' : 'mandatory' });
        } else {
          passthrough.push(e);
        }
      }
      render();
    },
  };
  return [box];
}

function refControl(field, multiple) {
  const select = document.createElement('select');
  select.name = field.name;
  // Entries that are not plain refs (e.g. bundle choice groups, edited via the
  // API) must survive an edit-save round trip untouched — and plain refs that
  // carry extra metadata (bundledProductOfferingOption cardinality) must keep it.
  let passthrough = [];
  let originals = {};
  if (multiple) {
    select.multiple = true;
    select.size = 4;
  } else {
    select.append(new Option('—', ''));
  }
  loadPicklist(field).then((items) => {
    for (const item of items) {
      const option = new Option(item.name || item.id, item.id);
      option.dataset.name = item.name || item.id;
      option.disabled = item.id === editingId;
      select.append(option);
    }
    if (pendingSelection[field.name]) {
      applySelection(select, pendingSelection[field.name]);
      delete pendingSelection[field.name];
    }
  });
  controls[field.name] = {
    get: () => {
      const picked = [...select.selectedOptions].filter((o) => o.value);
      if (!picked.length && !passthrough.length) return undefined;
      return multiple
        ? [...picked.map((o) => originals[o.value] || refObject(field, o)), ...passthrough]
        : refObject(field, picked[0]);
    },
    set: (item) => {
      const refs = multiple ? (item[field.name] || []) : [item[field.name]].filter(Boolean);
      passthrough = multiple ? refs.filter((r) => !r.id || r.options) : [];
      originals = Object.fromEntries(refs.filter((r) => r.id && !r.options).map((r) => [r.id, r]));
      const ids = refs.filter((r) => r.id && !r.options).map((r) => r.id);
      if (select.options.length > (multiple ? 0 : 1)) {
        applySelection(select, ids);
      } else {
        pendingSelection[field.name] = ids; // picklist still loading
      }
    },
  };
  return [select];
}

let pendingSelection = {};

function applySelection(select, ids) {
  for (const option of select.options) {
    option.selected = ids.includes(option.value);
    if (option.value) option.disabled = option.value === editingId;
  }
}

function renderEditor() {
  controls = {};
  pendingSelection = {};
  el('editor').hidden = Boolean(active.readOnly);
  el('fields').replaceChildren(...active.fields.map((f) => {
    const wrap = document.createElement('label');
    wrap.className = f.kind === 'checkbox' ? 'field check' : 'field';
    const caption = document.createElement('span');
    caption.textContent = f.label + (f.required ? ' *' : '');
    const parts =
      f.kind === 'checkbox' ? checkboxControl(f) :
      f.kind === 'money' ? moneyControl(f) :
      f.kind === 'quantity' ? quantityControl(f) :
      f.kind === 'ref' ? refControl(f, false) :
      f.kind === 'reflist' ? refControl(f, true) :
      f.kind === 'commitment' ? commitmentControl(f) :
      f.kind === 'artwork' ? artworkControl(f) :
      f.kind === 'bundlecomposer' ? bundleComposerControl(f) :
      f.kind === 'jsontext' ? jsonTextControl(f) :
      f.kind === 'select' ? selectControl(f) :
      f.kind === 'longtext' ? longTextControl(f) :
      f.kind === 'codepick' ? codePickControl(f) :
      textControl(f, f.kind === 'number' ? 'number' : 'text');
    wrap.append(caption, ...parts);
    wrap.dataset.field = f.name;
    return wrap;
  }));
  wireVisibility();
  if (active.aiAssist) {
    el('fields').append(aiAssistRow(active.aiAssist));
  }
  if (active.tester) {
    el('fields').append(testerRow());
  }
}

/**
 * Progressive disclosure: a field with showWhen {field, in: [...]} only renders
 * while the controlling select holds one of those values. An empty controlling
 * value (e.g. editing an existing row) shows everything — never hide data.
 */
function wireVisibility() {
  const dependents = active.fields.filter((f) => f.showWhen);
  if (!dependents.length) return;
  const sources = [...new Set(dependents.map((f) => f.showWhen.field))];
  const apply = () => {
    for (const f of dependents) {
      const src = el('fields').querySelector(`[name="${f.showWhen.field}"]`);
      const v = src ? src.value : '';
      const wrap = el('fields').querySelector(`[data-field="${f.name}"]`);
      // No kind chosen yet: creating → keep the form minimal; editing an
      // existing row → show everything, never hide data.
      const show = v ? f.showWhen.in.includes(v) : Boolean(editingId);
      if (wrap) wrap.style.display = show ? '' : 'none';
    }
  };
  for (const name of sources) {
    const src = el('fields').querySelector(`[name="${name}"]`);
    if (src) src.addEventListener('change', apply);
  }
  apply();
}

/**
 * Rules dry-run: a sample order/cart in, the live engine's verdict out —
 * exactly the /evaluate and /price calls the order pipeline and billing make,
 * so what you see here is what a customer would get.
 */
function testerRow() {
  const wrap = document.createElement('div');
  wrap.className = 'field aiassist';
  const caption = document.createElement('span');
  caption.textContent = 'Dry run — test the enabled rules';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  // Each dry-run input carries a visible mini-label — placeholders vanish
  // the moment a value is typed, labels don't.
  const labelled = (labelText, input) => {
    const box = document.createElement('span');
    box.style.cssText = 'display:inline-flex;flex-direction:column;gap:2px;';
    const cap = document.createElement('span');
    cap.textContent = labelText;
    cap.style.cssText = 'font-size:10px;color:var(--dim,#8a979c);letter-spacing:0.04em;text-transform:uppercase;';
    box.append(cap, input);
    return box;
  };
  const offering = document.createElement('input');
  offering.placeholder = 'any offering';
  offering.id = 'test-offering';
  const qty = document.createElement('input');
  qty.type = 'number';
  qty.value = '1';
  qty.id = 'test-qty';
  qty.style.maxWidth = '70px';
  const subtotal = document.createElement('input');
  subtotal.type = 'number';
  subtotal.value = '100';
  subtotal.id = 'test-subtotal';
  subtotal.style.maxWidth = '100px';
  const verified = document.createElement('label');
  verified.className = 'small';
  const verifiedBox = document.createElement('input');
  verifiedBox.type = 'checkbox';
  verifiedBox.id = 'test-verified';
  verified.append(verifiedBox, ' verified customer');
  const orderBtn = document.createElement('button');
  orderBtn.type = 'button';
  orderBtn.className = 'ghost';
  orderBtn.id = 'test-order';
  orderBtn.textContent = 'Test order';
  const priceBtn = document.createElement('button');
  priceBtn.type = 'button';
  priceBtn.className = 'ghost';
  priceBtn.id = 'test-price';
  priceBtn.textContent = 'Test price';
  const result = document.createElement('div');
  result.className = 'small';
  result.id = 'test-result';

  const context = () => {
    const id = offering.value.trim();
    const quantity = Number(qty.value) || 1;
    return {
      offeringIds: id ? [id] : [],
      quantityByOffering: id ? { [id]: quantity } : {},
      maxLineQuantity: quantity,
      totalQuantity: quantity,
      lineCount: id ? 1 : 0,
      subtotal: Number(subtotal.value) || 0,
      verifiedIdentity: verifiedBox.checked,
    };
  };
  orderBtn.addEventListener('click', async () => {
    result.textContent = '…';
    try {
      const res = await authFetch(`${POLICY_BASE}/evaluate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ domain: 'order', context: context() }),
      });
      const v = await res.json();
      result.textContent = v.decision === 'deny'
        ? `✕ DENIED by "${v.ruleName}": ${v.message}`
        : '✓ ALLOWED — no enabled order rule blocks this';
    } catch (e) { result.textContent = 'dry run failed: ' + e.message; }
  });
  priceBtn.addEventListener('click', async () => {
    result.textContent = '…';
    try {
      const res = await authFetch(`${POLICY_BASE}/price`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ context: context() }),
      });
      const v = await res.json();
      const adj = (v.adjustments || [])
        .map((a) => `${a.label}: ${Number(a.amount) > 0 ? '+' : ''}${Number(a.amount).toFixed(2)}`)
        .join(' · ');
      result.textContent = adj
        ? `base ${Number(v.basePrice).toFixed(2)} → ${adj} → total ${Number(v.total).toFixed(2)}`
        : `no pricing rule matches — price stays ${Number(v.basePrice).toFixed(2)}`;
    } catch (e) { result.textContent = 'dry run failed: ' + e.message; }
  });

  row.append(labelled('Offering id', offering), labelled('Qty', qty), labelled('Subtotal €', subtotal), verified, orderBtn, priceBtn);
  wrap.append(caption, row, result);
  return wrap;
}


/* ---------------- Staff tab: TMF672 over the tenant's own IdP ---------------- */
/* A tenant admin manages what each operator can do, in business terms (areas),
 * without ever opening the identity provider. Grants and revokes go through
 * the user-roles component, which translates to the tenant's IdP behind its
 * admin seam. */
const ROLES_BASE = '/tmf-api/rolesAndPermissionsManagement/v4';
const AREAS = [
  { name: 'Product catalog', hint: 'offerings, prices, stock, serviceability',
    roles: ['catalog:read', 'catalog:write', 'stock:read', 'stock:write', 'qualification:read', 'qualification:write'] },
  { name: 'Rules & pricing', hint: 'business rules, dynamic pricing',
    roles: ['policy:read', 'policy:write'] },
  { name: 'Marketing', hint: 'campaigns and promo codes',
    roles: ['campaign:read', 'campaign:write', 'promotion:read', 'promotion:write'] },
  { name: 'Customer bills', hint: 'back-office bill view',
    roles: ['billing:admin'] },
  { name: 'Appointments', hint: 'back-office appointment view',
    roles: ['appointment:admin'] },
  { name: 'Number porting', hint: 'port-in/out management',
    roles: ['porting:read', 'porting:write'] },
  { name: 'CSR powers', hint: 'fulfil orders, cease services',
    roles: ['service:read', 'service:write', 'ordering:write'] },
  { name: 'AI tools', hint: 'copilot, drafting, audit view',
    roles: ['ai:use'] },
  { name: 'Staff administration', hint: 'this tab',
    roles: ['roles:admin'] },
];


/* ---------------- Product Copilot: chat about a product, create it ---------------- */
/* The owner chats; the intelligence component PROPOSES (specs, prices,
 * offerings, bundles as TMF620 payloads); this panel validates the proposal,
 * shows it as a human card, and on "Create it" applies it in dependency
 * order with the OWNER's token — spec, prices, offerings, bundle links.
 * The model never writes; partial failures roll back in reverse. */
// The chat survives reloads and re-auth redirects: a long model
// conversation must never be lost to a token bounce.
const copilotChat = JSON.parse(sessionStorage.getItem('bss.console.copilotChat') || '[]');
function copilotRemember() {
  sessionStorage.setItem('bss.console.copilotChat', JSON.stringify(copilotChat.slice(-40)));
}

function copilotPanel() {
  let panel = document.getElementById('copilot-panel');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'copilot-panel';
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  return panel;
}

async function copilotCatalogContext() {
  const [offerings, prices] = await Promise.all([
    authFetch(`${API_BASE}/productOffering?limit=60`).then((r) => r.json()).catch(() => []),
    authFetch(`${API_BASE}/productOfferingPrice?limit=5`).then((r) => r.json()).catch(() => []),
  ]);
  const categories = [...new Set(offerings.flatMap((o) => (o.category || []).map((c) => c.name)).filter(Boolean))];
  const currency = prices.find((p) => p.price?.unit)?.price?.unit || 'EUR';
  return {
    categories,
    currency,
    offerings: offerings.slice(0, 40).map((o) => ({ name: o.name, category: (o.category || [])[0]?.name })),
  };
}

/**
 * Deterministic repair of KNOWN-wrong model habits before validation: a
 * non-positive "price" is always a discount in disguise (discounts are
 * pricing rules), and prodSpecCharValueUse must be a list. Everything
 * dropped is reported on the card — repaired, never hidden.
 */
function copilotSanitize(proposal) {
  const notes = [];
  const prices = proposal.prices || [];
  const bad = prices.filter((p) => !(Number(p.price?.value) > 0));
  if (bad.length) {
    proposal.prices = prices.filter((p) => Number(p.price?.value) > 0);
    const badRefs = new Set(bad.map((b) => b.ref));
    for (const o of proposal.offerings || []) {
      o.priceRefs = (o.priceRefs || []).filter((r) => !badRefs.has(r));
    }
    notes.push(`dropped ${bad.length} non-positive "price" — a discount belongs in the pricing rule, which this proposal ${(proposal.pricingRules || []).length ? 'has' : 'is missing'}`);
  }
  for (const p of proposal.prices || []) {
    if (p.prodSpecCharValueUse) {
      // a valid condition is a list of {name, productSpecCharacteristicValue}
      // objects; anything else (strings, prose) is model noise — drop it and
      // say so, the price itself is fine unconditioned
      const valid = Array.isArray(p.prodSpecCharValueUse)
        ? p.prodSpecCharValueUse.filter((c) => c && typeof c === 'object' && !Array.isArray(c) && c.name)
        : [];
      if (valid.length !== (Array.isArray(p.prodSpecCharValueUse) ? p.prodSpecCharValueUse.length : 1)) {
        notes.push(`ignored a malformed condition on price "${p.name}"`);
      }
      if (valid.length) p.prodSpecCharValueUse = valid;
      else delete p.prodSpecCharValueUse;
    }
  }
  return notes;
}

function copilotValidate(proposal, context) {
  const problems = [];
  const offerings = proposal.offerings || [];
  const rules = proposal.pricingRules || [];
  if (!offerings.length && !rules.length) problems.push('the proposal creates no offerings');
  const priceRefs = new Set((proposal.prices || []).map((x) => x.ref));
  const specRefs = new Set((proposal.specs || []).map((x) => x.ref));
  const offeringRefs = new Set(offerings.map((x) => x.ref));
  for (const price of proposal.prices || []) {
    if (!(Number(price.price?.value) > 0)) problems.push(`price "${price.name}" has no positive value`);
  }
  for (const o of offerings) {
    if (!o.name || !String(o.name).trim()) problems.push('an offering has no name');
    if (context.offerings.some((x) => x.name === o.name)) problems.push(`"${o.name}" already exists in the catalog`);
    for (const ref of o.priceRefs || []) {
      if (!priceRefs.has(ref)) problems.push(`offering "${o.name}" references unknown price ${ref}`);
    }
    if (o.specRef && !specRefs.has(o.specRef)) problems.push(`offering "${o.name}" references unknown spec ${o.specRef}`);
    for (const child of o.bundledChildren || []) {
      if (child.offeringRef && !offeringRefs.has(child.offeringRef)) {
        problems.push(`bundle "${o.name}" references unknown offering ${child.offeringRef}`);
      }
      if (child.existingName && !context.offerings.some((x) => x.name === child.existingName)) {
        problems.push(`bundle "${o.name}" references "${child.existingName}", not found in the catalog`);
      }
    }
    const cat = (o.category || [])[0]?.name;
    if (cat && context.categories.length && !context.categories.includes(cat)) {
      problems.push(`category "${cat}" is new — it will be created by use (placement may need a seed)`);
    }
  }
  const offeringRefsAll = new Set(offerings.map((x) => x.ref));
  for (const rule of rules) {
    if (!Number(rule.adjustmentValue)) problems.push(`rule "${rule.name}" has no adjustment value`);
    for (const target of rule.whenCartHas || []) {
      if (!offeringRefsAll.has(target) && !context.offerings.some((x) => x.name === target)) {
        problems.push(`rule "${rule.name}" references "${target}", not in this proposal or the catalog`);
      }
    }
  }
  return problems;
}

async function copilotExecute(proposal) {
  const created = []; // [{kind, id, base?}] for rollback, reverse order
  const jsonOf = async (res, what) => {
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      throw new Error(`${what}: ${problem.message || 'HTTP ' + res.status}`);
    }
    return res.json();
  };
  const post = (path, body) => authFetch(`${API_BASE}/${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  try {
    const specs = {};    // ref -> {id, name}
    for (const spec of proposal.specs || []) {
      const made = await jsonOf(await post('productSpecification', {
        name: spec.name, brand: spec.brand, lifecycleStatus: 'Active',
        productSpecCharacteristic: spec.productSpecCharacteristic || [],
      }), `spec "${spec.name}"`);
      specs[spec.ref] = made;
      created.push({ kind: 'productSpecification', id: made.id });
    }
    const prices = {};
    for (const price of proposal.prices || []) {
      // real models occasionally skip a price name or write "EUR/month" as
      // the unit — normalize deterministically instead of failing the apply
      const priceName = price.name
        || `${(proposal.offerings || []).find((o) => (o.priceRefs || []).includes(price.ref))?.name || 'Copilot'} price`;
      const money = price.price ? { ...price.price,
        unit: String(price.price.unit || 'EUR').split('/')[0].trim() } : price.price;
      const made = await jsonOf(await post('productOfferingPrice', {
        name: priceName, priceType: price.priceType || 'recurring',
        recurringChargePeriodType: price.recurringChargePeriodType,
        price: money, prodSpecCharValueUse: price.prodSpecCharValueUse || undefined,
        lifecycleStatus: 'Active',
      }), `price "${priceName}"`);
      prices[price.ref] = made;
      created.push({ kind: 'productOfferingPrice', id: made.id });
    }
    const offerings = {};
    const plain = (proposal.offerings || []).filter((o) => !(o.bundledChildren || []).length);
    const bundles = (proposal.offerings || []).filter((o) => (o.bundledChildren || []).length);
    for (const o of [...plain, ...bundles]) {
      const body = {
        name: o.name, description: o.description, lifecycleStatus: 'Active',
        isBundle: Boolean(o.isBundle || (o.bundledChildren || []).length),
        category: o.category, productOfferingTerm: o.productOfferingTerm,
      };
      if (o.specRef && specs[o.specRef]) {
        body.productSpecification = { id: specs[o.specRef].id, name: specs[o.specRef].name,
          '@referredType': 'ProductSpecification' };
      }
      if ((o.priceRefs || []).length) {
        body.productOfferingPrice = o.priceRefs.map((ref) => ({
          id: prices[ref].id, name: prices[ref].name, '@referredType': 'ProductOfferingPrice' }));
      }
      if ((o.bundledChildren || []).length) {
        body.bundledProductOffering = o.bundledChildren.map((child) => {
          const target = child.offeringRef ? offerings[child.offeringRef] : null;
          const entry = target
            ? { id: target.id, name: target.name, href: target.href, '@referredType': 'ProductOffering' }
            : { name: child.existingName, '@referredType': 'ProductOffering' };
          if (child.optional) {
            entry.bundledProductOfferingOption = {
              numberRelOfferLowerLimit: 0, numberRelOfferUpperLimit: 1 };
          }
          return entry;
        });
      }
      const made = await jsonOf(await post('productOffering', body), `offering "${o.name}"`);
      offerings[o.ref] = made;
      created.push({ kind: 'productOffering', id: made.id });
    }
    // cross-product discounts land in the pricing-rules engine — the same
    // rules the cart preview and the billing run already apply
    const catalogNames = {}; // existing-offering name -> id, resolved lazily
    for (const rule of proposal.pricingRules || []) {
      const targets = [];
      for (const t of rule.whenCartHas || []) {
        if (offerings[t]) { targets.push(offerings[t].id); continue; }
        if (!(t in catalogNames)) {
          const found = await (await authFetch(
            `${API_BASE}/productOffering?name=${encodeURIComponent(t)}`)).json().catch(() => []);
          catalogNames[t] = found[0]?.id || null;
        }
        if (catalogNames[t]) targets.push(catalogNames[t]);
      }
      const clauses = targets.map((id) => ({ in: [id, { var: 'offeringIds' }] }));
      // consumer-only: the rule requires the ABSENCE of a paying organization,
      // so it can never touch a company order or a consolidated invoice
      if (rule.audience === 'consumer') {
        clauses.push({ '!': { var: 'organizationId' } });
      } else if (rule.audience === 'business') {
        clauses.push({ '!!': { var: 'organizationId' } });
      }
      const madeRule = await jsonOf(await authFetch(`${POLICY_BASE}/policyRule`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: rule.name, domain: 'pricing', effect: 'adjust', priority: 100, enabled: true,
          condition: JSON.stringify(clauses.length > 1 ? { and: clauses } : clauses[0] || { '==': [1, 1] }),
          adjustmentType: rule.adjustmentType || 'percent',
          adjustmentValue: Number(rule.adjustmentValue),
          message: rule.message || rule.name,
        }),
      }), `pricing rule "${rule.name}"`);
      created.push({ kind: 'policyRule', id: madeRule.id, base: POLICY_BASE });
    }
    return Object.values(offerings);
  } catch (e) {
    // roll back what was made, newest first — a half-created product family
    // is worse than none
    for (const row of created.reverse()) {
      await authFetch(`${row.base || API_BASE}/${row.kind}/${row.id}`, { method: 'DELETE' }).catch(() => {});
    }
    throw e;
  }
}

function copilotProposalCard(reply, context, log) {
  const proposal = reply.proposal;
  const repairs = copilotSanitize(proposal);
  const card = document.createElement('div');
  card.className = 'copilot-proposal';
  card.dataset.testid = 'copilot-proposal';
  const rows = [];
  for (const spec of proposal.specs || []) rows.push(`spec · ${spec.name}`);
  for (const price of proposal.prices || []) {
    const cond = (price.prodSpecCharValueUse || []).length ? ' (conditioned)' : '';
    rows.push(`price · ${price.name} — ${price.price?.value} ${price.price?.unit}`
      + (price.priceType === 'recurring' ? '/month' : ' one-time') + cond);
  }
  for (const o of proposal.offerings || []) {
    const kids = (o.bundledChildren || []).length ? ` — bundle of ${o.bundledChildren.length}` : '';
    const term = (o.productOfferingTerm || [])[0]?.duration;
    const bind = term?.amount ? ` — ${term.amount}-${term.units || 'month'} commitment` : '';
    rows.push(`offering · ${o.name} [${(o.category || [])[0]?.name || 'no category'}]${kids}${bind}`);
  }
  for (const rule of proposal.pricingRules || []) {
    const scope = rule.audience === 'consumer' ? ' (private customers only)'
      : rule.audience === 'business' ? ' (business customers only)' : '';
    rows.push(`pricing rule · ${rule.name} — ${rule.adjustmentValue}`
      + (rule.adjustmentType === 'amount' ? '' : '%')
      + ` when the cart has ${(rule.whenCartHas || []).join(' + ')}${scope}`);
  }
  const problems = copilotValidate(proposal, context);
  const hardProblems = problems.filter((x) => !x.includes('is new'));
  card.innerHTML = `<b>The copilot will create:</b>
    <ul>${rows.map((r) => `<li>${r}</li>`).join('')}</ul>
    ${repairs.length ? `<p class="dim" style="font-size:12px">auto-repaired: ${repairs.join('; ')}</p>` : ''}
    ${problems.length ? `<p class="copilot-warn">${problems.join('<br>')}</p>` : ''}`;
  const actions = document.createElement('div');
  const create = document.createElement('button');
  create.className = 'primary';
  create.textContent = 'Yes — create it';
  create.dataset.testid = 'copilot-create';
  create.disabled = hardProblems.length > 0;
  const status = document.createElement('span');
  status.style.marginLeft = '10px';
  create.addEventListener('click', async () => {
    create.disabled = true;
    status.textContent = 'creating…';
    try {
      const made = await copilotExecute(proposal);
      status.innerHTML = '';
      const done = document.createElement('div');
      done.className = 'copilot-msg copilot-done';
      done.dataset.testid = 'copilot-created';
      done.innerHTML = `✓ Created ${made.length + (proposal.specs || []).length + (proposal.prices || []).length}
        catalog resources. ${made.map((o) =>
          `<a href="/shop/offering/${o.id}" target="_blank">${o.name} — see it in the shop</a>`).join(' · ')}`;
      log.append(done);
      log.scrollTop = log.scrollHeight;
      copilotChat.push({ role: 'assistant', content: 'Created: ' + made.map((o) => o.name).join(', ') });
      copilotRemember();
    } catch (e) {
      create.disabled = false;
      status.textContent = 'rolled back — ' + e.message;
    }
  });
  actions.append(create, status);
  if (hardProblems.length) {
    // close the loop: the validator's findings go BACK to the model as a
    // corrective turn instead of dead-ending the owner on red text
    const fix = document.createElement('button');
    fix.className = 'ghost';
    fix.textContent = 'Ask the copilot to fix this';
    fix.dataset.testid = 'copilot-fix';
    fix.style.marginLeft = '8px';
    fix.addEventListener('click', () => {
      const input = document.getElementById('copilot-input');
      const send = document.getElementById('copilot-send');
      if (input && send) {
        input.value = 'That proposal failed validation: ' + hardProblems.join('; ')
          + '. Please revise it — remember a proposal needs at least one offering.';
        send.click();
      }
    });
    actions.append(fix);
  }
  card.append(actions);
  return card;
}

async function renderProductCopilot() {
  const panel = copilotPanel();
  panel.replaceChildren();
  const context = await copilotCatalogContext();

  const intro = document.createElement('p');
  intro.className = 'dim';
  intro.style.cssText = 'font-size:13px;margin:6px 0 10px';
  intro.textContent = 'Describe the product you want to sell — the copilot models it '
    + 'against this catalog and creates it when you confirm. It proposes; you decide.';
  const log = document.createElement('div');
  log.id = 'copilot-log';
  const bar = document.createElement('div');
  bar.className = 'staffbar';
  const input = document.createElement('input');
  input.placeholder = 'e.g. I want to sell a streaming service…';
  input.id = 'copilot-input';
  input.style.flex = '1';
  const send = document.createElement('button');
  send.className = 'primary';
  send.textContent = 'Send';
  send.id = 'copilot-send';
  // a fresh product deserves a fresh conversation — the history rides into
  // every request (context cost) and steers the model (old asks bleed in)
  const clear = document.createElement('button');
  clear.className = 'ghost';
  clear.textContent = 'Clear chat';
  clear.id = 'copilot-clear';
  clear.type = 'button';
  clear.addEventListener('click', () => {
    copilotChat.length = 0;
    copilotRemember();
    log.replaceChildren();
    input.focus();
  });

  const bubble = (cls, text) => {
    const b = document.createElement('div');
    b.className = 'copilot-msg ' + cls;
    b.textContent = text;
    log.append(b);
    log.scrollTop = log.scrollHeight;
    return b;
  };
  for (const m of copilotChat) {
    if (m.role === 'user') bubble('copilot-user', m.content);
    else bubble('copilot-ai', m.content);
  }

  async function submit() {
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    bubble('copilot-user', text);
    copilotChat.push({ role: 'user', content: text });
    copilotRemember();
    const thinking = bubble('copilot-ai', '…');
    try {
      const res = await authFetch('/ai/v1/productCopilot', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: copilotChat, catalog: context }),
      });
      if (!res.ok) {
        const problem = await res.json().catch(() => null);
        throw new Error(problem?.message
          ? problem.message + ' — try sending your message again, or rephrase it.'
          : 'copilot unavailable (HTTP ' + res.status + ')');
      }
      const reply = await res.json();
      thinking.textContent = reply.message;
      copilotChat.push({ role: 'assistant', content: reply.message });
      copilotRemember();
      if (reply.kind === 'proposal' && reply.proposal) {
        log.append(copilotProposalCard(reply, context, log));
        log.scrollTop = log.scrollHeight;
      }
    } catch (e) {
      thinking.textContent = e.message;
      thinking.classList.add('copilot-warn');
    }
  }
  send.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });
  bar.append(input, send, clear);
  panel.append(intro, log, bar);
  input.focus();
}

function staffPanel() {
  let panel = document.getElementById('staff-panel');
  if (!panel) {
    panel = document.createElement('div');
    panel.id = 'staff-panel';
    document.querySelector('.table-wrap').after(panel);
  }
  panel.hidden = false;
  return panel;
}

async function renderStaff() {
  const panel = staffPanel();
  panel.replaceChildren();
  const bar = document.createElement('div');
  bar.className = 'staffbar';
  const q = document.createElement('input');
  q.placeholder = 'Search staff by username/email…';
  q.id = 'staff-q';
  const go = document.createElement('button');
  go.className = 'primary';
  go.textContent = 'Search';
  go.id = 'staff-search';
  bar.append(q, go);
  const results = document.createElement('div');
  results.id = 'staff-results';
  const detail = document.createElement('div');
  detail.id = 'staff-detail';
  panel.append(bar, results, detail);

  const search = async () => {
    results.textContent = 'Searching…';
    detail.replaceChildren();
    const res = await authFetch(`${ROLES_BASE}/user?username=${encodeURIComponent(q.value.trim())}`);
    const users = await res.json();
    results.replaceChildren(...users.map((u) => {
      const row = document.createElement('button');
      row.type = 'button';
      row.className = 'staffrow';
      row.textContent = `${u.username}`;
      row.addEventListener('click', () => renderStaffUser(u, detail));
      return row;
    }));
    if (!users.length) results.textContent = 'No staff found.';
  };
  go.addEventListener('click', search);
  q.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); search(); } });
}

async function renderStaffUser(user, detail) {
  detail.textContent = 'Loading permissions…';
  const perms = await (await authFetch(`${ROLES_BASE}/permission?userId=${user.id}`)).json();
  const held = new Map(perms.map((p) => [p.userRole.name, p.id]));   // role -> permission id
  detail.replaceChildren();
  const head = document.createElement('h3');
  head.textContent = user.username;
  head.className = 'staffname';
  const status = document.createElement('span');
  status.className = 'staffstatus';
  head.append(status);
  detail.append(head);
  for (const area of AREAS) {
    const row = document.createElement('label');
    row.className = 'staffarea';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.dataset.area = area.name;
    box.checked = area.roles.every((r) => held.has(r));
    box.addEventListener('change', async () => {
      box.disabled = true;
      status.textContent = ' saving…';
      try {
        if (box.checked) {
          for (const role of area.roles.filter((r) => !held.has(r))) {
            const res = await authFetch(`${ROLES_BASE}/permission`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ user: { id: user.id }, userRole: { name: role } }),
            });
            const created = await res.json();
            held.set(role, created.id);
          }
        } else {
          for (const role of area.roles.filter((r) => held.has(r))) {
            await authFetch(`${ROLES_BASE}/permission/${held.get(role)}`, { method: 'DELETE' });
            held.delete(role);
          }
        }
        status.textContent = ' ✓ saved — takes effect on their next sign-in';
      } catch (e) {
        status.textContent = ' ✗ ' + e.message;
        box.checked = !box.checked;
      }
      box.disabled = false;
    });
    const text = document.createElement('span');
    text.innerHTML = `<b>${area.name}</b> <span class="dimhint">${area.hint}</span>`;
    row.append(box, text);
    detail.append(row);
  }
}

/** A brief in, a draft out — into the message fields, still editable. */
function aiAssistRow(assist) {
  const wrap = document.createElement('div');
  wrap.className = 'field aiassist';
  const caption = document.createElement('span');
  caption.textContent = 'AI assist';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  const brief = document.createElement('input');
  brief.placeholder = assist.placeholder;
  brief.id = 'ai-brief';
  const button = document.createElement('button');
  button.type = 'button';
  button.id = 'ai-draft';
  button.className = 'ghost';
  button.textContent = '✨ Draft';
  button.addEventListener('click', async () => {
    if (!brief.value.trim()) return;
    button.disabled = true;
    button.textContent = 'Drafting…';
    try {
      await assist.draft(brief.value.trim());
    } catch (e) {
      el('editor-error').textContent = 'AI assist: ' + e.message;
      el('editor-error').hidden = false;
    } finally {
      button.disabled = false;
      button.textContent = '✨ Draft';
    }
  });
  row.append(brief, button);
  wrap.append(caption, row);
  return wrap;
}

function stopEditing() {
  editingId = null;
  el('editor-title').textContent = 'New';
  el('save').textContent = 'Create';
  el('cancel-edit').hidden = true;
  el('editor').reset();
  el('editor-error').hidden = true;
  for (const option of el('fields').querySelectorAll('option')) {
    option.disabled = false;
  }
}

function startEditing(item) {
  editingId = item.id;
  el('editor-title').textContent = 'Edit ' + (item.name || item.id);
  el('save').textContent = 'Save changes';
  el('cancel-edit').hidden = false;
  for (const f of active.fields) {
    controls[f.name].set(item);
  }
  el('editor').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function loadList() {
  el('resource-title').textContent = active.title;
  document.getElementById('staff-panel')?.setAttribute('hidden', '');
  document.getElementById('copilot-panel')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.removeAttribute('hidden');
  if (active.copilot) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderProductCopilot();
    return;
  }
  if (active.staff) {
    el('editor').hidden = true;
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    el('listing-body').replaceChildren();
    document.querySelector('.pager')?.setAttribute('hidden', '');
    renderStaff();
    return;
  }
  renderEditor();
  const res = await authFetch(`${active.base || API_BASE}/${active.path}?offset=${offset}&limit=${PAGE_SIZE}`);
  const items = await res.json();
  const total = Number(res.headers.get('X-Total-Count') || items.length);

  el('total').textContent = `${total} total`;
  el('listing-head').replaceChildren((() => {
    const tr = document.createElement('tr');
    for (const c of active.columns) {
      const th = document.createElement('th');
      th.textContent = COLUMN_LABELS[c] || c;
      tr.append(th);
    }
    tr.append(document.createElement('th'));
    return tr;
  })());

  el('listing-body').replaceChildren(...items.map((item) => {
    const tr = document.createElement('tr');
    for (const c of active.columns) {
      const td = document.createElement('td');
      if (item[c] === undefined && active.augmentRow) {
        td.textContent = '…';
        active.augmentRow(item, td).catch(() => { td.textContent = '—'; });
      } else {
        const raw = c === 'triggerEventType' ? (EVENT_LABELS[item[c]] || item[c]) : item[c];
        const text = fmtCell(raw);
        // Long machine values (JSON-logic conditions) get truncated with the
        // full value on hover, so the table never explodes.
        td.textContent = text.length > 64 ? text.slice(0, 61) + '…' : text;
        if (text.length > 64) td.title = text;
      }
      tr.append(td);
    }
    const td = document.createElement('td');
    td.className = 'rowactions';
    if (active.detail) {
      // read-only resources can still OPEN: an inline expansion of what the
      // row contains (a bill's lines), fetched on demand
      const view = document.createElement('button');
      view.textContent = 'View';
      view.className = 'ghost';
      view.dataset.testid = 'row-view';
      view.addEventListener('click', async () => {
        const existing = tr.nextElementSibling;
        if (existing && existing.classList.contains('detailrow')) {
          existing.remove();
          view.textContent = 'View';
          return;
        }
        view.textContent = 'Hide';
        const detailTr = document.createElement('tr');
        detailTr.className = 'detailrow';
        const cell = document.createElement('td');
        cell.colSpan = active.columns.length + 1;
        cell.textContent = 'loading…';
        detailTr.append(cell);
        tr.after(detailTr);
        try {
          const rows = await active.detail(item);
          if (!rows.length) { cell.textContent = 'nothing inside'; return; }
          const table = document.createElement('table');
          table.className = 'detailtable';
          const head = document.createElement('tr');
          for (const k of Object.keys(rows[0])) {
            const th = document.createElement('th');
            th.textContent = k;
            head.append(th);
          }
          table.append(head, ...rows.map((r) => {
            const line = document.createElement('tr');
            for (const v of Object.values(r)) {
              const c = document.createElement('td');
              c.textContent = v ?? '—';
              line.append(c);
            }
            return line;
          }));
          cell.replaceChildren(table);
        } catch (e) {
          cell.textContent = 'could not load: ' + e.message;
        }
      });
      td.append(view);
    }
    if (active.readOnly) {
      tr.append(td);
      return tr;
    }
    if (active.rowAction) {
      // A falsy label means the action doesn't apply to this row.
      const label = active.rowAction.label(item);
      if (label) {
        const act = document.createElement('button');
        act.textContent = label;
        act.className = 'ghost';
        act.addEventListener('click', async () => {
          await active.rowAction.apply(item);
          loadList();
        });
        td.append(act);
      }
    }
    const edit = document.createElement('button');
    edit.textContent = 'Edit';
    edit.className = 'ghost';
    edit.hidden = Boolean(active.noEdit);
    edit.addEventListener('click', () => startEditing(item));
    const del = document.createElement('button');
    del.textContent = 'Delete';
    del.className = 'ghost danger';
    del.hidden = Boolean(active.noDelete);
    del.addEventListener('click', async () => {
      if (!confirm(`Delete "${item.name || item.id}"?`)) return;
      await authFetch(`${active.base || API_BASE}/${active.path}/${item.id}`, { method: 'DELETE' });
      loadList();
    });
    td.append(edit, del);
    tr.append(td);
    return tr;
  }));

  const page = Math.floor(offset / PAGE_SIZE) + 1;
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  el('page-label').textContent = `page ${page} of ${pages}`;
  el('prev').disabled = offset === 0;
  el('next').disabled = offset + PAGE_SIZE >= total;
}

async function save(event) {
  event.preventDefault();
  let body = {};
  try {
    for (const f of active.fields) {
      const value = controls[f.name].get();
      if (value !== undefined) body[f.name] = value;
    }
    if (active.assemble) body = active.assemble(body);
  } catch (e) {
    el('editor-error').textContent = e.message;
    el('editor-error').hidden = false;
    return;
  }
  const url = editingId
    ? `${active.base || API_BASE}/${active.path}/${editingId}`
    : `${active.base || API_BASE}/${active.path}`;
  const res = await authFetch(url, {
    method: editingId ? 'PATCH' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    el('editor-error').textContent = problem.message || `HTTP ${res.status}`;
    el('editor-error').hidden = false;
    return;
  }
  stopEditing();
  loadList();
}

async function main() {
  const ready = await ensureSignedIn().catch((e) => {
    el('signin').hidden = false;
    el('signin').firstElementChild.textContent = 'Sign-in failed: ' + e.message;
    return false;
  });
  if (!ready) {
    el('signin').hidden = false;
    return;
  }
  el('main').hidden = false;
  computeVisible();
  if (!visible.length) {
    el('tabs').textContent = 'Your account has no back-office areas — ask an admin for a role.';
    return;
  }
  const savedTab = sessionStorage.getItem('bss.console.tab');
  active = visible.find((r) => r.path === savedTab) || visible[0];
  el('username').textContent = tokenClaims().preferred_username || '';
  el('logout').hidden = false;
  el('logout').addEventListener('click', signOut);
  el('editor').addEventListener('submit', save);
  el('cancel-edit').addEventListener('click', stopEditing);
  el('prev').addEventListener('click', () => { offset = Math.max(0, offset - PAGE_SIZE); loadList(); });
  el('next').addEventListener('click', () => { offset += PAGE_SIZE; loadList(); });
  renderTabs();
  loadList();
}

main();
