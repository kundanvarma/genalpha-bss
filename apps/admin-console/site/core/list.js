/* loadList — the one engine every page goes through — plus save and the desk-learning submit hook. */
'use strict';

async function loadList() {
  const current = active;   // guard: a slow fetch must not paint over a tab switched mid-flight
  el('resource-title').textContent = active.title;
  renderCrumb(active);
  renderPageRow(active);
  helpButtonFor(active);
  renderIntro(active);
  renderKnowledgeGaps(active);
  newButtonFor(active);
  closeDrawer();
  if (typeof closeSideDrawer === 'function') closeSideDrawer(); // the reading drawer never outlives its page
  renderKpis(active);
  document.getElementById('staff-panel')?.setAttribute('hidden', '');
  document.getElementById('copilot-panel')?.setAttribute('hidden', '');
  document.getElementById('workforce-panel')?.setAttribute('hidden', '');
  document.getElementById('reporting-panel')?.setAttribute('hidden', '');
  document.getElementById('integrations-panel')?.setAttribute('hidden', '');
  document.getElementById('wholesale-panel')?.setAttribute('hidden', '');
  document.getElementById('pipeline-panel')?.setAttribute('hidden', '');
  document.getElementById('home-panel')?.setAttribute('hidden', '');
  document.querySelector('.table-wrap')?.removeAttribute('hidden');   // generic tabs show the table again
  document.querySelector('.pager')?.removeAttribute('hidden');
  if (renderCustomPane()) return;
  renderEditor();
  const q = active.serverSearch && listFilter ? `&q=${encodeURIComponent(listFilter)}` : '';
  const res = await authFetch(`${active.base || API_BASE}/${active.path}?offset=${offset}&limit=${PAGE_SIZE}${q}`);
  if (active !== current) return;   // the user switched tabs while this was loading — drop the stale paint
  if (!res.ok) {
    // say so, in words — never "NaN total" and an empty table that looks like "nothing here"
    el('total').textContent = '';
    el('listing-head').replaceChildren();
    const tr = document.createElement('tr'); const td = document.createElement('td'); td.colSpan = Math.max(1, (active.columns || []).length);
    td.className = 'dim'; td.dataset.testid = 'list-unavailable';
    td.textContent = res.status === 403 ? 'You do not have the role to see this page.'
      : res.status >= 500 || res.status === 404 ? 'This page could not be loaded — the component behind it did not answer. Try again in a moment; if it stays like this, the component is down.'
      : `This page could not be loaded (${res.status}).`;
    tr.append(td); el('listing-body').replaceChildren(tr);
    document.querySelector('.pager')?.setAttribute('hidden', '');
    return;
  }
  const items = await res.json();
  lastListItems = items; // the page's rows, for chips that read what was just loaded
  if (active.path === 'findings') renderKpis(active); // chips read these rows
  const total = Number(res.headers.get('X-Total-Count') || items.length);

  // #200: search filters + header sorting over the loaded page (honest hint —
  // the fetch is paged, so the box narrows THIS page, not the whole history).
  let search = document.getElementById('list-search');
  if (!search) {
    search = document.createElement('input');
    search.id = 'list-search';
    search.style.cssText = 'margin-left:0.8rem;padding:0.25rem 0.5rem;font-size:0.85rem;width:14rem';
    el('total').after(search);
    // serverSearch tabs debounce and re-query from page 1; page-filter tabs are instant + local.
    let t = null;
    search.addEventListener('input', () => {
      listFilter = search.value.trim().toLowerCase();
      if (active.serverSearch) {
        clearTimeout(t);
        t = setTimeout(() => { offset = 0; loadList(); }, 250);
      } else {
        loadList();
      }
    });
  }
  search.removeAttribute('hidden');
  search.placeholder = active.serverSearch ? 'Search all visitors by id…' : 'Filter this page…';
  search.value = listFilter;
  const sortVal = (it, c) => {
    const v = it[c];
    if (v == null) return '';
    const t = typeof v === 'object' ? fmtCell(v) : String(v);
    const n = Number(t);
    return Number.isFinite(n) && t !== '' ? n : t.toLowerCase();
  };
  // serverSearch tabs already filtered on the server — don't re-filter locally.
  let shown = (!listFilter || active.serverSearch) ? items : items.filter((it) =>
    active.columns.some((c) => fmtCell(it[c] === undefined ? '' : it[c]).toLowerCase().includes(listFilter)));
  if (listSortCol && active.columns.includes(listSortCol)) {
    shown = [...shown].sort((a, b) => {
      const x = sortVal(a, listSortCol); const y = sortVal(b, listSortCol);
      return (x < y ? -1 : x > y ? 1 : 0) * listSortDir;
    });
  }
  el('total').textContent = listFilter ? `${shown.length} of ${total} total` : `${total} total`;
  if (listFilter && shown.length === 0) desk('search.empty', active.path, { query: listFilter });
  el('listing-head').replaceChildren(listHeadRow());

  el('listing-body').replaceChildren(...(Array.isArray(shown) ? shown : []).map((item) => listRowFor(item)));

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

// the submit hook: the form's VALUES SHAPE (short fields kept, free text as presence),
// the copilot draft's survival, and — for a new journey — its id so a holdout can be suggested
function deskOnSubmit() {
  if (!active) return;
  const fd = new FormData(el('editor'));
  const values = {};
  for (const [k, v] of fd.entries()) { if (typeof v === 'string') values[k] = v.length > 80 ? v.slice(0, 80) : v; }
  const creating = !editingId;
  const props = { values, mode: creating ? 'create' : 'edit' };
  if (DESK.lastDraft && DESK.lastDraft.form === active.path) {
    props.editRatio = Number(deskEditRatio(DESK.lastDraft.steps, values.steps).toFixed(2));
    desk('copilot.draft', active.path, { editRatio: props.editRatio });
    DESK.lastDraft = null;
  }
  if (DESK.started) DESK.started.submitted = true;
  const name = values.name;
  const form = active.path;
  if (creating && form === 'journeys' && name) {
    // resolve the created id after the save lands, so the suggestion can act on it
    setTimeout(async () => {
      try {
        const r = await authFetch('/tmf-api/campaignManagement/v4/journey?limit=100');
        const list = r.ok ? await r.json() : [];
        const hit = (Array.isArray(list) ? list : []).find((j) => j.name === name);
        desk('form.submit', form, { ...props, createdId: hit ? hit.id : null, createdName: name });
      } catch { desk('form.submit', form, props); }
    }, 1500);
  } else {
    desk('form.submit', form, props);
  }
}
