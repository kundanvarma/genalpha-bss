/* Integrations — the installers, network-priority and payment seam cards. */
'use strict';

// --- Installers: the tenant's calendar + the technician roster capacity derives from ---
async function integrationsInstallers(grid, card, pill) {
  const APPT = '/tmf-api/appointment/v4';
  const { c: instC, head: instHead } = card('Installers', 'Calendar & roster');
  grid.append(instC);
  const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
  async function loadInstallers() {
    [...instC.querySelectorAll('[data-inst-body]')].forEach((n) => n.remove());
    instHead.querySelector('[data-inst-pill]')?.remove();
    const cfgRes = await authFetch(`${APPT}/scheduleConfig`);
    const cfg = cfgRes.ok ? await cfgRes.json() : null;
    const techRes = await authFetch(`${APPT}/technician`);
    const techs = techRes.ok ? await techRes.json() : [];
    const p = pill(cfg ? (cfg.capacityMode === 'roster' ? `${cfg.rosterSize} on roster` : `flat ×${cfg.defaultCapacity}`) : 'n/a', cfg?.capacityMode === 'roster');
    p.dataset.instPill = '1';
    instHead.append(p);

    const wrap = document.createElement('div');
    wrap.dataset.instBody = '1';
    wrap.style.cssText = 'margin-top:0.7rem;display:flex;flex-direction:column;gap:0.5rem;font-size:0.9rem';
    if (!cfg) {
      wrap.innerHTML = '<span class="dimhint">calendar unavailable (needs appointment:admin)</span>';
      instC.append(wrap);
      return;
    }
    // the calendar: where the customer's install windows come from
    const cal = document.createElement('div');
    cal.innerHTML = `<b>${esc(cfg.timezone)}</b> · ${esc(cfg.workingDays.join(' '))} · windows ${esc(cfg.slotStarts.join(', '))} (${esc(cfg.slotHours)}h) · ${esc(cfg.daysAhead)} days ahead · `
      + (cfg.capacityMode === 'provider' ? `calendar answered by <b>${esc(cfg.provider)}</b> at ${esc(cfg.providerUrl || '?')}`
        : cfg.capacityMode === 'roster' ? `capacity from <b>${esc(cfg.rosterSize)}</b> active technician${cfg.rosterSize === 1 ? '' : 's'}` : `flat capacity <b>${esc(cfg.defaultCapacity)}</b> per window (no roster yet)`);
    wrap.append(cal);

    // the field-service seam: who answers "when can an installer come?"
    const prov = document.createElement('div');
    prov.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:0.35rem;align-items:center;border-top:1px solid var(--line,#eee);padding-top:0.5rem';
    const provSel = document.createElement('select');
    [['roster', 'Built-in roster (below)'], ['tmf646', 'Own workforce system (TMF646)']].forEach(([v, l]) => provSel.append(new Option(l, v)));
    provSel.value = cfg.provider || 'roster';
    const provUrl = document.createElement('input'); provUrl.value = cfg.providerUrl || ''; provUrl.placeholder = 'TMF646 base url, e.g. http://mock-fsm:8080'; provUrl.style.cssText = 'padding:0.3rem 0.4rem';
    const provRef = document.createElement('input'); provRef.value = cfg.providerSecretRef || ''; provRef.placeholder = 'secret env var name (optional)'; provRef.style.cssText = 'padding:0.3rem 0.4rem';
    const provCat = document.createElement('input'); provCat.value = cfg.providerCategory || ''; provCat.placeholder = 'capacity category, e.g. fibre-install'; provCat.style.cssText = 'padding:0.3rem 0.4rem';
    const provRow = document.createElement('div'); provRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center;grid-column:1/3';
    const provSave = document.createElement('button'); provSave.textContent = 'Save provider'; provSave.style.cssText = 'padding:0.3rem 0.9rem';
    const provTest = document.createElement('button'); provTest.textContent = 'Test'; provTest.style.cssText = 'padding:0.3rem 0.9rem';
    const provMsg = document.createElement('span'); provMsg.className = 'dimhint';
    provRow.append(provSave, provTest, provMsg);
    prov.append(provSel, provUrl, provRef, provCat, provRow);
    wrap.append(prov);
    provSave.addEventListener('click', async () => {
      provMsg.textContent = 'saving…';
      const dto = { provider: provSel.value, providerUrl: provUrl.value.trim() || null, providerSecretRef: provRef.value.trim() || null, providerCategory: provCat.value.trim() || null };
      const r = await authFetch(`${APPT}/scheduleConfig`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) });
      const body = r.ok ? null : await r.json().catch(() => ({}));
      provMsg.textContent = r.ok ? 'saved' : (r.status === 403 ? 'not authorized (needs appointment:admin)' : (body?.message || 'failed (' + r.status + ')'));
      if (r.ok) loadInstallers();
    });
    provTest.addEventListener('click', async () => {
      provTest.textContent = '…';
      const r = await authFetch(`${APPT}/scheduleConfig/test`, { method: 'POST' });
      const body = r.ok ? await r.json() : { ok: false, detail: 'HTTP ' + r.status };
      provTest.textContent = body.ok ? '✓ reachable' : '✗ unreachable';
      provMsg.textContent = body.detail || '';
      setTimeout(() => { provTest.textContent = 'Test'; }, 5000);
    });
    const calForm = document.createElement('div');
    calForm.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:0.35rem;align-items:center';
    const tz = document.createElement('input'); tz.value = cfg.timezone; tz.placeholder = 'IANA timezone'; tz.style.cssText = 'padding:0.3rem 0.4rem';
    const starts = document.createElement('input'); starts.value = cfg.slotStarts.join(','); starts.placeholder = 'window starts HH:mm,…'; starts.style.cssText = 'padding:0.3rem 0.4rem';
    const dayBox = document.createElement('div'); dayBox.style.cssText = 'display:flex;gap:0.4rem;flex-wrap:wrap;grid-column:1/3';
    const dayChecks = DAYS.map((d) => {
      const l = document.createElement('label'); l.style.cssText = 'font-size:0.8rem;display:flex;gap:0.2rem;align-items:center';
      const c = document.createElement('input'); c.type = 'checkbox'; c.checked = cfg.workingDays.includes(d); c.dataset.day = d;
      l.append(c, document.createTextNode(d)); dayBox.append(l); return c;
    });
    const hours = document.createElement('input'); hours.type = 'number'; hours.value = cfg.slotHours; hours.min = 1; hours.max = 12; hours.title = 'window length (h)'; hours.style.cssText = 'padding:0.3rem 0.4rem';
    const ahead = document.createElement('input'); ahead.type = 'number'; ahead.value = cfg.daysAhead; ahead.min = 1; ahead.max = 90; ahead.title = 'days ahead'; ahead.style.cssText = 'padding:0.3rem 0.4rem';
    const cap = document.createElement('input'); cap.type = 'number'; cap.value = cfg.defaultCapacity; cap.min = 0; cap.title = 'flat capacity when no roster'; cap.style.cssText = 'padding:0.3rem 0.4rem';
    const saveRow = document.createElement('div'); saveRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center';
    const save = document.createElement('button'); save.textContent = 'Save calendar'; save.style.cssText = 'padding:0.3rem 0.9rem';
    const calMsg = document.createElement('span'); calMsg.className = 'dimhint';
    saveRow.append(save, calMsg);
    calForm.append(tz, starts, dayBox, hours, ahead, cap, saveRow);
    wrap.append(calForm);
    save.addEventListener('click', async () => {
      calMsg.textContent = 'saving…';
      const dto = { timezone: tz.value.trim(), slotStarts: starts.value.split(',').map((x) => x.trim()).filter(Boolean),
        workingDays: dayChecks.filter((c) => c.checked).map((c) => c.dataset.day),
        slotHours: Number(hours.value), daysAhead: Number(ahead.value), defaultCapacity: Number(cap.value) };
      const r = await authFetch(`${APPT}/scheduleConfig`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) });
      calMsg.textContent = r.ok ? 'saved' : (r.status === 403 ? 'not authorized (needs appointment:admin)' : 'failed (' + r.status + ')');
      if (r.ok) loadInstallers();
    });

    // the roster: who works when — each active technician is one visit per window they cover
    const list = document.createElement('div');
    list.style.cssText = 'display:flex;flex-direction:column;gap:0.35rem;border-top:1px solid var(--line,#eee);padding-top:0.5rem';
    if (!techs.length) {
      list.innerHTML = '<span class="dimhint">No technicians yet — windows use the flat capacity above. Add one and capacity follows the roster.</span>';
    }
    for (const t of techs) {
      const line = document.createElement('div');
      line.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:0.5rem';
      const label = document.createElement('span');
      label.innerHTML = `<b>${esc(t.name)}</b>${t.zone ? ' · ' + esc(t.zone) : ''} · ${esc(t.workingDays.join(' '))} ${esc(t.startTime)}–${esc(t.endTime)}${(t.skills || []).length ? ' · ' + esc(t.skills.join('/')) : ''}${t.active ? '' : ' · <i>off duty</i>'}`;
      const tog = document.createElement('button');
      tog.textContent = t.active ? 'Off duty' : 'On duty';
      tog.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
      tog.addEventListener('click', async () => {
        await authFetch(`${APPT}/technician/${t.id}`, { method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active: !t.active }) });
        loadInstallers();
      });
      const del = document.createElement('button');
      del.textContent = 'Remove';
      del.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
      del.addEventListener('click', async () => { await authFetch(`${APPT}/technician/${t.id}`, { method: 'DELETE' }); loadInstallers(); });
      line.append(label, tog, del);
      list.append(line);
    }
    wrap.append(list);
    const form = document.createElement('div');
    form.style.cssText = 'display:grid;grid-template-columns:1fr 1fr;gap:0.35rem;align-items:center;border-top:1px solid var(--line,#eee);padding-top:0.5rem';
    const name = document.createElement('input'); name.placeholder = 'technician name'; name.style.cssText = 'padding:0.3rem 0.4rem';
    const zone = document.createElement('input'); zone.placeholder = 'zone (optional)'; zone.style.cssText = 'padding:0.3rem 0.4rem';
    const from = document.createElement('input'); from.value = '08:00'; from.placeholder = 'from HH:mm'; from.style.cssText = 'padding:0.3rem 0.4rem';
    const to = document.createElement('input'); to.value = '17:00'; to.placeholder = 'to HH:mm'; to.style.cssText = 'padding:0.3rem 0.4rem';
    const tDayBox = document.createElement('div'); tDayBox.style.cssText = 'display:flex;gap:0.4rem;flex-wrap:wrap;grid-column:1/3';
    const tDayChecks = DAYS.map((d) => {
      const l = document.createElement('label'); l.style.cssText = 'font-size:0.8rem;display:flex;gap:0.2rem;align-items:center';
      const c = document.createElement('input'); c.type = 'checkbox'; c.checked = cfg.workingDays.includes(d); c.dataset.day = d;
      l.append(c, document.createTextNode(d)); tDayBox.append(l); return c;
    });
    const addRow = document.createElement('div'); addRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center;grid-column:1/3';
    const add = document.createElement('button'); add.textContent = 'Add technician'; add.style.cssText = 'padding:0.3rem 0.9rem';
    const msg = document.createElement('span'); msg.className = 'dimhint';
    addRow.append(add, msg);
    form.append(name, zone, from, to, tDayBox, addRow);
    wrap.append(form);
    add.addEventListener('click', async () => {
      if (!name.value.trim()) { msg.textContent = 'name required'; return; }
      msg.textContent = 'saving…';
      const dto = { name: name.value.trim(), zone: zone.value.trim() || null, startTime: from.value.trim(), endTime: to.value.trim(),
        workingDays: tDayChecks.filter((c) => c.checked).map((c) => c.dataset.day) };
      const r = await authFetch(`${APPT}/technician`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) });
      msg.textContent = r.ok ? 'added' : (r.status === 403 ? 'not authorized (needs appointment:admin)' : 'failed (' + r.status + ')');
      if (r.ok) loadInstallers();
    });
    instC.append(wrap);
  }
  await loadInstallers();
}

// --- Network priority: which lines ride a slice right now (boost passes / tiers) ---
async function integrationsSlices(grid, card, pill) {
  const SVC_INV = '/tmf-api/serviceInventory/v4';
  const { c: sliceC, head: sliceHead } = card('Network priority', '5G slice seam');
  grid.append(sliceC);
  async function loadSlices() {
    const tenantCfg = window.BSS_CONSOLE_CONFIG || {};
    [...sliceC.querySelectorAll('[data-slice-body]')].forEach((n) => n.remove());
    sliceHead.querySelector('[data-slice-pill]')?.remove();
    const res = await authFetch(`${SVC_INV}/service?limit=100`);
    const all = res.ok ? await res.json() : [];
    const charsOf = (sv) => Object.fromEntries((sv.serviceCharacteristic || []).map((c) => [c.name, c.value]));
    const boosted = all.filter((sv) => sv.state === 'active' && charsOf(sv).sliceProfile && charsOf(sv).sliceProfile !== 'default');
    const p = pill(boosted.length ? `${boosted.length} line${boosted.length === 1 ? '' : 's'} on priority` : 'best effort', boosted.length > 0);
    p.dataset.slicePill = '1';
    sliceHead.append(p);
    const wrap = document.createElement('div');
    wrap.dataset.sliceBody = '1';
    wrap.style.cssText = 'margin-top:0.7rem;display:flex;flex-direction:column;gap:0.4rem;font-size:0.9rem';
    const intro = document.createElement('div');
    intro.innerHTML = 'The core decides the slice (PCF/NSSF or a vendor slice manager); the OCS only rates it. '
      + 'A plan or a top-up with <code>sliceProfile</code> on its spec puts a line on priority — a <code>boostHours</code> pass lapses on its own clock.';
    wrap.append(intro);
    if (!boosted.length) {
      const none = document.createElement('div'); none.className = 'dimhint'; none.textContent = 'No line rides a priority slice at the moment.'; wrap.append(none);
    }
    for (const sv of boosted.slice(0, 25)) {
      const ch = charsOf(sv);
      const line = document.createElement('div');
      line.style.cssText = 'display:flex;justify-content:space-between;gap:0.5rem';
      const who = (sv.relatedParty || []).find((r) => r.role === 'customer')?.id || '';
      line.innerHTML = `<span><b>${esc((sv.supportingResource || [{}])[0].value || sv.name)}</b> · ${esc(sv.name)}${who ? ' · ' + esc(who.slice(0, 8)) : ''}</span>`
        + `<span>⚡ ${esc(ch.sliceProfile)}${ch.sliceUntil ? ' · until ' + esc(new Date(ch.sliceUntil).toLocaleString(undefined, { weekday: 'short', hour: '2-digit', minute: '2-digit', ...(tenantCfg.timezone ? { timeZone: tenantCfg.timezone } : {}) })) : ' · open-ended'}</span>`;
      wrap.append(line);
    }
    sliceC.append(wrap);
  }
  await loadSlices();
}

// --- Payment / PSP: LIVE, per-tenant menu ---
async function integrationsPayment(grid, card, pill) {
  const PAY = '/tmf-api/paymentManagement/v4';
  const { c: payC, head: payHead } = card('Payment', 'PSP seam');
  grid.append(payC);
  async function loadPay() {
    [...payC.querySelectorAll('[data-pay-body]')].forEach((n) => n.remove());
    payHead.querySelector('[data-pay-pill]')?.remove();
    const res = await authFetch(`${PAY}/paymentProvider`);
    const psps = res.ok ? await res.json() : [];
    const p = pill(psps.length ? `${psps.length} provider${psps.length > 1 ? 's' : ''}` : 'built-in', psps.length > 0);
    p.dataset.payPill = '1';
    payHead.append(p);
    const wrap = document.createElement('div');
    wrap.dataset.payBody = '1';
    wrap.style.cssText = 'margin-top:0.7rem;display:flex;flex-direction:column;gap:0.5rem';
    if (!psps.length) {
      const none = document.createElement('div');
      none.style.fontSize = '0.9rem';
      none.innerHTML = 'Built-in default (deploy-configured). Add a PSP to override per tenant.';
      wrap.append(none);
    } else {
      for (const ps of psps) {
        const line = document.createElement('div');
        line.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:0.5rem;font-size:0.9rem';
        const label = document.createElement('span');
        // routing facts on the line: priority orders the card pool, currencies scope it
        const currHtml = ps.currencies ? ` · ${esc(String(ps.currencies).replace(/[\[\]"]/g, ''))}` : '';
        const prioHtml = ps.priority != null && ps.priority !== 100 ? ` · prio ${esc(ps.priority)}` : '';
        label.innerHTML = `<b>${esc(ps.displayName || ps.provider)}</b>${ps.isDefault ? ' · default' : ''}${prioHtml}${currHtml}${ps.enabled === false ? ' · off' : ''}`;
        // Test = reachability probe of the PSP's /health — never a payment
        const tst = document.createElement('button');
        tst.textContent = 'Test';
        tst.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
        tst.addEventListener('click', async () => {
          tst.textContent = '…';
          const r = await authFetch(`${PAY}/paymentProvider/${ps.provider}/test`, { method: 'POST' });
          const body = r.ok ? await r.json() : { ok: false };
          tst.textContent = body.ok ? '✓ reachable' : '✗ unreachable';
          setTimeout(() => { tst.textContent = 'Test'; }, 4000);
        });
        const del = document.createElement('button');
        del.textContent = 'Remove';
        del.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
        del.addEventListener('click', async () => { await authFetch(`${PAY}/paymentProvider/${ps.provider}`, { method: 'DELETE' }); loadPay(); });
        line.append(label, tst, del);
        wrap.append(line);
      }
    }
    const form = document.createElement('div');
    form.style.cssText = 'display:flex;flex-direction:column;gap:0.35rem;border-top:1px solid var(--line,#eee);padding-top:0.5rem;margin-top:0.2rem';
    const sel = document.createElement('select');
    [['mock', 'Mock (dev)'], ['mockbank', 'MockBank (dev backup)'], ['stripe', 'Stripe'], ['klarna', 'Klarna'], ['paypal', 'PayPal']].forEach(([v, l]) => sel.append(new Option(l, v)));
    const base = document.createElement('input'); base.placeholder = 'PSP API base url (optional)'; base.style.cssText = 'padding:0.3rem 0.4rem';
    const sref = document.createElement('input'); sref.placeholder = 'secret-ref (env var name)'; sref.style.cssText = 'padding:0.3rem 0.4rem';
    // orchestration routing: lower priority tried first; currencies scope a card
    // provider to markets (blank = any) — see suite #96
    const routeRow = document.createElement('div'); routeRow.style.cssText = 'display:flex;gap:0.5rem';
    const prio = document.createElement('input'); prio.type = 'number'; prio.placeholder = 'priority (100)';
    prio.title = 'Routing order among card providers — lower is tried first'; prio.style.cssText = 'padding:0.3rem 0.4rem;width:8.5rem';
    const currs = document.createElement('input'); currs.placeholder = 'currencies e.g. USD,NOK (blank = any)';
    currs.title = 'Card charges in these currencies route here'; currs.style.cssText = 'padding:0.3rem 0.4rem;flex:1';
    routeRow.append(prio, currs);
    const defWrap = document.createElement('label'); defWrap.style.cssText = 'font-size:0.85rem;display:flex;gap:0.3rem;align-items:center';
    const defC = document.createElement('input'); defC.type = 'checkbox'; defWrap.append(defC, document.createTextNode('default provider'));
    const addRow = document.createElement('div'); addRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center';
    const add = document.createElement('button'); add.textContent = 'Add / update'; add.style.cssText = 'padding:0.3rem 0.9rem';
    const msg = document.createElement('span'); msg.className = 'dimhint';
    addRow.append(add, msg);
    form.append(sel, base, sref, routeRow, defWrap, addRow);
    wrap.append(form);
    add.addEventListener('click', async () => {
      msg.textContent = 'saving…';
      const provider = sel.value;
      const dto = { provider, displayName: sel.options[sel.selectedIndex].text, baseUrl: base.value || null,
        secretRef: sref.value || null,
        methods: (provider === 'klarna' || provider === 'paypal') ? ['card', provider] : ['card'], isDefault: defC.checked };
      if (prio.value) dto.priority = Number(prio.value);
      if (currs.value.trim()) dto.currencies = currs.value.split(',').map((x) => x.trim().toUpperCase()).filter(Boolean);
      const r = await authFetch(`${PAY}/paymentProvider`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) });
      msg.textContent = r.ok ? 'saved' : (r.status === 403 ? 'not authorized' : 'failed (' + r.status + ')');
      if (r.ok) loadPay();
    });
    payC.append(wrap);
  }
  await loadPay();
}
