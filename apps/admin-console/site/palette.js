/* ⌘K — the back office's command palette. Ported from the care desk (Palette.jsx).
 *
 * One overlay, one input: type a page, a recent page or an action visible on the
 * page in front of you, and run it with Enter. Nothing here is a new capability;
 * it is the same rail and the same buttons, reachable without the mouse.
 *
 *   Go to        every page this token can see (visible[]), with its department
 *   Recent       the last eight pages opened on this browser
 *   On this page every enabled, visible button in #main (2–48 characters, deduped)
 *   Ask          "Ask: <what you typed>" hands the question to the ? help drawer
 *
 * Opening a page goes through EXACTLY the tab-click path in app.js (active, offset,
 * listFilter, listSortCol, stopEditing, the saved tab, renderTabs, loadList), so a
 * palette jump and a rail click are indistinguishable to the rest of the console.
 * This file only reads app.js globals; it never edits them except `loadList`,
 * which it wraps once to remember the recent pages. */
(function () {
  'use strict';

  const isMac = /Mac|iPhone|iPad/.test(navigator.platform);
  const MOD = isMac ? '⌘' : 'Ctrl';
  const RECENT_KEY = 'bss.console.recent';
  const MAX_ROWS = 12;
  const MAX_RECENT = 8;

  const g = (name) => { try { return window[name] !== undefined ? window[name] : eval(name); } catch { return undefined; } }; // eslint-disable-line no-eval
  const tell = (event, target, props) => { const d = g('desk'); if (typeof d === 'function') { try { d(event, target, props); } catch { /* telemetry never breaks the desk */ } } };
  const shown = (node) => !!node && node.offsetParent !== null && !node.disabled && node.getAttribute('aria-hidden') !== 'true';

  /* ---- recent pages: read whatever is there (strings or {path,…} objects), write back in the same shape ---- */
  const readRecent = () => {
    try { const raw = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]'); return Array.isArray(raw) ? raw : []; } catch { return []; }
  };
  const recentPaths = () => readRecent().map((r) => (typeof r === 'string' ? r : r && r.path)).filter(Boolean);
  const recordRecent = (path) => {
    if (!path) return;
    const list = readRecent();
    const asObjects = list.length ? typeof list[0] !== 'string' : true;
    const kept = list.filter((r) => (typeof r === 'string' ? r : r && r.path) !== path);
    kept.unshift(asObjects ? { path, at: Date.now() } : path);
    try { localStorage.setItem(RECENT_KEY, JSON.stringify(kept.slice(0, MAX_RECENT))); } catch { /* no storage */ }
  };
  // home.js keeps the same list when the Home page is on; the palette only records it itself when nobody else does
  if (typeof loadList === 'function' && typeof homeRemember !== 'function') {
    const _ll = loadList;
    // eslint-disable-next-line no-global-assign
    loadList = function () { try { const a = g('active'); if (a && a.path) recordRecent(a.path); } catch { /* keep the desk moving */ } return _ll.apply(this, arguments); };
  }

  /* ---- what the palette can do right now ---- */
  const departmentOf = (path) => { const ws = (g('WORKSPACES') || []).find((w) => (w.tabs || []).includes(path)); return ws ? ws.label : ''; };
  const openPage = (r) => {
    const DESK = g('DESK');
    if (DESK && DESK.started && !DESK.started.submitted) tell('form.abandon', DESK.started.form);
    if (DESK) DESK.started = null;
    tell('tab.open', r.path);
    active = r; offset = 0; listFilter = ''; listSortCol = null; stopEditing();
    sessionStorage.setItem('bss.console.tab', r.path); renderTabs(); loadList();
  };
  const collect = () => {
    // Recent first: with sixty pages on the operator's rail, the eight you were just on
    // are what an empty palette should show before the alphabet of everything else
    const out = [];
    const pages = Array.isArray(g('visible')) ? g('visible') : [];
    const current = g('active');
    for (const path of recentPaths()) {
      const r = pages.find((p) => p.path === path);
      if (!r || r === current) continue;
      out.push({ group: 'Recent', label: r.title, hint: departmentOf(r.path), run: () => openPage(r) });
    }
    for (const r of pages) out.push({ group: 'Go to', label: r.title, hint: departmentOf(r.path), run: () => openPage(r) });
    const seen = new Set();
    const main = document.getElementById('main');
    for (const b of main ? main.querySelectorAll('button') : []) {
      if (b.closest('#tabs') || b.closest('.palette')) continue;
      const text = (b.textContent || '').trim().replace(/\s+/g, ' ');
      if (!shown(b) || text.length < 2 || text.length > 48 || seen.has(text)) continue;
      seen.add(text);
      out.push({ group: 'On this page', label: text, run: () => b.click() });
    }
    return out;
  };
  const canAsk = () => {
    if (typeof g('openHelpDrawer') !== 'function') return false;
    try { const claims = typeof tokenClaims === 'function' ? tokenClaims() : {}; return (((claims || {}).realm_access || {}).roles || []).includes('ai:use'); } catch { return false; }
  };
  const askRow = (question) => ({ group: 'Ask', label: `Ask: ${question}`, run: async () => {
    await openHelpDrawer(g('active'));
    const search = document.querySelector('#help-drawer [data-testid="help-search"]');
    if (search) { search.value = question; search.dispatchEvent(new Event('input', { bubbles: true })); }
    const ask = document.querySelector('#help-drawer [data-testid="help-ask"]');
    if (ask) ask.click(); else if (search) search.focus();
  } });

  /* ---- the overlay ---- */
  let backdrop = null, input = null, list = null, status = null;
  let commands = [], hits = [], cursor = 0, lastFocus = null;
  const isOpen = () => !!backdrop;

  const rowId = (i) => `palette-row-${i}`;
  const paint = () => {
    const q = input.value.trim().toLowerCase();
    const matched = q ? commands.filter((c) => `${c.group} ${c.label} ${c.hint || ''}`.toLowerCase().includes(q)) : commands;
    const ask = q && canAsk() ? askRow(input.value.trim()) : null;
    hits = matched.slice(0, ask ? MAX_ROWS - 1 : MAX_ROWS);
    if (ask) hits.push(ask);
    if (cursor >= hits.length) cursor = Math.max(0, hits.length - 1);
    list.replaceChildren();
    hits.forEach((c, i) => {
      const li = document.createElement('li');
      li.id = rowId(i); li.setAttribute('role', 'option'); li.dataset.testid = 'palette-row'; li.dataset.group = c.group;
      if (i === cursor) li.setAttribute('aria-selected', 'true');
      const grp = document.createElement('span'); grp.className = 'palette-group'; grp.textContent = c.group;
      const lbl = document.createElement('span'); lbl.className = 'palette-label'; lbl.textContent = c.label;
      li.append(grp, lbl);
      if (c.hint) { const h = document.createElement('span'); h.className = 'palette-hint-dept'; h.textContent = c.hint; li.append(h); }
      li.addEventListener('mouseenter', () => { if (cursor !== i) { cursor = i; paint(); } });
      li.addEventListener('mousedown', (e) => e.preventDefault()); // keep focus in the input
      li.addEventListener('click', () => run(c));
      list.append(li);
    });
    if (!hits.length) { const li = document.createElement('li'); li.className = 'palette-empty'; li.textContent = 'Nothing matches.'; list.append(li); }
    input.setAttribute('aria-activedescendant', hits.length ? rowId(cursor) : '');
    status.textContent = hits.length ? `${hits.length} result${hits.length === 1 ? '' : 's'}` : 'Nothing matches';
  };

  const close = () => {
    if (!backdrop) return;
    backdrop.remove(); backdrop = null; input = null; list = null; status = null; hits = []; commands = [];
    if (lastFocus && typeof lastFocus.focus === 'function' && document.contains(lastFocus)) lastFocus.focus();
    lastFocus = null;
  };
  const run = (c) => {
    const current = g('active');
    close();
    tell('palette.run', `${c.group}: ${c.label}`, { page: current ? current.path : null });
    try { const p = c.run(); if (p && typeof p.catch === 'function') p.catch(() => {}); } catch { /* a dead button is not the palette's fault */ }
  };

  const openPalette = () => {
    const main = document.getElementById('main');
    if (!main || main.hidden) return;             // nobody is signed in yet
    if (isOpen()) { close(); return; }
    lastFocus = document.activeElement;
    commands = collect(); cursor = 0;
    const current = g('active');
    tell('palette.open', current ? current.path : null);

    backdrop = document.createElement('div'); backdrop.className = 'palette-backdrop'; backdrop.dataset.testid = 'palette';
    backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) close(); });
    const box = document.createElement('div'); box.className = 'palette';
    box.setAttribute('role', 'dialog'); box.setAttribute('aria-modal', 'true'); box.setAttribute('aria-label', 'Command palette');
    input = document.createElement('input');
    input.type = 'text'; input.dataset.testid = 'palette-input'; input.autocomplete = 'off'; input.spellcheck = false;
    input.placeholder = 'Type a page or an action…';
    input.setAttribute('aria-label', 'Type a page or an action'); input.setAttribute('role', 'combobox');
    input.setAttribute('aria-expanded', 'true'); input.setAttribute('aria-controls', 'palette-list'); input.setAttribute('aria-autocomplete', 'list');
    list = document.createElement('ul'); list.id = 'palette-list'; list.className = 'palette-list'; list.setAttribute('role', 'listbox'); list.setAttribute('aria-label', 'Commands');
    status = document.createElement('div'); status.className = 'sr-only'; status.setAttribute('aria-live', 'polite');
    const keys = document.createElement('div'); keys.className = 'palette-keys';
    for (const [k, what] of [['↑↓', 'move'], ['↵', 'run'], ['esc', 'close'], [`${MOD} K`, 'open anywhere']]) {
      const s = document.createElement('span'); const kbd = document.createElement('kbd'); kbd.textContent = k; s.append(kbd, ` ${what}`); keys.append(s);
    }
    input.addEventListener('input', () => { cursor = 0; paint(); });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown') { e.preventDefault(); if (hits.length) { cursor = Math.min(cursor + 1, hits.length - 1); paint(); } }
      else if (e.key === 'ArrowUp') { e.preventDefault(); if (hits.length) { cursor = Math.max(cursor - 1, 0); paint(); } }
      else if (e.key === 'Enter') { e.preventDefault(); if (hits[cursor]) run(hits[cursor]); }
      else if (e.key === 'Tab') { e.preventDefault(); }      // focus stays in the one field
    });
    box.append(input, list, status, keys); backdrop.append(box); document.body.append(backdrop);
    paint();
    input.focus();
  };

  /* ---- keys: ⌘K / Ctrl+K anywhere; Esc closes (captured, so the editor's own Esc stays quiet) ---- */
  window.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && !e.altKey && String(e.key).toLowerCase() === 'k') { e.preventDefault(); e.stopPropagation(); openPalette(); return; }
    if (isOpen()) {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(); return; }
      if (input && document.activeElement !== input && !e.metaKey && !e.ctrlKey) input.focus();
    }
  }, true);
  // if focus slips out (a screen reader, a stray click), bring it back to the field
  document.addEventListener('focusin', (e) => { if (isOpen() && backdrop && !backdrop.contains(e.target)) input.focus(); });

  /* ---- the hint in the header: shows once someone is signed in ---- */
  const mountHint = () => {
    const who = document.querySelector('header .who');
    if (!who || document.getElementById('palette-hint')) return;
    const b = document.createElement('button');
    b.type = 'button'; b.id = 'palette-hint'; b.className = 'ghost palette-hint'; b.dataset.testid = 'palette-hint';
    b.title = `Command palette (${MOD} K)`; b.setAttribute('aria-label', `Open the command palette, ${MOD} K`);
    b.textContent = `${MOD} K`;
    b.addEventListener('click', () => openPalette());
    const logout = document.getElementById('logout');
    if (logout) who.insertBefore(b, logout); else who.append(b);
    const main = document.getElementById('main');
    const sync = () => { b.hidden = !main || main.hidden; };
    sync();
    if (main) new MutationObserver(sync).observe(main, { attributes: true, attributeFilter: ['hidden'] });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mountHint); else mountHint();

  window.consolePalette = { open: openPalette, close };
})();
