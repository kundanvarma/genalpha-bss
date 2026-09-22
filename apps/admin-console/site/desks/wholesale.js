'use strict';

/* ---------------- Wholesale panes (fixed wholesale console) ---------------- */

const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

function downloadCsv(rows, filename) {
  const blob = new Blob([rows.join('\n')], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

// FC2 — onboard an access owner (TMF632 org + TMF668 partnershipType + TMF651 agreement).
async function renderWholesaleOwners() {
  const panel = panelFor('wholesale-panel');
  panel.innerHTML = '<div class="editor"><h2>Access owners</h2><p class="dim">Loading…</p></div>';
  const [orgs, agreements] = await Promise.all([
    authFetch(`${PARTY_BASE}/organization?limit=100`).then((r) => r.json()).catch(() => []),
    authFetch(`${AGREEMENT_BASE}/agreement?limit=100`).then((r) => r.json()).catch(() => []),
  ]);
  const partnerships = (agreements || []).filter((a) => a.agreementType === 'partnership');
  let html = '<div class="editor"><h2>Access owners</h2>'
    + '<p class="dim small">Onboard a wholesale access owner: an organization party plus a TMF651 '
    + 'partnership agreement (accessProvider role). Access products reference the owner by code.</p>'
    + '<div class="fields">'
    + '<label class="field"><span>Owner name *</span><input id="wo-name" placeholder="NordAccess AS"></label>'
    + '<label class="field"><span>Owner code *</span><input id="wo-code" placeholder="NORDACCESS"></label>'
    + '<label class="field"><span>Default layer</span><select id="wo-layer">'
    + '<option value="L3-activated">L3-activated</option><option value="L2-VULA">L2-VULA</option></select></label>'
    + '</div><div class="actions"><button class="primary" id="wo-create">Onboard owner</button>'
    + '<span id="wo-msg" class="dim"></span></div>'
    + '<h2 style="margin-top:1.5rem">Wholesale partnership agreements</h2>';
  if (!partnerships.length) {
    html += '<p class="dim">None yet.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Agreement</th><th>Status</th>'
      + '<th>Owner code</th><th>Layer</th></tr></thead><tbody>';
    for (const a of partnerships) {
      const ch = a.characteristic || {};
      html += `<tr><td>${esc(a.name)}</td><td>${esc(a.status)}</td>`
        + `<td>${esc(ch.accessOwnerCode)}</td><td>${esc(ch.accessLayer)}</td></tr>`;
    }
    html += '</tbody></table></div>';
  }
  html += '</div>';
  panel.innerHTML = html;
  panel.querySelector('#wo-create').addEventListener('click', async () => {
    const msg = panel.querySelector('#wo-msg');
    const name = panel.querySelector('#wo-name').value.trim();
    const code = panel.querySelector('#wo-code').value.trim();
    const layer = panel.querySelector('#wo-layer').value;
    if (!name || !code) { msg.textContent = 'Fill name + code.'; msg.className = 'error'; return; }
    msg.textContent = 'Onboarding…'; msg.className = 'dim';
    try {
      const org = await authFetch(`${PARTY_BASE}/organization`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, tradingName: code, '@type': 'Organization' }) }).then((r) => r.json());
      const pts = await authFetch(`${PARTNERSHIP_BASE}/partnershipType?limit=100`).then((r) => r.json()).catch(() => []);
      let pt = (pts || []).find((t) => t.name === 'Wholesale access');
      if (!pt) {
        pt = await authFetch(`${PARTNERSHIP_BASE}/partnershipType`, { method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: 'Wholesale access', status: 'active',
            roleType: [{ name: 'accessProvider' }, { name: 'accessSeeker' }] }) }).then((r) => r.json());
      }
      const agr = await authFetch(`${AGREEMENT_BASE}/agreement`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: `Wholesale access — ${name}`, agreementType: 'partnership', status: 'active',
          engagedParty: [{ id: org.id, role: 'accessProvider', '@referredType': 'Organization' }],
          characteristic: { partnershipTypeId: pt.id, accessOwnerCode: code, accessLayer: layer } }) });
      if (!agr.ok) { const p = await agr.json().catch(() => ({})); throw new Error(p.message || 'agreement create failed'); }
      msg.textContent = 'Owner onboarded ✓'; msg.className = 'ok';
      setTimeout(renderWholesaleOwners, 600);
    } catch (e) { msg.textContent = e.message; msg.className = 'error'; }
  });
}

// FC3+FC5 — publish an L2/L3 access product: price + spec (chars + CFS ref) + offering.
async function renderAccessProduct() {
  const panel = panelFor('wholesale-panel');
  panel.innerHTML = '<div class="editor"><h2>Access products</h2><p class="dim">Loading…</p></div>';
  const [offerings, specs] = await Promise.all([
    authFetch(`${API_BASE}/productOffering?limit=100`).then((r) => r.json()).catch(() => []),
    authFetch(`${SERVICE_CATALOG_BASE}/serviceSpecification?serviceType=CFS&limit=100`).then((r) => r.json()).catch(() => []),
  ]);
  const wholesale = (offerings || []).filter((o) => (o.category || []).some((c) => c.name === 'Wholesale access'));
  const cfsOptions = (specs || []).map((s) => `<option value="${esc(s.id)}|${esc(s.name)}">${esc(s.name)}</option>`).join('');
  let html = '<div class="editor"><h2>Access products</h2>'
    + '<p class="dim small">Publish an L2/L3 wholesale access SKU. It lands in the “Wholesale access” '
    + 'category (hidden from the retail shop) and appears in the partner portal for access seekers to order. '
    + 'Optionally realise it by a TMF633 CFS.</p>'
    + '<div class="fields">'
    + '<label class="field"><span>Product name *</span><input id="wp-name" placeholder="NordAccess Fibre 1000 (L3)"></label>'
    + '<label class="field"><span>Owner code *</span><input id="wp-owner" placeholder="NORDACCESS"></label>'
    + '<label class="field"><span>Access layer *</span><select id="wp-layer">'
    + '<option value="L3-activated">L3-activated</option><option value="L2-VULA">L2-VULA</option></select></label>'
    + '<label class="field"><span>Bandwidth (Mbit/s) *</span><input id="wp-bw" type="number" placeholder="1000"></label>'
    + `<label class="field"><span>Wholesale price / line / month (${tenantCurrency()}) *</span><input id="wp-price" type="number" placeholder="24"></label>`
    + `<label class="field"><span>Realised by CFS (TMF633, optional)</span><select id="wp-cfs"><option value="">— none —</option>${cfsOptions}</select></label>`
    + '</div><div class="actions"><button class="primary" id="wp-create">Publish access product</button>'
    + '<span id="wp-msg" class="dim"></span></div>'
    + '<h2 style="margin-top:1.5rem">Published wholesale access products</h2>';
  if (!wholesale.length) {
    html += '<p class="dim">None yet.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Name</th><th>Description</th></tr></thead><tbody>';
    for (const o of wholesale) html += `<tr><td>${esc(o.name)}</td><td>${esc(o.description)}</td></tr>`;
    html += '</tbody></table></div>';
  }
  html += '</div>';
  panel.innerHTML = html;
  panel.querySelector('#wp-create').addEventListener('click', async () => {
    const msg = panel.querySelector('#wp-msg');
    const name = panel.querySelector('#wp-name').value.trim();
    const owner = panel.querySelector('#wp-owner').value.trim();
    const layer = panel.querySelector('#wp-layer').value;
    const bw = Number(panel.querySelector('#wp-bw').value);
    const price = Number(panel.querySelector('#wp-price').value);
    const cfsRaw = panel.querySelector('#wp-cfs').value;
    if (!name || !owner || !bw || !price) { msg.textContent = 'Fill name, owner, bandwidth, price.'; msg.className = 'error'; return; }
    msg.textContent = 'Publishing…'; msg.className = 'dim';
    try {
      const cats = await authFetch(`${API_BASE}/category?name=Wholesale access`).then((r) => r.json()).catch(() => []);
      let cat = (cats || []).find((c) => c.name === 'Wholesale access');
      if (!cat) {
        cat = await authFetch(`${API_BASE}/category`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: 'Wholesale access', lifecycleStatus: 'Active' }) }).then((r) => r.json());
      }
      const priceObj = await authFetch(`${API_BASE}/productOfferingPrice`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: `${name} monthly`, priceType: 'recurring', recurringChargePeriodType: 'month',
          price: { unit: tenantCurrency(), value: price }, lifecycleStatus: 'Active' }) }).then((r) => r.json());
      const specBody = { name: `${name} spec`, lifecycleStatus: 'Active', productSpecCharacteristic: [
        { name: 'accessOwner', productSpecCharacteristicValue: [{ value: owner }] },
        { name: 'accessLayer', productSpecCharacteristicValue: [{ value: layer }] },
        { name: 'downloadSpeed', productSpecCharacteristicValue: [{ value: bw }] }] };
      if (cfsRaw) {
        const [cid, cname] = cfsRaw.split('|');
        specBody.serviceSpecification = [{ id: cid, name: cname,
          href: `${SERVICE_CATALOG_BASE}/serviceSpecification/${cid}`, '@referredType': 'ServiceSpecification' }];
      }
      const spec = await authFetch(`${API_BASE}/productSpecification`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(specBody) }).then((r) => r.json());
      const offBody = { name, description: `Wholesale ${layer} access from ${owner}, up to ${bw} Mbit/s.`,
        lifecycleStatus: 'Active',
        category: [{ id: cat.id, name: cat.name, '@referredType': 'Category' }],
        productSpecification: { id: spec.id, name: spec.name,
          href: `${API_BASE}/productSpecification/${spec.id}`, '@referredType': 'ProductSpecification' },
        productOfferingPrice: [{ id: priceObj.id, name: priceObj.name,
          href: `${API_BASE}/productOfferingPrice/${priceObj.id}`, '@referredType': 'ProductOfferingPrice' }] };
      const off = await authFetch(`${API_BASE}/productOffering`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(offBody) });
      if (!off.ok) { const p = await off.json().catch(() => ({})); throw new Error(p.message || 'offering create failed'); }
      msg.textContent = 'Published ✓'; msg.className = 'ok';
      setTimeout(renderAccessProduct, 600);
    } catch (e) { msg.textContent = e.message; msg.className = 'error'; }
  });
}

// FC4 — the owner's book: what retailers owe us + what we owe owners, with CSV.
async function renderWholesaleSettlement() {
  const panel = panelFor('wholesale-panel');
  panel.innerHTML = '<div class="editor"><h2>Wholesale settlement</h2><p class="dim">Loading…</p></div>';
  const [asOwner, asSeeker] = await Promise.all([
    authFetch(`${WHOLESALE_ORDER_BASE}/wholesaleProviderSettlement`).then((r) => (r.ok ? r.json() : null)).catch(() => null),
    authFetch(`${WHOLESALE_ORDER_BASE}/wholesaleSettlement`).then((r) => (r.ok ? r.json() : null)).catch(() => null),
  ]);
  const prov = (asOwner && asOwner.retailer) || [];
  const seek = (asSeeker && asSeeker.owner) || [];
  let html = '<div class="editor"><h2>Wholesale settlement</h2>'
    + '<h2 style="font-size:1rem">As the fibre owner — what retailers owe you</h2>';
  if (!prov.length) {
    html += '<p class="dim">No wholesale lines sold to retailers yet.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Retailer</th><th>Layer</th>'
      + '<th>Active lines</th><th>Rate/line</th><th>Monthly</th></tr></thead><tbody>';
    for (const r of prov) for (const l of (r.line || [])) {
      html += `<tr><td>${esc(r.retailer)}</td><td>${esc(l.accessLayer)}</td>`
        + `<td>${esc(l.activeLines)}</td><td>${esc(l.ratePerLine)}</td><td>${esc(l.amount)}</td></tr>`;
    }
    html += `</tbody></table></div><p><b>Total monthly revenue: ${esc(asOwner.totalMonthlyRevenue)} `
      + `${esc(asOwner.currency)}</b> · <button class="ghost" id="csv-owner">Export CSV</button></p>`;
  }
  html += '<h2 style="font-size:1rem;margin-top:1.5rem">As an access seeker — what you owe owners</h2>';
  if (!seek.length) {
    html += '<p class="dim">No wholesale access bought from owners yet.</p>';
  } else {
    html += '<div class="table-wrap"><table><thead><tr><th>Owner</th><th>Layer</th><th>Active lines</th>'
      + '<th>Owed/line</th><th>Retail/line</th><th>Margin/line</th></tr></thead><tbody>';
    for (const o of seek) {
      html += `<tr><td>${esc(o.accessOwner)}</td><td>${esc(o.accessLayer)}</td><td>${esc(o.activeLines)}</td>`
        + `<td>${esc(o.monthlyOwed)}</td><td>${esc(o.retailMonthlyPerLine)}</td><td>${esc(o.marginPerLine)}</td></tr>`;
    }
    html += `</tbody></table></div><p><b>Total owed: ${esc(asSeeker.totalMonthlyOwed)} ${esc(asSeeker.currency)}</b>, `
      + `margin ${esc(asSeeker.totalMonthlyMargin)} · <button class="ghost" id="csv-seeker">Export CSV</button></p>`;
  }
  html += '</div>';
  panel.innerHTML = html;
  panel.querySelector('#csv-owner')?.addEventListener('click', () => {
    const rows = ['retailer,layer,activeLines,ratePerLine,amount'];
    for (const r of prov) for (const l of (r.line || [])) {
      rows.push(`${r.retailer},${l.accessLayer},${l.activeLines},${l.ratePerLine},${l.amount}`);
    }
    downloadCsv(rows, 'wholesale-provider-settlement.csv');
  });
  panel.querySelector('#csv-seeker')?.addEventListener('click', () => {
    const rows = ['owner,layer,activeLines,monthlyOwed,retailPerLine,marginPerLine'];
    for (const o of seek) {
      rows.push(`${o.accessOwner},${o.accessLayer},${o.activeLines},${o.monthlyOwed},${o.retailMonthlyPerLine ?? ''},${o.marginPerLine ?? ''}`);
    }
    downloadCsv(rows, 'wholesale-seeker-settlement.csv');
  });
}
