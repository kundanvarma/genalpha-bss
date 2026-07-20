/*
 * Dealer console: the retail partner's desk (the CSP + external-retail model (think Elkjøp/Power)).
 * A clerk whose org holds a dealer agreement sells at the counter, mints
 * starter kits for their store, and watches commission accrue — pending
 * through the withdrawal window, earned after it, clawed back when a
 * customer leaves inside it. The org boundary is enforced SERVER-side;
 * this app never gets to choose whose kits or money it sees.
 */
'use strict';

const DEALER = '/dealer/v1';
const CATALOG = '/tmf-api/productCatalogManagement/v4';

const el = (id) => document.getElementById(id);

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

async function loadOfferings() {
  const offers = await json(await authFetch(
    `${CATALOG}/productOffering?lifecycleStatus=Active&limit=100`));
  const select = el('sell-offering');
  select.innerHTML = '';
  for (const o of offers.filter((o) => !o.isBundle)) {
    const opt = new Option(o.name, o.id);
    opt.dataset.name = o.name;
    select.append(opt);
  }
}

async function sell() {
  el('sell-msg').hidden = true;
  el('sell-err').hidden = true;
  const offering = el('sell-offering');
  try {
    const sale = await json(await authFetch(`${DEALER}/sell`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        customerEmail: el('sell-email').value.trim(),
        offeringId: offering.value,
        offeringName: offering.selectedOptions[0]?.dataset.name,
        store: el('sell-store').value.trim() || undefined,
      }),
    }));
    el('sell-msg').textContent = `Sold — order ${sale.productOrderId.slice(0, 8)}… placed for the customer.`;
    el('sell-msg').hidden = false;
    setTimeout(loadCommission, 4000);
  } catch (e) {
    el('sell-err').textContent = e.message;
    el('sell-err').hidden = false;
  }
}

async function mintBatch() {
  try {
    await json(await authFetch(`${DEALER}/kits/batch`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        count: Number(el('batch-count').value) || 1,
        store: el('batch-store').value.trim() || undefined,
      }),
    }));
    loadKits();
  } catch (e) { alert(e.message); }
}

async function loadKits() {
  const kits = await json(await authFetch(`${DEALER}/kits`));
  el('kits').innerHTML = '';
  for (const kit of kits.slice(0, 30)) {
    const row = document.createElement('div');
    row.className = 'row';
    row.dataset.testid = 'kit-row';
    row.innerHTML = `<span class="code">${kit.activationCode}</span>`
      + `<span class="dim">${kit.store || ''}</span>`
      + `<span class="dim">SIM ${kit.iccid.slice(0, 8)}…</span>`
      + `<span class="end"><span class="state ${kit.status}">${kit.status}</span></span>`;
    el('kits').append(row);
  }
  if (!kits.length) {
    el('kits').innerHTML = '<p class="dim" style="font-size:12.5px">No kits yet — mint a batch for your shelf.</p>';
  }
}

async function loadCommission() {
  const money = await json(await authFetch(`${DEALER}/commission`));
  const totals = el('commission-totals');
  totals.innerHTML = '';
  for (const [status, sum] of Object.entries(money.totals || {})) {
    const cell = document.createElement('span');
    cell.innerHTML = `${status}: <b data-testid="total-${status}">${Number(sum).toFixed(2)}`
      + ` ${money.commissionPerActivation.unit}</b>`;
    totals.append(cell);
  }
  el('commission').innerHTML = '';
  for (const entry of (money.entries || []).slice(0, 30)) {
    const row = document.createElement('div');
    row.className = 'row';
    row.dataset.testid = 'commission-row';
    row.innerHTML = `<span>${entry.offeringName || 'activation'}</span>`
      + `<span class="dim">${entry.store || ''}</span>`
      + (entry.reason ? `<span class="dim">${entry.reason}</span>` : '')
      + `<span class="end">${Number(entry.amount.value).toFixed(2)} ${entry.amount.unit}`
      + ` <span class="state ${entry.status}">${entry.status}</span></span>`;
    el('commission').append(row);
  }
}

async function pullDialList() {
  el('dial-summary').hidden = true;
  el('dial-list').innerHTML = '';
  try {
    const list = await json(await authFetch(
      `${DEALER}/telesales/dialList?segment=${encodeURIComponent(el('dial-segment').value.trim())}`));
    el('dial-summary').textContent = `${list.entries.length} callable · `
      + `${list.reservedExcluded} reserved excluded · ${list.unwashedExcluded} unwashable excluded`;
    el('dial-summary').hidden = false;
    for (const entry of list.entries) {
      const row = document.createElement('div');
      row.className = 'row';
      row.dataset.testid = 'dial-row';
      row.innerHTML = `<span>${entry.name}</span><span class="code">${entry.phone}</span>`
        + `<span class="dim">${entry.consent}</span>`;
      el('dial-list').append(row);
    }
  } catch (e) { alert(e.message); }
}

async function recordOffer() {
  el('ts-msg').hidden = true;
  el('ts-err').hidden = true;
  const offering = el('ts-offering');
  try {
    const offer = await json(await authFetch(`${DEALER}/telesales/offer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        customerEmail: el('ts-email').value.trim(),
        prospectName: el('ts-name').value.trim() || undefined,
        phone: el('ts-phone').value.trim(),
        offeringId: offering.value,
        offeringName: offering.selectedOptions[0]?.dataset.name,
        campaign: el('ts-campaign').value.trim() || undefined,
      }),
    }));
    el('ts-msg').textContent = offer.prospect
      ? `Offer recorded — COLD prospect: read them code ${offer.confirmToken} (your SMS carries it).`
        + ' It binds only when they register and confirm.'
      : 'Offer recorded — the customer has the confirmation in their inbox. Nothing is ordered'
        + ' until they say yes in writing.';
    el('ts-msg').hidden = false;
    loadPipeline();
  } catch (e) {
    el('ts-err').textContent = e.message;
    el('ts-err').hidden = false;
  }
}

async function loadPipeline() {
  const offers = await json(await authFetch(`${DEALER}/telesales/offers`));
  el('ts-pipeline').innerHTML = '';
  for (const offer of offers.slice(0, 15)) {
    const row = document.createElement('div');
    row.className = 'row';
    row.dataset.testid = 'ts-offer-row';
    row.innerHTML = `<span>${offer.offeringName || 'offer'}</span>`
      + `<span class="dim">${offer.campaign || ''}</span>`
      + `<span class="end"><span class="state ${offer.status}">${offer.status}</span></span>`;
    el('ts-pipeline').append(row);
  }
}

/* ---------- boot ---------- */
async function main() {
  const ready = await ensureSignedIn().catch(() => false);
  if (!ready) { el('signin').hidden = false; return; }
  el('username').textContent = tokenClaims().preferred_username || '';
  el('logout').hidden = false;
  el('logout').addEventListener('click', signOut);

  // the gate is a FACT, not a role: your org holds a dealer agreement,
  // or this console has nothing for you (checked server-side; this
  // probe just decides which message you see)
  let dealer = null;
  try {
    dealer = await json(await authFetch(`${DEALER}/commission`));
  } catch (e) { dealer = null; }
  if (!dealer) { el('nogate').hidden = false; return; }

  el('org-name').textContent = dealer.dealer || '';
  el('main').hidden = false;
  el('sell-go').addEventListener('click', sell);
  el('batch-go').addEventListener('click', mintBatch);
  el('dial-go').addEventListener('click', pullDialList);
  el('ts-go').addEventListener('click', recordOffer);
  await Promise.all([loadOfferings(), loadKits()]);
  // the telesales offering picker shares the catalog the sell form loads
  const sellOptions = el('sell-offering').innerHTML;
  el('ts-offering').innerHTML = sellOptions;
  loadPipeline();
  loadCommission();
  setInterval(loadCommission, 8000);
}
main();
