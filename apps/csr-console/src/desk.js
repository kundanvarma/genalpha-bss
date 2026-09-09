/* DESK LEARNING (CSR): what an agent DOES — a tab, an empty search — never what
 * they see, never the customer. Batched to insight; a tenant with desk-learning
 * off answers enabled:false and the desk goes quiet. Fail-soft by design. */
import { authFetch } from './auth.js';

const state = { queue: [], on: true, session: null };
function session() {
  if (!state.session) {
    let v = sessionStorage.getItem('bss.desk.session');
    if (!v) { v = Math.random().toString(36).slice(2, 12); sessionStorage.setItem('bss.desk.session', v); }
    state.session = v;
  }
  return state.session;
}
export function desk(event, target, props) {
  if (!state.on) return;
  state.queue.push({ desk: 'csr', event, target: target || null, props: props || null, session: session() });
  if (state.queue.length >= 20) flush();
}
export async function flush() {
  if (!state.on || !state.queue.length) return;
  const batch = state.queue.splice(0, 50);
  try {
    const r = await authFetch('/insight/v1/desk/event', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(batch), keepalive: true });
    if (r.status === 403 || r.status === 404) { state.on = false; return; }
    if (r.ok) { const j = await r.json().catch(() => null); if (j && j.enabled === false) state.on = false; }
  } catch { /* never break the desk */ }
}
if (typeof window !== 'undefined') { setInterval(flush, 5000); window.addEventListener('pagehide', flush); }
