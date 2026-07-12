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
      { name: 'isBundle', label: 'Is a bundle', kind: 'checkbox' },
      { name: 'bundledProductOffering', label: 'Bundled offerings', kind: 'reflist', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'productOfferingPrice', label: 'Prices', kind: 'reflist', resource: 'productOfferingPrice', referredType: 'ProductOfferingPrice' },
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
        { value: 'price-advanced', label: 'Price: advanced — raw JSON-logic condition' },
      ] },
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
      ], showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-advanced'] } },
      { name: 'adjustmentValue', label: 'Adjustment value — negative = discount, positive = surcharge', kind: 'number',
        showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-advanced'] } },
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
      let condition = body.condition;
      switch (kind) {
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
        default:
          break;
      }
      return {
        name: body.name,
        description: body.description,
        domain: isPricing ? 'pricing' : 'order',
        effect: isPricing ? 'adjust' : 'deny',
        priority: body.priority,
        enabled: body.enabled,
        condition,
        message: body.message,
        adjustmentType: isPricing ? (body.adjustmentType || 'percent') : undefined,
        adjustmentValue: isPricing ? body.adjustmentValue : undefined,
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
  numberPortingOrder: 'porting:write',
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
    b.addEventListener('click', () => { active = r; offset = 0; stopEditing(); renderTabs(); loadList(); });
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
  active = visible[0];
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
