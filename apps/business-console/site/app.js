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
  const reassignFrom = el('reassign-member');
  const reassignTo = el('reassign-to');
  reassignFrom.replaceChildren(new Option(t('From whom…'), ''));
  reassignTo.replaceChildren(new Option(t('To whom…'), ''));
  for (const m of members) {
    const row = document.createElement('div');
    row.className = 'memberrow';
    row.dataset.member = m.id;
    const name = document.createElement('span');
    name.innerHTML = `<b>${m.givenName || ''} ${m.familyName || ''}</b>`;
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
          ? `${active.length} ${t(active.length > 1 ? 'lines' : 'line')} · <span class="msisdn">${nums.join(' · ')}</span>`
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
        <span data-testid="invite-credentials" style="font-family:ui-monospace,Menlo,monospace">${email} / ${login.temporaryPassword}</span>`;
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
    row.innerHTML = `${o.name} <span style="float:right" data-price>${fmtMoney(monthly, unit)}/${t('month')}</span>`;
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
          `<s style="opacity:.55">${fmtMoney(monthly, unit)}</s>
           <b class="msisdn" data-testid="your-price">${fmtMoney(r.total, unit)}/${t('month')}</b>
           <span style="opacity:.7">· ${label}</span>`;
      })
      .catch(() => {});
  }
  if (!box.children.length) box.textContent = t('No priced plans in the catalog.');
}

async function placeOrder() {
  const member = el('order-member').value;
  const offering = el('order-offering').value;
  const status = el('order-status');
  if (!member || !offering) return;
  status.className = ''; status.textContent = t('ordering…');
  try {
    await json(await authFetch(`${ORDERING}/productOrder`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        productOrderItem: [{ action: 'add', productOffering: {
          id: offering, name: el('order-offering').selectedOptions[0]?.text } }],
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
    lines.append(new Option(`${sv.name}${num ? ' · ' + num : ''}`, sv.id));
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
    el('policy-unit').value = allowance.unit || 'EUR';
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
    row.innerHTML = `<b>${b.billNo}</b> · ${b.state}
      <span class="amount">${fmtMoney(b.amountDue.value, b.amountDue.unit)}</span>`;
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
          const who = r.forParty?.id ? ` — <span data-for="${r.forParty.id}" class="linefor">${r.forParty.id.slice(0, 8)}…</span>` : '';
          d.innerHTML = `${r.name}${who} <span style="float:right">${fmtMoney(r.taxExcludedAmount.value, r.taxExcludedAmount.unit)}</span>`;
          return d;
        }));
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
        row.innerHTML = `<b>${b.billNo}</b> · ${b.state}
          <span class="amount">${fmtMoney(b.amountDue.value, b.amountDue.unit)}</span>`;
        const lines = document.createElement('div');
        lines.className = 'billlines';
        row.append(lines);
        box.append(row);
        authFetch(`${BILLING}/customerBill/${b.id}/appliedCustomerBillingRate`)
          .then(json)
          .then((billRates) => {
            lines.replaceChildren(...billRates.map((r) => {
              const d = document.createElement('div');
              d.innerHTML = `${r.name} <span style="float:right">${fmtMoney(r.taxExcludedAmount.value, r.taxExcludedAmount.unit)}</span>`;
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
      name.innerHTML = `<b>${sv.name || 'Service'}</b>`;
      const num = document.createElement('span');
      num.className = 'lines';
      const msisdns = (sv.supportingResource || []).map((r) => r.value).filter(Boolean);
      num.innerHTML = `${sv.state} ${msisdns.length ? `· <span class="msisdn">${msisdns.join(' · ')}</span>` : ''}`;
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
        simRow.innerHTML = `SIM <span class="msisdn">${sim.iccid}</span>
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
        d.innerHTML = `${b.name} <span style="float:right">${b.usedValue}${b.allowedValue != null ? ` / ${b.allowedValue}` : ''} ${b.units || ''}</span>`;
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
