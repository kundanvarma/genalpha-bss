/*
 * Business console: the CUSTOMER-side back office. A company's admin manages
 * their own people and lines against the same TMF APIs every other channel
 * uses — party (members), ordering (subscriptions), service inventory (live
 * lines), billing (the consolidated company invoice). The org boundary is
 * enforced server-side; this app never gets to choose whose data it sees.
 */
'use strict';

const PARTY = '/tmf-api/party/v4';
const CATALOG = '/tmf-api/productCatalogManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
const BILLING = '/tmf-api/customerBillManagement/v4';
const ROLES = '/tmf-api/rolesAndPermissionsManagement/v4';
const CONSUMPTION = '/tmf-api/usageConsumption/v4';
const INVENTORY = '/tmf-api/productInventory/v4';

const el = (id) => document.getElementById(id);
// the tenant's brand colour drives the console, same as every other channel
// the company policy is priced in the tenant's currency unless a saved policy says otherwise
if (window.BSS_BIZ_CONFIG?.currency) {
  const sel = document.getElementById('policy-unit');
  if (sel) {
    const cur = window.BSS_BIZ_CONFIG.currency;
    if (![...sel.options].some((o) => o.value === cur)) sel.append(new Option(cur, cur));
    sel.value = cur;
  }
}
if (window.BSS_BIZ_CONFIG?.brandColor) {
  document.documentElement.style.setProperty('--teal', window.BSS_BIZ_CONFIG.brandColor);
}
let me = null;
let orgId = null;

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

const emailOf = (p) => (p.contactMedium || [])
  .find((m) => m.mediumType === 'email')?.characteristic?.emailAddress || '';

/* ---------- members + their live lines ---------- */
async function loadMembers() {
  const members = await json(await authFetch(`${PARTY}/individual?organizationId=${orgId}&limit=100`));
  const box = el('members');
  box.replaceChildren();
  const picker = el('order-member');
  picker.replaceChildren();
  const swapPicker = el('swap-member');
  swapPicker.replaceChildren(new Option(t('Who…'), ''));
  el('swap-line').replaceChildren(new Option(t('Their line…'), ''));
  const reassignFrom = el('reassign-member');
  const reassignTo = el('reassign-to');
  reassignFrom.replaceChildren(new Option(t('From whom…'), ''));
  reassignTo.replaceChildren(new Option(t('To whom…'), ''));
  window._peopleById = Object.fromEntries(members.map((m) => [m.id,
    `${m.givenName || ''} ${m.familyName || ''}`.trim() || m.email || m.id.slice(0, 8)]));
  resolveLineFor();
  for (const m of members) {
    const row = document.createElement('div');
    row.className = 'memberrow';
    row.dataset.member = m.id;
    const name = document.createElement('span');
    name.innerHTML = `<b>${esc(m.givenName || '')} ${esc(m.familyName || '')}</b>`;
    const mail = document.createElement('span');
    mail.className = 'mail';
    mail.textContent = emailOf(m);
    const lines = document.createElement('span');
    lines.className = 'lines';
    lines.textContent = '…';
    row.append(name, mail, lines);
    box.append(row);
    const label = `${m.givenName || ''} ${m.familyName || ''}`.trim() || m.id;
    picker.append(new Option(label, m.id));
    reassignFrom.append(new Option(label, m.id));
    reassignTo.append(new Option(label, m.id));
    swapPicker.append(new Option(label, m.id));
    // live lines, fail-soft
    authFetch(`${SERVICE_INV}/service?relatedPartyId=${m.id}`)
      .then(json)
      .then((svcs) => {
        const active = (svcs || []).filter((sv) => sv.state === 'active');
        const nums = active.flatMap((sv) => (sv.supportingResource || []).map((r) => r.value)).filter(Boolean);
        lines.innerHTML = active.length
          ? `${active.length} ${t(active.length > 1 ? 'lines' : 'line')} · <span class="msisdn">${esc(nums.join(' · '))}</span>`
          : t('no lines yet');
      })
      .catch(() => { lines.textContent = ''; });
  }
  if (!members.length) box.textContent = t('Nobody yet — add your first person below.');
  return members;
}

async function addMember() {
  const given = el('new-given').value.trim();
  const family = el('new-family').value.trim();
  const email = el('new-email').value.trim();
  const status = el('member-status');
  if (!given || !family) { status.className = 'err'; status.textContent = 'name required'; return; }
  status.className = ''; status.textContent = t('adding…');
  try {
    // With an email we provision a real LOGIN first (TMF672 mints the IdP
    // account, customer role only), then pin the party's id to the new token
    // subject — so the person can sign in here and land on THEIR line.
    let login = null;
    if (email) {
      login = await json(await authFetch(`${ROLES}/user`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, givenName: given, familyName: family }),
      }));
    }
    await json(await authFetch(`${PARTY}/individual`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...(login ? { id: login.id } : {}),
        givenName: given, familyName: family,
        ...(email ? { contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] } : {}),
      }),
    }));
    status.className = 'ok';
    if (login) {
      // Shown once, for hand-over. The IdP never returns it again.
      status.innerHTML = `✓ added — they can sign in here with
        <span data-testid="invite-credentials" style="font-family:ui-monospace,Menlo,monospace">${esc(email)} / ${esc(login.temporaryPassword)}</span>`;
    } else {
      status.textContent = '✓ added to your organization (no email — no sign-in)';
    }
    el('new-given').value = ''; el('new-family').value = ''; el('new-email').value = '';
    loadMembers();
  } catch (e) { status.className = 'err'; status.textContent = e.message; }
}

/* ---------- ordering for a member ---------- */
const categoryOf = (o) => ((o.category || [])[0] || {}).name || '';
const PLAN_CATS = ['Mobile plans', 'Broadband'];

async function loadOfferings() {
  const offers = await json(await authFetch(`${CATALOG}/productOffering?limit=100`));
  const picker = el('order-offering');
  picker.replaceChildren();
  window.__bizOfferings = offers;
  const orderable = offers.filter((x) => !x.isBundle && !x.requiresVerifiedIdentity);
  // plan changes are like-for-like: plans only, never devices or add-ons
  const swapPicker = el('swap-offering');
  swapPicker.replaceChildren(new Option(t('New plan…'), ''));
  for (const o of orderable) {
    picker.append(new Option(o.name, o.id));
    if (PLAN_CATS.includes(categoryOf(o))) {
      swapPicker.append(new Option(o.name, o.id));
    }
  }
  return orderable;
}

/* ---------- the B2B price view: list price vs THIS company's price ----------
 * Same catalog as every channel, but priced through the policy component with
 * the org in the context — a negotiated deal or volume tier authored as a
 * pricing rule shows up here, without touching the consumer storefront. */
async function loadPlans(orderable, memberCount) {
  const box = el('plans');
  box.textContent = 'loading…';
  const prices = await json(await authFetch(`${CATALOG}/productOfferingPrice?limit=100`))
    .catch(() => []);
  const priceById = Object.fromEntries(prices.map((p) => [p.id, p]));
  box.replaceChildren();
  // the price view is about SUBSCRIPTIONS — plans and add-ons, not hardware
  for (const o of orderable.filter((x) => categoryOf(x) !== 'Devices')) {
    const monthly = (o.productOfferingPrice || [])
      .map((ref) => priceById[ref.id])
      .filter((p) => p && p.priceType === 'recurring' && p.price?.value != null)
      .reduce((sum, p) => sum + p.price.value, 0);
    if (!monthly) continue;
    // the price's own unit — a Norwegian tenant prices in NOK, not EUR
    const unit = (o.productOfferingPrice || []).map((ref) => priceById[ref.id])
      .find((p) => p && p.price?.unit)?.price?.unit;
    const row = document.createElement('div');
    row.dataset.plan = o.id;
    row.innerHTML = `${esc(o.name)} <span style="float:right" data-price>${esc(fmtMoney(monthly, unit))}/${t('month')}</span>`;
    box.append(row);
    // negotiated price, fail-soft to list
    authFetch('/tmf-api/policyManagement/v4/price', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ context: {
        subtotal: monthly, offeringIds: [o.id],
        organizationId: orgId, party: me.id, memberCount,
      } }),
    })
      .then(json)
      .then((r) => {
        if (!(r.adjustments || []).length) return;
        const label = r.adjustments.map((a) => a.label).join(', ');
        row.querySelector('[data-price]').innerHTML =
          `<s style="opacity:.55">${esc(fmtMoney(monthly, unit))}</s>
           <b class="msisdn" data-testid="your-price">${esc(fmtMoney(r.total, unit))}/${t('month')}</b>
           <span style="opacity:.7">· ${esc(label)}</span>`;
      })
      .catch(() => {});
  }
  if (!box.children.length) box.textContent = t('No priced plans in the catalog.');
}

/* ---------- the ONE oracle (TMF760): a configurable offering is configured here as in the shop ---------- */
let orderSpace = null;
async function loadOrderConfig() {
  const box = el('order-config'); const priceLine = el('order-price');
  box.replaceChildren(); priceLine.textContent = ''; orderSpace = null;
  const offering = el('order-offering').value;
  if (!offering) return;
  const q = await authFetch('/tmf-api/productConfigurationManagement/v5/queryProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productConfiguration: { productOffering: { id: offering } } }) }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const space = q && (q.computedProductConfigurationItem || [])[0];
  if (!space || (!(space.configurationCharacteristic || []).length && !space.fungible)) return;
  orderSpace = space;
  for (const ch of space.configurationCharacteristic || []) {
    const vals = ch.productSpecCharacteristicValue || [];
    const range = vals.find((v) => v.value == null && (v.valueFrom != null || v.valueTo != null));
    const label = document.createElement('label'); label.className = 'dim'; label.textContent = ch.name + ' ';
    let input;
    if (range) {
      input = document.createElement('input'); input.type = 'number'; input.min = range.valueFrom ?? ''; input.max = range.valueTo ?? ''; input.value = range.valueFrom ?? 0; input.style.width = '70px';
    } else {
      input = document.createElement('select');
      for (const v of vals) { const o = new Option(v.value + (v.isSelectable === false ? ' — ' + t('sold out') : ''), v.value); o.disabled = v.isSelectable === false; if (v.isDefault) o.selected = true; input.append(o); }
    }
    input.dataset.pick = ch.name; input.addEventListener('change', priceOrderConfig); input.addEventListener('input', priceOrderConfig);
    label.append(input); box.append(label);
  }
  if (space.fungible) {
    const label = document.createElement('label'); label.className = 'dim'; label.textContent = t('how many') + ' ';
    const input = document.createElement('input'); input.type = 'number'; input.min = 1; input.value = 1; input.id = 'order-quantity'; input.style.width = '70px';
    input.addEventListener('change', priceOrderConfig); input.addEventListener('input', priceOrderConfig);
    label.append(input); box.append(label);
  }
  priceOrderConfig();
}
function orderPicks() {
  const picks = {};
  for (const input of el('order-config').querySelectorAll('[data-pick]')) picks[input.dataset.pick] = input.value;
  const q = el('order-quantity'); return { picks, quantity: q ? Math.max(1, parseInt(q.value || '1', 10)) : 1 };
}
async function priceOrderConfig() {
  const offering = el('order-offering').value; if (!offering || !orderSpace) return;
  const { picks, quantity } = orderPicks();
  const r = await authFetch('/tmf-api/productConfigurationManagement/v5/checkProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ checkProductConfigurationItem: [{ id: '1', productConfiguration: { productOffering: { id: offering }, quantity,
      configurationCharacteristic: Object.entries(picks).map(([name, value]) => ({ name, value: String(value) })) } }] }) }).then((x) => (x.ok ? x.json() : null)).catch(() => null);
  const item = r && (r.checkProductConfigurationItem || [])[0];
  const line = el('order-price');
  if (!item) { line.textContent = ''; return; }
  if (item.state === 'rejected') { line.className = 'err'; line.textContent = (item.message || []).join(' · '); el('place-order').disabled = true; return; }
  const p = item.configurationPrice || {};
  line.className = 'dim'; line.textContent = `${fmtMoney(Number(p.monthlyTotal?.value || 0), p.monthlyTotal?.unit)}/${t('month')}` + (Number(p.oneTimeTotal?.value || 0) > 0 ? ` + ${fmtMoney(Number(p.oneTimeTotal.value), p.oneTimeTotal.unit)} ${t('once')}` : '');
  el('place-order').disabled = false;
}

async function placeOrder() {
  const member = el('order-member').value;
  const offering = el('order-offering').value;
  const status = el('order-status');
  if (!member || !offering) return;
  status.className = ''; status.textContent = t('ordering…');
  try {
    const { picks, quantity } = orderSpace ? orderPicks() : { picks: {}, quantity: 1 };
    const item = { id: '1', action: 'add', quantity: orderSpace && orderSpace.fungible ? quantity : 1,
      productOffering: { id: offering, name: el('order-offering').selectedOptions[0]?.text } };
    if (Object.keys(picks).length) item.product = { productCharacteristic: Object.entries(picks).map(([name, value]) => ({ name, value: String(value) })) };
    await json(await authFetch(`${ORDERING}/productOrder`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        productOrderItem: [item],
        relatedParty: [{ id: member, role: 'customer' }],
      }),
    }));
    status.className = 'ok'; status.textContent = t('✓ ordered — the line activates in seconds');
    setTimeout(loadMembers, 8000);
    setTimeout(loadMembers, 20000);
  } catch (e) { status.className = 'err'; status.textContent = e.message; }
}

/* ---------- plan change for a member: same line, same number ---------- */
async function loadSwapLines() {
  const member = el('swap-member').value;
  const lines = el('swap-line');
  lines.replaceChildren(new Option(t('Their line…'), ''));
  if (!member) return;
  const svcs = await json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${member}`))
    .catch(() => []);
  // the member's products, so a line knows its offering (exchangableTo lives on the offering)
  window.__bizProducts = await json(await authFetch(`${INVENTORY}/product?relatedPartyId=${member}&status=active&limit=100`)).catch(() => []);
  // the swap picker follows the line: exchangableTo on the line's offering, else the plan categories
  const restrict = async () => {
    const line = lines.selectedOptions[0]; const swapPicker = el('swap-offering');
    const offeringId = line && line.dataset.offering;
    const all = window.__bizOfferings || [];
    let targets = all.filter((o) => !o.isBundle && !o.requiresVerifiedIdentity && PLAN_CATS.includes(categoryOf(o)));
    if (offeringId) {
      const cur = all.find((o) => o.id === offeringId);
      const ex = (cur?.productOfferingRelationship || []).filter((r) => String(r.relationshipType || '').toLowerCase() === 'exchangableto').map((r) => r.id);
      if (ex.length) targets = all.filter((o) => ex.includes(o.id));
    }
    swapPicker.replaceChildren(new Option(t('New plan…'), ''));
    for (const o of targets) swapPicker.append(new Option(o.name, o.id));
  };
  lines.onchange = restrict;
  for (const sv of (svcs || []).filter((s) => s.state === 'active')) {
    const num = (sv.supportingResource || []).map((r) => r.value).filter(Boolean).join(' ');
    const opt = new Option(`${sv.name}${num ? ' · ' + num : ''}`, sv.id);
    opt.dataset.planName = sv.name;
    lines.append(opt);
  }
}

/* ---------- reassign a line: the employee left, the number stays ---------- */
async function loadReassignLines() {
  const member = el('reassign-member').value;
  const lines = el('reassign-line');
  lines.replaceChildren(new Option(t('Their line…'), ''));
  if (!member) return;
  const svcs = await json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${member}`))
    .catch(() => []);
  for (const sv of (svcs || []).filter((s) => s.state === 'active')) {
    const num = (sv.supportingResource || []).map((r) => r.value).filter(Boolean).join(' ');
    { const opt = new Option(`${sv.name}${num ? ' · ' + num : ''}`, sv.id); const prod = (window.__bizProducts || []).find((p) => p.name === sv.name && (p.relatedParty || []).some((rp) => rp.id === member)); if (prod && prod.productOffering) opt.dataset.offering = prod.productOffering.id; lines.append(opt); }
  }
}

async function reassignLine() {
  const serviceId = el('reassign-line').value;
  const to = el('reassign-to').value;
  const status = el('reassign-status');
  if (!serviceId || !to) return;
  status.className = ''; status.textContent = t('moving…');
  try {
    await json(await authFetch(`${SERVICE_INV}/service/${serviceId}/transfer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ toPartyId: to }),
    }));
    status.className = 'ok';
    status.textContent = t('done — the line, number and SIM now belong to them');
    loadReassignLines();
  } catch (e) { status.className = 'err'; status.textContent = e.message; }
}


/** A customer admin should never read a UUID: once people are known, every
 *  bill line's "for whom" resolves to the person's name. */
function resolveLineFor() {
  for (const span of document.querySelectorAll('.linefor[data-for]')) {
    const name = window._peopleById?.[span.dataset.for];
    if (name) span.textContent = name;
  }
}
async function swapPlan() {
  const member = el('swap-member').value;
  const line = el('swap-line');
  const serviceId = line.value;
  const currentPlan = line.selectedOptions[0]?.dataset.planName;
  const offering = el('swap-offering').value;
  const status = el('swap-status');
  if (!member || !serviceId || !offering) return;
  status.className = ''; status.textContent = t('changing…');
  try {
    // the member's installed product behind that line (matched by plan name)
    const products = await json(await authFetch(
      `${INVENTORY}/product?relatedPartyId=${member}&status=active&limit=100`));
    const product = (products || []).find((p) => p.name === currentPlan);
    if (!product) throw new Error('no active product found for that line');
    await json(await authFetch(`${ORDERING}/productOrder`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        productOrderItem: [{
          action: 'modify',
          product: { id: product.id, realizingService: [{ id: serviceId }] },
          productOffering: { id: offering, name: el('swap-offering').selectedOptions[0]?.text },
        }],
        relatedParty: [{ id: member, role: 'customer' }],
      }),
    }));
    status.className = 'ok'; status.textContent = t('✓ plan changed — same number, new plan');
    loadMembers();
    loadSwapLines();
    loadBills();
  } catch (e) { status.className = 'err'; status.textContent = e.message; }
}

/* ---------- company policy: the device co-pay allowance ---------- */
function loadPolicy(org) {
  const allowance = org?.deviceAllowance;
  if (allowance?.value != null) {
    el('policy-allowance').value = allowance.value;
    const unit = allowance.unit || window.BSS_BIZ_CONFIG?.currency || 'EUR';
    const sel = el('policy-unit');
    if (![...sel.options].some((o) => o.value === unit)) sel.append(new Option(unit, unit));
    sel.value = unit;
  }
}

async function savePolicy() {
  const status = el('policy-status');
  const value = el('policy-allowance').value.trim();
  if (value && Number.isNaN(Number(value))) {
    status.className = 'err'; status.textContent = t('enter an amount');
    return;
  }
  status.className = ''; status.textContent = t('saving…');
  try {
    await json(await authFetch(`${PARTY}/organization/${orgId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceAllowance: value === '' ? { value: null }
        : { value: Number(value), unit: el('policy-unit').value } }),
    }));
    status.className = 'ok';
    status.textContent = value === '' ? t('✓ allowance removed — the company pays devices in full')
      : t('✓ saved — applies from the next billing run');
  } catch (e) { status.className = 'err'; status.textContent = e.message; }
}

/* ---------- the consolidated company invoice ---------- */
async function loadBills() {
  const bills = await json(await authFetch(`${BILLING}/customerBill?relatedPartyId=${orgId}&limit=50`));
  const box = el('bills');
  box.replaceChildren();
  for (const b of bills) {
    const row = document.createElement('div');
    row.className = 'billrow';
    row.innerHTML = `<b>${esc(b.billNo)}</b> · ${esc(b.state)}
      <span class="amount">${esc(fmtMoney(b.amountDue.value, b.amountDue.unit))}</span>`;
    const lines = document.createElement('div');
    lines.className = 'billlines';
    lines.textContent = 'loading lines…';
    row.append(lines);
    box.append(row);
    authFetch(`${BILLING}/customerBill/${b.id}/appliedCustomerBillingRate`)
      .then(json)
      .then((rates) => {
        lines.replaceChildren(...rates.map((r) => {
          const d = document.createElement('div');
          const label = window._peopleById?.[r.forParty?.id];
          const who = r.forParty?.id ? ` — <span data-for="${esc(r.forParty.id)}" class="linefor">${esc(label || r.forParty.id.slice(0, 8) + '…')}</span>` : '';
          d.innerHTML = `${esc(r.name)}${who} <span style="float:right">${esc(fmtMoney(r.taxExcludedAmount.value, r.taxExcludedAmount.unit))}</span>`;
          return d;
        }));
        resolveLineFor();
      })
      .catch(() => { lines.textContent = ''; });
  }
  if (!bills.length) box.innerHTML = `<span class="dimhint">${t('No invoices yet — they appear after the operator\'s billing run.')}</span>`;
}

/* ---------- the MEMBER's my-page: the line their company pays for ---------- */
async function renderMemberView(orgName) {
  el('member-org-name').textContent = orgName;
  const note = el('member-billing-note');
  note.replaceChildren(
    document.createTextNode(t('Your subscription is paid by your company — charges appear on') + ' '),
    Object.assign(document.createElement('b'), { textContent: orgName }),
    document.createTextNode(' — ' + t("your organization's consolidated invoice. Anything you buy yourself, and any device cost above the company allowance, appears below on your personal bill.")));
  el('memberview').hidden = false;

  // the member's PERSONAL bill: their own purchases + device co-pay excess
  authFetch(`${BILLING}/customerBill?relatedPartyId=${me.id}&limit=20`)
    .then(json)
    .then((personalBills) => {
      const box = el('member-bills');
      box.replaceChildren();
      for (const b of personalBills || []) {
        const row = document.createElement('div');
        row.className = 'billrow';
        row.dataset.personalBill = b.id;
        row.innerHTML = `<b>${esc(b.billNo)}</b> · ${esc(b.state)}
          <span class="amount">${esc(fmtMoney(b.amountDue.value, b.amountDue.unit))}</span>`;
        const lines = document.createElement('div');
        lines.className = 'billlines';
        row.append(lines);
        box.append(row);
        authFetch(`${BILLING}/customerBill/${b.id}/appliedCustomerBillingRate`)
          .then(json)
          .then((billRates) => {
            lines.replaceChildren(...billRates.map((r) => {
              const d = document.createElement('div');
              d.innerHTML = `${esc(r.name)} <span style="float:right">${esc(fmtMoney(r.taxExcludedAmount.value, r.taxExcludedAmount.unit))}</span>`;
              return d;
            }));
          })
          .catch(() => { lines.textContent = ''; });
      }
    })
    .catch(() => {});

  const box = el('member-lines');
  box.textContent = 'loading…';
  try {
    const svcs = await json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${me.id}`));
    box.replaceChildren();
    for (const sv of (svcs || []).filter((s) => s.state === 'active' || s.state === 'inactive')) {
      const row = document.createElement('div');
      row.className = 'memberrow';
      row.dataset.service = sv.id;
      const name = document.createElement('span');
      name.innerHTML = `<b>${esc(sv.name || 'Service')}</b>`;
      const num = document.createElement('span');
      num.className = 'lines';
      const msisdns = (sv.supportingResource || []).map((r) => r.value).filter(Boolean);
      num.innerHTML = `${esc(sv.state)} ${msisdns.length ? `· <span class="msisdn">${esc(msisdns.join(' · '))}</span>` : ''}`;
      row.append(name, num);
      box.append(row);
    }
    if (!box.children.length) box.textContent = t('No lines yet — your company admin can order one for you.');
    // SIM self-care per line: masked ICCID, PUK on request, OTA PIN reset.
    for (const row of box.querySelectorAll('.memberrow[data-service]')) {
      const sid = row.dataset.service;
      authFetch(`${SERVICE_INV}/service/${sid}/sim`).then(json).then((sim) => {
        const simRow = document.createElement('div');
        simRow.className = 'billlines';
        simRow.style.margin = '2px 0 8px 14px';
        simRow.dataset.simFor = sid;
        simRow.innerHTML = `SIM <span class="msisdn">${esc(sim.iccid)}</span>
          <button class="ghost" data-puk style="margin-left:8px">${t('Show PUK')}</button>
          <input data-pin placeholder="${t('New PIN')}" inputmode="numeric" maxlength="8" style="width:6em;margin-left:8px">
          <button class="ghost" data-reset>${t('Reset PIN')}</button> <span data-sim-status></span>`;
        row.after(simRow);
        simRow.querySelector('[data-puk]').addEventListener('click', async () => {
          const full = await json(await authFetch(`${SERVICE_INV}/service/${sid}/sim?reveal=true`));
          simRow.querySelector('[data-puk]').replaceWith(Object.assign(document.createElement('b'),
            { textContent: `PUK ${full.puk}`, className: 'msisdn' }));
        });
        simRow.querySelector('[data-reset]').addEventListener('click', async () => {
          const status = simRow.querySelector('[data-sim-status]');
          try {
            await json(await authFetch(`${SERVICE_INV}/service/${sid}/sim/resetPin`, {
              method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ newPin: simRow.querySelector('[data-pin]').value }),
            }));
            status.className = 'ok'; status.textContent = t('✓ sent to your SIM');
          } catch (e) { status.className = 'err'; status.textContent = e.message; }
        });
      }).catch(() => {});
    }
  } catch (e) { box.textContent = 'Could not load your services.'; }

  // usage buckets, fail-soft
  authFetch(`${CONSUMPTION}/queryUsageConsumption`)
    .then(json)
    .then((report) => {
      const usage = el('member-usage');
      usage.replaceChildren(...(report.bucket || []).map((b) => {
        const d = document.createElement('div');
        d.innerHTML = `${esc(b.name)} <span style="float:right">${esc(b.usedValue)}${b.allowedValue != null ? ` / ${esc(b.allowedValue)}` : ''} ${esc(b.units || '')}</span>`;
        return d;
      }));
      if (!usage.children.length) usage.textContent = t('No usage yet.');
    })
    .catch(() => { el('member-usage').textContent = 'No usage yet.'; });
}

/* ---------- boot ---------- */
async function main() {
  const ready = await ensureSignedIn().catch(() => false);
  if (!ready) { el('signin').hidden = false; return; }
  el('username').textContent = tokenClaims().preferred_username || '';
  el('logout').hidden = false;
  el('logout').addEventListener('click', signOut);

  try {
    me = await json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}`));
  } catch (e) { me = null; }
  orgId = me?.organization?.id;
  if (!orgId) { el('nogate').hidden = false; return; }

  const org = await json(await authFetch(`${PARTY}/organization/${orgId}`)).catch(() => null);
  const orgName = org?.name || orgId;

  // One channel, two faces: the org's admin runs the company; everyone
  // else who belongs to the org sees their own work line.
  if (!hasRole('business:admin')) {
    renderMemberView(orgName);
    return;
  }

  el('org-name').textContent = orgName;
  el('main').hidden = false;

  el('add-member').addEventListener('click', addMember);
  el('place-order').addEventListener('click', placeOrder);
  el('order-offering').addEventListener('change', loadOrderConfig);
  el('swap-member').addEventListener('change', loadSwapLines);
  el('reassign-member').addEventListener('change', loadReassignLines);
  el('reassign-go').addEventListener('click', reassignLine);
  el('swap-plan').addEventListener('click', swapPlan);
  el('save-policy').addEventListener('click', savePolicy);
  loadPolicy(org);
  loadBills();
  const [members, orderable] = await Promise.all([loadMembers(), loadOfferings()]);
  loadPlans(orderable, (members || []).length);
}
main();
