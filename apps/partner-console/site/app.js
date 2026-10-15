/*
 * Wholesale partner portal — the retail ISP's (access seeker's) self-service for
 * open-access fibre: check an address, see the L2/L3 access products, track the
 * access already bought, and what is owed to each owner. Static; talks to the same
 * TMF/Sonata APIs the rest of the fleet exposes. The tenant rides the hostname.
 */
const CAT = '/tmf-api/productCatalogManagement/v4';
const SQ = '/tmf-api/serviceQualificationManagement/v4';
const SO = '/tmf-api/serviceOrdering/v4';

async function json(res) {
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}
const el = (id) => document.getElementById(id);
const eur = (v) => (v == null ? '—' : Number(v).toFixed(2) + ' EUR');

// Build the DOM as NODES, never as markup. This file used to assemble strings and
// assign them to innerHTML, with every value wrapped in an esc() helper. That was
// correct — and correct by vigilance, which is the kind that lapses the day someone
// adds a field and forgets the wrapper. An owner named `<img src=x onerror=…>` now
// cannot be markup, because nothing here parses markup: textContent is text by
// construction. The esc() helper is gone with the last innerHTML that needed it.
const node = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
};
const put = (box, ...kids) => box.replaceChildren(...kids);
const row = (...kids) => { const d = node('div', 'row'); d.append(...kids); return d; };

async function main() {
  // OIDC redirect return
  const params = new URLSearchParams(location.search);
  if (params.get('code')) {
    try { await completeLogin(params.get('code')); } catch (e) { /* fall to gate */ }
    history.replaceState({}, '', location.pathname);
  }
  const token = currentToken();
  if (!token) {
    el('gate').style.display = 'block';
    el('signin').onclick = () => beginLogin();
    return;
  }
  el('gate').style.display = 'none';
  el('app').style.display = 'block';
  el('signout').style.display = 'inline-block';
  el('signout').onclick = () => signOut();
  try { el('username').textContent = claims().preferred_username || claims().email || ''; } catch {}

  el('check').onclick = checkAddress;
  el('pc').addEventListener('keydown', (e) => { if (e.key === 'Enter') checkAddress(); });
  loadCatalog();
  loadOrders();
  loadSettlement();
}

function claims() {
  const t = currentToken();
  return JSON.parse(atob(t.split('.')[1]));
}

// --- serviceability: which owners serve this address, at what layer ---
async function checkAddress() {
  const pc = el('pc').value.trim();
  const box = el('options');
  if (!pc) { put(box, node('div', 'dim', 'Enter a postcode.')); return; }
  put(box, node('div', 'dim', 'Checking…'));
  try {
    const r = await fetch(`${SQ}/queryAccessOptions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ searchCriteria: { place: { postCode: pc }, technology: 'fiber' } }),
    });
    const d = await json(r);
    const opts = d.accessOption || [];
    if (!opts.length) {
      put(box, node('div', 'dim', `No open-access owner serves ${pc} — this would be our own network.`));
      return;
    }
    put(box, ...opts.map((o) => row(
      node('span', 'code', o.accessOwner),
      node('span', 'layer', o.accessLayer || ''),
      node('span', 'dim', 'fibre'),
      node('span', 'end', `up to ${o.maxDownMbps} Mbit/s`))));
  } catch (e) {
    put(box, node('div', 'err', `Could not check: ${e.message}`));
  }
}

// --- the L2/L3 access products ---
async function loadCatalog() {
  const box = el('catalog');
  try {
    const offs = await json(await fetch(`${CAT}/productOffering?limit=100`));
    const wholesale = offs.filter((o) => ((o.category || [{}])[0] || {}).name === 'Wholesale access');
    if (!wholesale.length) { put(box, node('div', 'dim', 'No access products published.')); return; }
    const rows = await Promise.all(wholesale.map(async (o) => {
      let layer = '', bw = '';
      try {
        const spec = await json(await fetch(`${CAT}/productSpecification/${o.productSpecification.id}`));
        for (const c of spec.productSpecCharacteristic || []) {
          const v = (c.productSpecCharacteristicValue || [{}])[0].value;
          if (c.name === 'accessLayer') layer = v; if (c.name === 'maxDownMbps') bw = v;
        }
      } catch {}
      let price = '';
      try {
        const p = (o.productOfferingPrice || [])[0];
        if (p) { const pr = await json(await fetch(`${CAT}/productOfferingPrice/${p.id}`)); price = pr.price?.value; }
      } catch {}
      return row(
        node('span', 'code', o.name),
        node('span', 'layer', layer),
        node('span', 'dim', `up to ${bw} Mbit/s`),
        node('span', 'end', `${eur(price)}/line/mo`));
    }));
    put(box, ...rows);
  } catch (e) { put(box, node('div', 'err', e.message)); }
}

// --- my access orders (what I have bought) ---
async function loadOrders() {
  const box = el('orders');
  try {
    const rows = await json(await authFetch(`${SO}/wholesaleAccessOrder`));
    if (!rows.length) { put(box, node('div', 'dim', 'No access ordered yet.')); return; }
    put(box, ...rows.slice(-20).reverse().map((w) => {
      const end = node('span', 'end');
      end.append(node('span', `state ${w.state}`, w.state));
      return row(
        node('span', 'code', w.accessOwner),
        node('span', 'layer', w.accessLayer || ''),
        node('span', 'dim',
          `${w.bandwidthMbps || ''} Mbit/s${w.postCode ? ' · ' + w.postCode : ''}`),
        end);
    }));
  } catch (e) {
    put(box, node('div', 'dim', `Access orders need a staff/service:read sign-in (${e.message}).`));
  }
}

// --- what I owe the owners ---
async function loadSettlement() {
  const box = el('settlement');
  try {
    const s = await json(await authFetch(`${SO}/wholesaleSettlement`));
    const owners = s.owner || [];
    if (!owners.length) { put(box, node('div', 'dim', 'Nothing owed yet.')); return; }
    const money = node('div', 'money');
    const owed = node('span');
    owed.append('Owed this month ', node('b', null, eur(s.totalMonthlyOwed)));
    const margin = node('span');
    margin.append('Retail margin ', node('b', null, eur(s.totalMonthlyMargin)));
    money.append(owed, margin);
    put(box, money, ...owners.map((o) => row(
      node('span', 'code', o.accessOwner),
      node('span', 'layer', o.accessLayer || ''),
      node('span', 'dim', `${o.activeLines} line(s) × ${eur(o.ratePerLine)}`),
      node('span', 'end', eur(o.monthlyOwed)
        + (o.marginPerLine != null ? ` · margin ${eur(o.marginPerLine)}/line` : '')))));
  } catch (e) {
    put(box, node('div', 'dim', `Settlement needs a staff/service:read sign-in (${e.message}).`));
  }
}

main();
