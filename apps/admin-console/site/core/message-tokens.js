/* Personalization tokens in a message: chips and the {{-autocomplete. */
'use strict';

/* Personalization tokens a message can carry — offered as chips and as a
 * {{-autocomplete, so a marketer inserts "First name" without knowing the
 * {{party.firstName}} syntax. Resolved per-customer at send time. */
const MSG_TOKENS = [
  { token: 'party.firstName', label: 'First name' },
  { token: 'party.lastName', label: 'Last name' },
  { token: 'brand.name', label: 'Brand' },
  { token: 'promotion.code', label: 'Promo code' },
  { token: 'order.id', label: 'Order number' },
  { token: 'tracking.url', label: 'Tracking link' },
  { token: 'tracking.carrier', label: 'Carrier' },
  { token: 'usage.remaining', label: 'Data remaining (GB)' },
  { token: 'usage.percentUsed', label: 'Percent used' },
  { token: 'slice.profile', label: 'Network priority profile' },
  { token: 'slice.until', label: 'Priority ends at' },
  { token: 'slice.pass', label: 'Boost pass name' },
  { token: 'organization.name', label: 'Company (B2B)' },
];

function insertAtCaret(el, text, replaceFrom) {
  const end = el.selectionEnd ?? el.value.length;
  const start = replaceFrom != null ? replaceFrom : (el.selectionStart ?? end);
  el.value = el.value.slice(0, start) + text + el.value.slice(end);
  const pos = start + text.length;
  el.setSelectionRange(pos, pos);
  el.dispatchEvent(new Event('input', { bubbles: true })); // drives serialize()
  el.focus();
}

function attachTokenAutocomplete(el) {
  if (el._tok) return; el._tok = true;
  let dd = null;
  const close = () => { if (dd) { dd.remove(); dd = null; } };
  const check = () => {
    const pos = el.selectionStart ?? el.value.length;
    const before = el.value.slice(0, pos);
    const m = before.match(/\{\{\s*([\w.]*)$/); // an open {{ (optionally a partial), not yet closed
    if (!m) { close(); return; }
    const q = m[1].toLowerCase();
    const matches = MSG_TOKENS.filter((t) => t.token.toLowerCase().includes(q) || t.label.toLowerCase().includes(q));
    close();
    if (!matches.length) return;
    dd = document.createElement('div'); dd.className = 'tokendrop'; dd.dataset.testid = 'token-drop';
    const r = el.getBoundingClientRect();
    dd.style.left = (r.left + window.scrollX) + 'px';
    dd.style.top = (r.bottom + window.scrollY + 3) + 'px';
    dd.style.minWidth = Math.max(180, r.width) + 'px';
    matches.forEach((t) => {
      const it = document.createElement('div'); it.className = 'tokenitem'; it.dataset.testid = 'tokenopt-' + t.token;
      const code = document.createElement('code'); code.textContent = '{{' + t.token + '}}';
      const lbl = document.createElement('span'); lbl.textContent = t.label;
      it.append(code, lbl);
      it.addEventListener('mousedown', (ev) => {
        ev.preventDefault();
        const start = before.lastIndexOf('{{');
        insertAtCaret(el, '{{' + t.token + '}}', start);
        close();
      });
      dd.append(it);
    });
    document.body.append(dd);
  };
  el.addEventListener('input', check);
  el.addEventListener('click', check);
  el.addEventListener('keyup', (e) => { if (e.key === 'Escape') close(); });
  el.addEventListener('blur', () => setTimeout(close, 150));
}

function tokenChips(el) {
  attachTokenAutocomplete(el);
  const strip = document.createElement('div'); strip.className = 'tokenchips';
  const lbl = document.createElement('span'); lbl.className = 'tokenlbl'; lbl.textContent = 'Insert:';
  strip.append(lbl);
  MSG_TOKENS.forEach((t) => {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'tokenchip';
    b.textContent = t.label; b.dataset.testid = 'token-' + t.token; b.title = '{{' + t.token + '}}';
    b.addEventListener('mousedown', (ev) => { ev.preventDefault(); insertAtCaret(el, '{{' + t.token + '}}'); });
    strip.append(b);
  });
  return strip;
}
