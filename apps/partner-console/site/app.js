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
  if (!pc) { box.innerHTML = '<div class="dim">Enter a postcode.</div>'; return; }
  box.innerHTML = '<div class="dim">Checking&hellip;</div>';
  try {
    const r = await fetch(`${SQ}/queryAccessOptions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ searchCriteria: { place: { postCode: pc }, technology: 'fiber' } }),
    });
    const d = await json(r);
    const opts = d.accessOption || [];
    if (!opts.length) {
      box.innerHTML = `<div class="dim">No open-access owner serves ${pc} — this would be our own network.</div>`;
      return;
    }
    box.innerHTML = opts.map((o) => `<div class="row">
      <span class="code">${o.accessOwner}</span>
      <span class="layer">${o.accessLayer || ''}</span>
      <span class="dim">fibre</span>
      <span class="end">up to ${o.maxDownMbps} Mbit/s</span></div>`).join('');
  } catch (e) {
    box.innerHTML = `<div class="err">Could not check: ${e.message}</div>`;
  }
}

// --- the L2/L3 access products ---
async function loadCatalog() {
  const box = el('catalog');
  try {
    const offs = await json(await fetch(`${CAT}/productOffering?limit=100`));
    const wholesale = offs.filter((o) => ((o.category || [{}])[0] || {}).name === 'Wholesale access');
    if (!wholesale.length) { box.innerHTML = '<div class="dim">No access products published.</div>'; return; }
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
      return `<div class="row"><span class="code">${o.name}</span>
        <span class="layer">${layer}</span><span class="dim">up to ${bw} Mbit/s</span>
        <span class="end">${eur(price)}/line/mo</span></div>`;
    }));
    box.innerHTML = rows.join('');
  } catch (e) { box.innerHTML = `<div class="err">${e.message}</div>`; }
}

// --- my access orders (what I have bought) ---
async function loadOrders() {
  const box = el('orders');
  try {
    const rows = await json(await authFetch(`${SO}/wholesaleAccessOrder`));
    if (!rows.length) { box.innerHTML = '<div class="dim">No access ordered yet.</div>'; return; }
    box.innerHTML = rows.slice(-20).reverse().map((w) => `<div class="row">
      <span class="code">${w.accessOwner}</span>
      <span class="layer">${w.accessLayer || ''}</span>
      <span class="dim">${w.bandwidthMbps || ''} Mbit/s${w.postCode ? ' · ' + w.postCode : ''}</span>
      <span class="end"><span class="state ${w.state}">${w.state}</span></span></div>`).join('');
  } catch (e) {
    box.innerHTML = `<div class="dim">Access orders need a staff/service:read sign-in (${e.message}).</div>`;
  }
}

// --- what I owe the owners ---
async function loadSettlement() {
  const box = el('settlement');
  try {
    const s = await json(await authFetch(`${SO}/wholesaleSettlement`));
    const owners = s.owner || [];
    if (!owners.length) { box.innerHTML = '<div class="dim">Nothing owed yet.</div>'; return; }
    box.innerHTML =
      `<div class="money"><span>Owed this month <b>${eur(s.totalMonthlyOwed)}</b></span>
       <span>Retail margin <b>${eur(s.totalMonthlyMargin)}</b></span></div>` +
      owners.map((o) => `<div class="row"><span class="code">${o.accessOwner}</span>
        <span class="layer">${o.accessLayer || ''}</span>
        <span class="dim">${o.activeLines} line(s) × ${eur(o.ratePerLine)}</span>
        <span class="end">${eur(o.monthlyOwed)}${o.marginPerLine != null
          ? ` · margin ${eur(o.marginPerLine)}/line` : ''}</span></div>`).join('');
  } catch (e) {
    box.innerHTML = `<div class="dim">Settlement needs a staff/service:read sign-in (${e.message}).</div>`;
  }
}

main();
