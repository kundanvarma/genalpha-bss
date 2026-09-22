/* The table: the sortable header row and one row per item (view, canvas, open, the ⋯ menu). */
'use strict';

function listHeadRow() {
  const tr = document.createElement('tr');
  for (const c of active.columns) {
    const th = document.createElement('th');
    th.textContent = (COLUMN_LABELS[c] || c)
      + (listSortCol === c ? (listSortDir === 1 ? ' ↑' : ' ↓') : '');
    th.style.cursor = 'pointer';
    th.title = 'Sort by ' + (COLUMN_LABELS[c] || c);
    th.addEventListener('click', () => {
      listSortDir = listSortCol === c ? -listSortDir : 1;
      listSortCol = c;
      loadList();
    });
    tr.append(th);
  }
  tr.append(document.createElement('th'));
  return tr;
}

function listRowFor(item) {
  const tr = document.createElement('tr');
  if (item && item.id != null) tr.dataset.id = String(item.id);
  for (const c of active.columns) {
    const td = document.createElement('td');
    if (item[c] === undefined && active.augmentRow) {
      td.textContent = '…';
      active.augmentRow(item, td, c).catch(() => { td.textContent = '—'; });
    } else {
      // a column that is a select field on this page shows the option's words, not its key
      const selectField = (active.fields || []).find((f) => f.name === c && f.kind === 'select' && Array.isArray(f.options));
      const optionLabel = selectField ? (selectField.options.find((o) => (typeof o === 'object' ? o.value : o) === item[c]) || {}).label : undefined;
      const raw = c === 'triggerEventType' ? (EVENT_LABELS[item[c]] || item[c])
        : (c === 'analyticsConsent' || c === 'personalizationConsent') ? (item[c] ? 'Granted' : 'Declined')
        : optionLabel !== undefined ? optionLabel
        : item[c];
      const text = fmtCell(raw);
      // Long machine values (JSON-logic conditions) get truncated with the
      // full value on hover, so the table never explodes.
      td.textContent = text.length > 64 ? text.slice(0, 61) + '…' : text;
      if (text.length > 64) td.title = text;
      // an operator should never read a UUID: name-less party refs resolve
      // to the person's (or org's) name, cached, fail-soft to the id stub
      const nameless = Array.isArray(raw)
        ? (raw.length && raw.every((v) => v && v.id && !v.name) ? raw : null)
        : (raw && typeof raw === 'object' && raw.id && !raw.name && !raw.value ? [raw] : null);
      if (nameless) {
        Promise.all(nameless.map((v) => partyName(v.id)))
          .then((names) => { td.textContent = names.join(', '); })
          .catch(() => {});
      }
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
  if (active.canvas) {
    // a visual node-flow of the journey, with live per-node counts
    const canvasBtn = document.createElement('button');
    canvasBtn.textContent = 'Canvas';
    canvasBtn.className = 'ghost';
    canvasBtn.dataset.testid = 'row-canvas';
    canvasBtn.addEventListener('click', async () => {
      const existing = tr.nextElementSibling;
      if (existing && existing.classList.contains('canvasrow')) {
        existing.remove();
        canvasBtn.textContent = 'Canvas';
        return;
      }
      canvasBtn.textContent = 'Hide';
      const cr = document.createElement('tr');
      cr.className = 'canvasrow';
      const cell = document.createElement('td');
      cell.colSpan = active.columns.length + 1;
      cell.textContent = 'loading…';
      cr.append(cell);
      tr.after(cr);
      try {
        cell.replaceChildren(await active.canvas(item));
      } catch (e) {
        cell.textContent = 'could not load: ' + e.message;
      }
    });
    td.append(canvasBtn);
  }
  if (active.readOnly) {
    // A read-only tab can still opt into Delete (e.g. Saved audiences —
    // authored elsewhere, but prune-able so the list doesn't clutter).
    if (active.allowDelete) {
      const del = document.createElement('button');
      del.textContent = 'Delete';
      del.className = 'ghost danger';
      del.addEventListener('click', async () => {
        if (!confirm(`Delete "${item.name || item.id}"?`)) return;
        await authFetch(`${active.base || API_BASE}/${active.path}/${item.id}`, { method: 'DELETE' });
        loadList();
      });
      td.append(del);
    }
    tr.append(td);
    return tr;
  }
  // One verb per row — Open (the editor) — and everything else behind "⋯":
  // the lifecycle step if the row has one, Edit (the same door as Open),
  // Delete only while the row is still a draft. A row whose resource has
  // no lifecycle keeps Delete; one that carries a lifecycle loses it the
  // moment the thing is live (retire it instead — the ladder is the door).
  const menuItems = [];
  if (active.rowAction) {
    // A falsy (or '—') label means the action doesn't apply to this row.
    const label = active.rowAction.label(item);
    if (label && label !== '—') {
      menuItems.push({ label, run: async () => { await active.rowAction.apply(item); loadList(); } });
    }
  }
  if (!active.noEdit) {
    const open = document.createElement('button');
    open.textContent = 'Open';
    open.className = 'ghost open';
    open.dataset.testid = 'row-open';
    open.addEventListener('click', () => startEditing(item));
    td.append(open);
    menuItems.push({ label: 'Edit', run: () => startEditing(item) });
  }
  const hasLifecycle = Object.prototype.hasOwnProperty.call(item, 'lifecycleStatus')
    || (active.fields || []).some((f) => f.name === 'lifecycleStatus');
  const isDraft = !hasLifecycle || item.lifecycleStatus == null
    || ['In study', 'In design', 'In test', 'Draft', 'draft'].includes(item.lifecycleStatus);
  if (!active.noDelete && isDraft) {
    menuItems.push({ label: 'Delete', danger: true, run: async () => {
      if (!confirm(`Delete "${item.name || item.id}"?`)) return;
      await authFetch(`${active.base || API_BASE}/${active.path}/${item.id}`, { method: 'DELETE' });
      loadList();
    } });
  }
  if (menuItems.length) {
    const more = document.createElement('button');
    more.type = 'button'; more.textContent = '⋯'; more.className = 'ghost more';
    more.setAttribute('aria-label', 'More actions'); more.setAttribute('aria-haspopup', 'menu'); more.setAttribute('aria-expanded', 'false');
    more.dataset.testid = 'row-more';
    const menu = document.createElement('div'); menu.className = 'rowmenu'; menu.setAttribute('role', 'menu'); menu.hidden = true;
    for (const it of menuItems) {
      const b = document.createElement('button'); b.type = 'button'; b.setAttribute('role', 'menuitem');
      b.textContent = it.label; b.className = it.danger ? 'danger' : '';
      b.addEventListener('click', async (e) => { e.stopPropagation(); closeRowMenus(); await it.run(); });
      menu.append(b);
    }
    more.addEventListener('click', (e) => {
      e.stopPropagation();
      const wasOpen = !menu.hidden;
      closeRowMenus();
      if (wasOpen) return;
      menu.hidden = false; more.setAttribute('aria-expanded', 'true');
      placeRowMenu(more, menu);
      menu.querySelector('button')?.focus();
    });
    td.append(more, menu);
  }
  tr.append(td);
  return tr;
}
