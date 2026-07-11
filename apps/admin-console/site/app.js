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
    path: 'audit',
    base: '/ai/v1',
    title: 'AI Audit',
    readOnly: true,
    fields: [],
    columns: ['createdAt', 'useCase', 'provider', 'model', 'prompt', 'response'],
  },
];

const el = (id) => document.getElementById(id);
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
  el('tabs').replaceChildren(...RESOURCES.map((r) => {
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
  // API) must survive an edit-save round trip untouched.
  let passthrough = [];
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
        ? [...picked.map((o) => refObject(field, o)), ...passthrough]
        : refObject(field, picked[0]);
    },
    set: (item) => {
      const refs = multiple ? (item[field.name] || []) : [item[field.name]].filter(Boolean);
      passthrough = multiple ? refs.filter((r) => !r.id || r.options) : [];
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
    return wrap;
  }));
  if (active.aiAssist) {
    el('fields').append(aiAssistRow(active.aiAssist));
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
  renderEditor();
  const res = await authFetch(`${active.base || API_BASE}/${active.path}?offset=${offset}&limit=${PAGE_SIZE}`);
  const items = await res.json();
  const total = Number(res.headers.get('X-Total-Count') || items.length);

  el('total').textContent = `${total} total`;
  el('listing-head').replaceChildren((() => {
    const tr = document.createElement('tr');
    for (const c of active.columns) {
      const th = document.createElement('th');
      th.textContent = c;
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
        td.textContent = fmtCell(item[c]);
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
      const act = document.createElement('button');
      act.textContent = active.rowAction.label(item);
      act.className = 'ghost';
      act.addEventListener('click', async () => {
        await active.rowAction.apply(item);
        loadList();
      });
      td.append(act);
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
