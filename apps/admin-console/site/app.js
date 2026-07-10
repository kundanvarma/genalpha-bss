/*
 * Catalog console: list / create / edit / delete over the TMF620 API through
 * the gateway (same origin — no CORS). Resources are configuration, so
 * extending the console is a data change, not new code.
 *
 * Field kinds: text (default), number, checkbox, money ({unit, value}),
 * ref (single entity reference), reflist (array of entity references).
 * Refs load their pick-lists from the API when the editor renders.
 */
'use strict';

const API_BASE = '/tmf-api/productCatalogManagement/v4';
const STOCK_BASE = '/tmf-api/productStockManagement/v4';
const BILLING_BASE = '/tmf-api/customerBillManagement/v4';
const QUALIFICATION_BASE = '/tmf-api/productOfferingQualification/v4';
const APPOINTMENT_BASE = '/tmf-api/appointment/v4';
const PAGE_SIZE = 10;
const REF_PICKLIST_LIMIT = 100;

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
      textControl(f, f.kind === 'number' ? 'number' : 'text');
    wrap.append(caption, ...parts);
    return wrap;
  }));
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
      td.textContent = fmtCell(item[c]);
      tr.append(td);
    }
    const td = document.createElement('td');
    td.className = 'rowactions';
    if (active.readOnly) {
      tr.append(td);
      return tr;
    }
    const edit = document.createElement('button');
    edit.textContent = 'Edit';
    edit.className = 'ghost';
    edit.hidden = Boolean(active.noEdit);
    edit.addEventListener('click', () => startEditing(item));
    const del = document.createElement('button');
    del.textContent = 'Delete';
    del.className = 'ghost danger';
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
  const body = {};
  try {
    for (const f of active.fields) {
      const value = controls[f.name].get();
      if (value !== undefined) body[f.name] = value;
    }
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
