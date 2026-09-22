/* The two drawers (form and reading) and the row overflow menu. */
'use strict';

/* ---------------- a second drawer for reading things (receipts, contracts) — the form drawer stays the form's ---------------- */
function sideDrawer() {
  let d = document.getElementById('side-drawer');
  if (!d) {
    d = document.createElement('aside'); d.id = 'side-drawer'; d.className = 'side-drawer'; d.dataset.testid = 'side-drawer';
    document.body.append(d);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeSideDrawer(); });
  }
  return d;
}
function openSideDrawer(title, ...nodes) {
  const d = sideDrawer(); d.replaceChildren();
  const head = document.createElement('div'); head.className = 'side-head';
  const h = document.createElement('h2'); h.textContent = title;
  const close = document.createElement('button'); close.className = 'ghost'; close.textContent = 'Close'; close.dataset.testid = 'side-close';
  close.addEventListener('click', closeSideDrawer);
  head.append(h, close);
  d.append(head, ...nodes);
  d.classList.add('open');
  return d;
}
function closeSideDrawer() { document.getElementById('side-drawer')?.classList.remove('open'); }

/* ---------------- The form as a drawer: off to the right until asked for ----------------
 * Opens on "+ New", on Edit, or the moment any of its fields gets focus (so a script or
 * a keyboard user filling it never finds it closed). Closes on Cancel, Save, Escape, backdrop. */
function openDrawer() {
  const ed = el('editor'); if (!ed || ed.hidden) return;
  ed.classList.add('open');
  let bd = document.getElementById('drawer-backdrop');
  if (!bd) { bd = document.createElement('div'); bd.id = 'drawer-backdrop'; bd.className = 'drawer-backdrop'; bd.addEventListener('click', () => { stopEditing(); closeDrawer(); }); document.body.append(bd); }
  bd.hidden = false;
}
function closeDrawer() {
  el('editor')?.classList.remove('open');
  const bd = document.getElementById('drawer-backdrop'); if (bd) bd.hidden = true;
}
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && el('editor')?.classList.contains('open')) { stopEditing(); closeDrawer(); } });

/* ---------------- The row overflow ("⋯"): one open at a time, gone on outside click / Escape / scroll ---------------- */
function closeRowMenus() {
  document.querySelectorAll('.rowmenu:not([hidden])').forEach((m) => { m.hidden = true; m.previousElementSibling?.setAttribute('aria-expanded', 'false'); });
}
// fixed under (or, near the bottom of the window, above) its button, so the
// scrolling table wrapper never clips it; follows the button when the page scrolls
function placeRowMenu(more, menu) {
  const r = more.getBoundingClientRect();
  const w = menu.offsetWidth || 160, h = menu.offsetHeight || 0;
  const below = r.bottom + 4 + h <= window.innerHeight - 8;
  menu.style.top = `${below ? r.bottom + 4 : Math.max(8, r.top - 4 - h)}px`;
  menu.style.left = `${Math.max(8, r.right - w)}px`;
}
document.addEventListener('click', (e) => { if (!e.target.closest?.('.rowmenu')) closeRowMenus(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeRowMenus(); });
document.addEventListener('scroll', () => {
  document.querySelectorAll('.rowmenu:not([hidden])').forEach((m) => { const more = m.previousElementSibling; if (more) placeRowMenu(more, m); });
}, true);
document.addEventListener('DOMContentLoaded', () => {
  // rows render after their fetch — whenever the table changes, re-apply the KPI flags
  const body = el('listing-body');
  if (body) new MutationObserver(() => applyRowFlags()).observe(body, { childList: true });
  const ed = el('editor'); if (!ed) return;
  ed.addEventListener('focusin', () => { if (!ed.classList.contains('open')) openDrawer(); });
  el('cancel-edit')?.addEventListener('click', closeDrawer);
  el('editor-close')?.addEventListener('click', () => { stopEditing(); closeDrawer(); });
});
