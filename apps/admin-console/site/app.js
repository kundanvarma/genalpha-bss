/*
 * Catalog console: list / create / edit / delete over the TMF620 API through
 * the gateway (same origin — no CORS). Resources are configuration, so
 * extending the console is a data change, not new code.
 */
'use strict';

const API_BASE = '/tmf-api/productCatalogManagement/v4';
const PAGE_SIZE = 10;

const RESOURCES = [
  {
    path: 'productOffering',
    title: 'Product Offerings',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'description', label: 'Description' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
      { name: 'version', label: 'Version' },
    ],
    columns: ['name', 'lifecycleStatus', 'version', 'lastUpdate'],
  },
  {
    path: 'productSpecification',
    title: 'Product Specifications',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'brand', label: 'Brand' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
    ],
    columns: ['name', 'brand', 'lifecycleStatus', 'lastUpdate'],
  },
  {
    path: 'productOfferingPrice',
    title: 'Product Offering Prices',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'priceType', label: 'Price type', placeholder: 'recurring' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active' },
      { name: 'version', label: 'Version' },
    ],
    columns: ['name', 'priceType', 'lifecycleStatus', 'lastUpdate'],
  },
];

const el = (id) => document.getElementById(id);
let active = RESOURCES[0];
let offset = 0;
let editingId = null;

function fmtCell(value) {
  if (value == null) return '—';
  if (/^\d{4}-\d{2}-\d{2}T/.test(String(value))) {
    return String(value).slice(0, 19).replace('T', ' ');
  }
  return String(value);
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

function renderEditor() {
  el('fields').replaceChildren(...active.fields.map((f) => {
    const wrap = document.createElement('label');
    wrap.className = 'field';
    const caption = document.createElement('span');
    caption.textContent = f.label + (f.required ? ' *' : '');
    const input = document.createElement('input');
    input.name = f.name;
    input.placeholder = f.placeholder || '';
    input.required = Boolean(f.required);
    wrap.append(caption, input);
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
}

function startEditing(item) {
  editingId = item.id;
  el('editor-title').textContent = 'Edit ' + (item.name || item.id);
  el('save').textContent = 'Save changes';
  el('cancel-edit').hidden = false;
  for (const f of active.fields) {
    el('editor').elements[f.name].value = item[f.name] || '';
  }
  el('editor').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function loadList() {
  el('resource-title').textContent = active.title;
  renderEditor();
  const res = await authFetch(`${API_BASE}/${active.path}?offset=${offset}&limit=${PAGE_SIZE}`);
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
    const edit = document.createElement('button');
    edit.textContent = 'Edit';
    edit.className = 'ghost';
    edit.addEventListener('click', () => startEditing(item));
    const del = document.createElement('button');
    del.textContent = 'Delete';
    del.className = 'ghost danger';
    del.addEventListener('click', async () => {
      if (!confirm(`Delete "${item.name || item.id}"?`)) return;
      await authFetch(`${API_BASE}/${active.path}/${item.id}`, { method: 'DELETE' });
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
  for (const f of active.fields) {
    const value = el('editor').elements[f.name].value.trim();
    if (value) body[f.name] = value;
  }
  const url = editingId
    ? `${API_BASE}/${active.path}/${editingId}`
    : `${API_BASE}/${active.path}`;
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
