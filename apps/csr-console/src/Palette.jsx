import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { hasRole } from './auth.js';
import { desk } from './desk.js';

/* Keyboard for the desk. Single keys when the caret is not in a field:
 *   /  search customers      t  ticket queue      c  chats      k  knowledge
 *   n  note on this customer / this ticket        r  resolve the ticket in front of you
 *   ?  this sheet
 * Anywhere: ⌘K (Ctrl+K) opens the command palette; ⌘↵ submits the field you are in.
 * The palette lists pages, the customers opened last, and every action visible
 * on the current page — typed, not clicked. Nothing here is a new capability;
 * it is the same buttons, reachable without the mouse. */

const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform);
export const MOD = isMac ? '⌘' : 'Ctrl';

const SHEET = [
  ['/', 'Search customers'], ['t', 'Ticket queue'], ['c', 'Chats'], ['k', 'Knowledge'],
  ['n', 'Note on this customer or ticket'], ['r', 'Resolve the ticket in front of you'],
  [`${MOD} K`, 'Command palette'], [`${MOD} ↵`, 'Send / submit the field you are in'], ['?', 'This sheet'],
];

const typing = (el) => !!el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable);
const visible = (el) => !!el && el.offsetParent !== null && !el.disabled;
const focusLater = (selector) => setTimeout(() => { const el = document.querySelector(selector); if (el) { el.focus(); el.select?.(); } }, 150);
const buttonWithText = (text) => [...document.querySelectorAll('main button')].find((b) => visible(b) && b.textContent.trim() === text);

export default function Palette() {
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [cursor, setCursor] = useState(0);
  const [commands, setCommands] = useState([]);
  const inputRef = useRef(null);

  // what the palette can do right now: pages, recent customers, the actions on this page
  const collect = () => {
    const out = [];
    const page = (label, to) => out.push({ group: 'Go to', label, run: () => navigate(to) });
    page('Customers', '/'); page('Ticket queue', '/tickets'); page('Chats', '/chats'); page('Knowledge', '/knowledge');
    if (hasRole('stock:read')) page('Stock', '/stock');
    if (hasRole('device:read')) page('Devices', '/devices');
    if (hasRole('migration:read')) page('Migrations', '/migrations');
    if (hasRole('billing:read')) page('Collections', '/collections');
    if (hasRole('party:write')) page('Registry', '/registry');
    try {
      for (const r of JSON.parse(localStorage.getItem('bss.csr.recent') || '[]')) {
        out.push({ group: 'Recent customer', label: r.name || r.id.slice(0, 8), run: () => navigate(`/customer/${r.id}`) });
      }
    } catch { /* no storage */ }
    const seen = new Set();
    for (const b of document.querySelectorAll('main button, main a.linkbtn')) {
      const text = b.textContent.trim().replace(/\s+/g, ' ');
      if (!visible(b) || text.length < 2 || text.length > 48 || seen.has(text)) continue;
      seen.add(text);
      out.push({ group: 'On this page', label: text, run: () => b.click() });
    }
    return out;
  };

  const openPalette = () => { setCommands(collect()); setQ(''); setCursor(0); setOpen(true); desk('palette.open', location.pathname.split('/')[1] || 'customers'); setTimeout(() => inputRef.current?.focus(), 30); };

  useEffect(() => {
    const onKey = (e) => {
      const mod = isMac ? e.metaKey : e.ctrlKey;
      if (mod && e.key.toLowerCase() === 'k') { e.preventDefault(); open ? setOpen(false) : openPalette(); return; }
      if (open) { if (e.key === 'Escape') { e.preventDefault(); setOpen(false); } return; }
      if (typing(e.target)) {
        if (mod && e.key === 'Enter' && e.target.form) { e.preventDefault(); e.target.form.requestSubmit ? e.target.form.requestSubmit() : e.target.form.submit(); }
        if (e.key === 'Escape') e.target.blur();
        return;
      }
      if (e.altKey || mod) return;
      switch (e.key) {
        case '/': e.preventDefault(); navigate('/'); focusLater('[data-testid="cust-search"]'); desk('shortcut', '/'); break;
        case 't': navigate('/tickets'); desk('shortcut', 't'); break;
        case 'c': navigate('/chats'); desk('shortcut', 'c'); break;
        case 'k': navigate('/knowledge'); focusLater('[data-testid="kb-search"]'); desk('shortcut', 'k'); break;
        case 'n': { const el = document.querySelector('input[name="newInteraction"]') || document.querySelector('[data-testid="ticket-detail"] input[name="ticketNote"]') || document.querySelector('input[name="ticketNote"]'); if (el) { e.preventDefault(); el.focus(); desk('shortcut', 'n'); } break; }
        case 'r': { const b = buttonWithText('→ resolved'); if (b) { e.preventDefault(); b.click(); desk('shortcut', 'r'); } break; }
        case '?': e.preventDefault(); openPalette(); break;
        default: break;
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, location.pathname]);

  if (!open) return null;
  const hits = commands.filter((c) => !q.trim() || (c.group + ' ' + c.label).toLowerCase().includes(q.trim().toLowerCase())).slice(0, 12);
  const run = (c) => { setOpen(false); desk('palette.run', c.group + ': ' + c.label); c.run(); };
  return (
    <div className="palette-overlay" onMouseDown={() => setOpen(false)} data-testid="palette">
      <div className="palette" role="dialog" aria-label="Command palette" onMouseDown={(e) => e.stopPropagation()}>
        <input ref={inputRef} autoFocus data-testid="palette-input" aria-label="Type a page, a customer or an action" placeholder="Type a page, a customer or an action…"
          value={q} onChange={(e) => { setQ(e.target.value); setCursor(0); }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, hits.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            else if (e.key === 'Enter' && hits[cursor]) { e.preventDefault(); run(hits[cursor]); }
          }} />
        <ul className="palette-list" role="listbox" aria-label="Commands">
          {hits.map((c, i) => (
            <li key={c.group + c.label} role="option" aria-selected={i === cursor} className={`palette-item${i === cursor ? ' on' : ''}`}
                data-testid="palette-item" onMouseEnter={() => setCursor(i)} onClick={() => run(c)}>
              <span className="dim small">{c.group}</span><span>{c.label}</span>
            </li>
          ))}
          {!hits.length && <li className="dim small palette-item">Nothing matches.</li>}
        </ul>
        <div className="palette-keys" data-testid="shortcut-sheet">
          {SHEET.map(([k, what]) => <span key={k}><kbd>{k}</kbd> {what}</span>)}
        </div>
      </div>
    </div>
  );
}
