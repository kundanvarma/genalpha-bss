'use strict';

/* ---------------- Device entitlements: what each line's phone may use (GSMA TS.43 entitlement server) ---------------- */
const ENTITLEMENT_BASE = '/tmf-api/deviceEntitlement/v1';
// outcomes in words — the request list is read by ops staff, not by the phone
const ECS_OUTCOME_WORDS = {
  served: 'answered', 'eap-challenge': 'SIM challenge sent', 'eap-failed': 'SIM challenge failed', forbidden: 'refused',
  unauthenticated: 'not signed in', 'bad-request': 'could not be understood', notified: 'told to refresh',
};
const ecsOutcomeWords = (o) => ECS_OUTCOME_WORDS[o] || String(o || '');
const ecsRefused = (o) => o === 'forbidden' || o === 'eap-failed' || o === 'unauthenticated' || o === 'bad-request';
// TS.43 application ids, when the server reports them raw; a human name passes through untouched
const ECS_APP_WORDS = {
  ap2003: 'VoLTE', ap2004: 'Wi-Fi calling', ap2005: 'SMS over IP', ap2006: 'companion eSIM', ap2009: 'eSIM for this phone',
  ap2010: 'data plan', ap2011: 'refresh notice', ap2012: 'carrier billing', ap2013: 'private identity', ap2014: 'phone number', ap2015: 'satellite',
};
const ecsAppWords = (a) => ECS_APP_WORDS[String(a || '').toLowerCase()] || String(a || '');
const LINE_STATUS_WORDS = { active: 'active', suspended: 'suspended', terminated: 'closed', pending: 'being set up', barred: 'barred' };
const lineStatusWords = (s) => LINE_STATUS_WORDS[String(s || '').toLowerCase()] || String(s || 'unknown').toLowerCase();
const isToday = (s) => { const d = new Date(s); return !Number.isNaN(d.getTime()) && d.toDateString() === new Date().toDateString(); };
const lineName = (s) => s.msisdn || s.imsi || '';

async function loadEntitlementRequests(limit) {
  const rows = await authFetch(`${ENTITLEMENT_BASE}/ecsRequest`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
  const list = Array.isArray(rows) ? rows : [];
  list.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
  return limit ? list.slice(0, limit) : list;
}

async function renderDeviceEntitlements() {
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  closeSideDrawer();
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'entitlements-pane';
  const roles = (tokenClaims().realm_access || {}).roles || [];
  const mayWrite = roles.includes('entitlement:write');

  const [subscribers, requests] = await Promise.all([
    authFetch(`${ENTITLEMENT_BASE}/subscriber`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    loadEntitlementRequests(),
  ]);
  const subs = Array.isArray(subscribers) ? subscribers : [];
  const nameOf = {}; for (const s of subs) if (s.imsi) nameOf[s.imsi] = lineName(s);
  const details = new Map(); // imsi → detail, fetched lazily (the plan name lives there)
  const detailOf = async (imsi) => {
    if (details.has(imsi)) return details.get(imsi);
    const d = await authFetch(`${ENTITLEMENT_BASE}/subscriber/${encodeURIComponent(imsi)}`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    details.set(imsi, d);
    return d;
  };

  const serviceTone = (v) => {
    const s = String(v || '').toLowerCase();
    if (/still needed|being set up/.test(s)) return 'warn';
    if (s === 'on' || s === 'allowed' || s === 'unmetered' || s === 'metered') return 'ok';
    return '';
  };

  const requestRow = (r, withLine) => {
    const row = document.createElement('div'); row.className = 'decision-row'; row.dataset.testid = 'ecs-row'; row.style.cursor = 'default';
    const t = document.createElement('span'); t.className = 'dim when'; t.textContent = when(r.createdAt); t.title = clock(r.createdAt);
    const s = document.createElement('span'); s.className = 'what';
    const who = withLine ? `Line ${nameOf[r.imsi] || r.imsi || 'unknown'}` : 'This phone';
    s.textContent = `${who} asked for ${ecsAppWords(r.app)}${r.operation ? ` (${r.operation})` : ''}${r.detail ? ` — ${r.detail}` : ''}`;
    const pill = document.createElement('span'); pill.className = 'pill'; pill.textContent = ecsOutcomeWords(r.outcome);
    if (ecsRefused(r.outcome)) pill.classList.add('warn'); else if (r.outcome === 'served' || r.outcome === 'notified') pill.classList.add('ok');
    row.append(t, s, pill);
    return row;
  };
  const section = (title) => { const h = document.createElement('div'); h.className = 'q-title'; h.style.cssText = 'font-weight:600;font-size:13.5px;margin:14px 0 6px'; h.textContent = title; return h; };

  const openLine = async (s) => {
    const body = document.createElement('div'); body.dataset.testid = 'entitlement-detail';
    const lead = document.createElement('p'); lead.className = 'dim'; lead.style.margin = '0 0 4px'; lead.textContent = 'Reading the line…';
    body.append(lead);
    openSideDrawer(`Line ${lineName(s)}`, body);
    const d = await detailOf(s.imsi);
    if (!d) { lead.textContent = 'The entitlement server did not answer for this line.'; return; }
    const e = d.entitlements || {};
    const facts = [];
    facts.push(e.plan ? `On the ${e.plan} plan` : 'Plan on file');
    facts.push(`line ${lineStatusWords(e.lineStatus || d.status)}`);
    facts.push(d.imsProvisioned ? 'provisioned on IMS' : 'not on IMS yet');
    facts.push(d.emergencyAddressConfirmed ? 'emergency address confirmed' : 'emergency address still needed');
    facts.push(d.termsAccepted ? 'terms accepted' : 'terms not accepted yet');
    lead.textContent = `${facts.join(' · ')}.${d.lastUpdate ? ` Last changed ${when(d.lastUpdate)}.` : ''}`;

    const svc = document.createElement('div'); svc.dataset.testid = 'entitlement-services'; svc.className = 'decision-list';
    const services = e.services || {};
    const keys = Object.keys(services);
    if (!keys.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No services decided for this line yet.'; svc.append(p); }
    for (const k of keys) {
      const row = document.createElement('div'); row.className = 'decision-row'; row.style.cssText = 'grid-template-columns:1fr auto;cursor:default';
      const n = document.createElement('span'); n.textContent = k;
      const v = document.createElement('span'); v.className = 'pill'; v.textContent = String(services[k]);
      const tone = serviceTone(services[k]); if (tone) v.classList.add(tone);
      row.append(n, v); svc.append(row);
    }

    const devs = document.createElement('div'); devs.className = 'decision-list';
    const devices = Array.isArray(d.devices) ? d.devices : [];
    if (!devices.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No phone has checked in on this line yet.'; devs.append(p); }
    for (const dv of devices) {
      const row = document.createElement('div'); row.className = 'decision-row'; row.style.cursor = 'default';
      const t = document.createElement('span'); t.className = 'dim when'; t.textContent = dv.lastSeenAt ? when(dv.lastSeenAt) : 'never seen'; if (dv.lastSeenAt) t.title = clock(dv.lastSeenAt);
      const w = document.createElement('span'); w.className = 'what';
      w.textContent = `${[dv.vendor, dv.model].filter(Boolean).join(' ') || 'Unknown phone'}${dv.swVersion ? `, software ${dv.swVersion}` : ''}${dv.lastApps ? ` — last asked about ${String(dv.lastApps).split(/[,\s]+/).filter(Boolean).map(ecsAppWords).join(', ')}` : ''}`;
      const pill = document.createElement('span'); pill.className = 'pill'; pill.textContent = dv.pushRegistered ? 'push registered' : 'no push — cannot be nudged';
      if (dv.pushRegistered) pill.classList.add('ok');
      row.append(t, w, pill); devs.append(row);
    }

    const comps = document.createElement('div'); comps.className = 'decision-list';
    const companions = Array.isArray(d.companions) ? d.companions : [];
    if (!companions.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No watch or tablet shares this line.'; comps.append(p); }
    for (const c of companions) {
      const row = document.createElement('div'); row.className = 'decision-row'; row.style.cursor = 'default';
      const t = document.createElement('span'); t.className = 'dim when'; t.textContent = when(c.lastUpdate); if (c.lastUpdate) t.title = clock(c.lastUpdate);
      const w = document.createElement('span'); w.className = 'what'; w.textContent = `${c.model || 'Companion device'}${c.iccid ? ` — eSIM ending ${String(c.iccid).slice(-4)}` : ''}`;
      const pill = document.createElement('span'); pill.className = 'pill'; pill.textContent = lineStatusWords(c.status);
      if (String(c.status || '').toLowerCase() === 'active') pill.classList.add('ok');
      row.append(t, w, pill); comps.append(row);
    }

    const mine = requests.filter((r) => r.imsi && r.imsi === s.imsi).slice(0, 30);
    const reqs = document.createElement('div'); reqs.className = 'decision-list'; reqs.dataset.testid = 'entitlement-requests';
    if (!mine.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No requests from this line yet.'; reqs.append(p); }
    for (const r of mine) reqs.append(requestRow(r, false));

    const actions = document.createElement('div'); actions.className = 'actions'; actions.style.cssText = 'display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:14px';
    if (mayWrite) {
      const refresh = document.createElement('button'); refresh.className = 'primary'; refresh.textContent = 'Ask the phone to refresh'; refresh.dataset.testid = 'entitlement-refresh';
      const msg = document.createElement('span'); msg.className = 'dim'; msg.dataset.testid = 'entitlement-msg';
      refresh.addEventListener('click', async () => {
        msg.textContent = 'asking…';
        const r = await authFetch(`${ENTITLEMENT_BASE}/subscriber/${encodeURIComponent(s.imsi)}/reconfigure`, { method: 'POST',
          headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({}) });
        msg.textContent = r.ok ? 'The phones on this line will fetch their entitlements again.' : ((await r.json().catch(() => ({}))).message || 'the phone could not be asked');
      });
      actions.append(refresh, msg);
    }

    body.append(section('What the phone may use'), svc, section('Phones that checked in'), devs,
      section('Watches and tablets on this line'), comps, section('Recent requests from this phone'), reqs, actions);
  };

  const lines = document.createElement('div'); lines.className = 'decision-list'; lines.dataset.testid = 'entitlement-list';
  if (!subs.length) {
    const p = document.createElement('p'); p.className = 'dim';
    p.textContent = 'No lines are bound to the entitlement server yet. Lines appear as mobile services go live.';
    lines.append(p);
  }
  const planCells = [];
  for (const s of subs) {
    const row = document.createElement('button'); row.type = 'button'; row.className = 'decision-row'; row.dataset.testid = 'entitlement-row'; row.dataset.imsi = s.imsi || '';
    const t = document.createElement('span'); t.className = 'dim when'; t.textContent = when(s.lastUpdate); if (s.lastUpdate) t.title = clock(s.lastUpdate);
    const w = document.createElement('span'); w.className = 'what';
    const name = document.createElement('strong'); name.textContent = lineName(s);
    const plan = document.createElement('span'); plan.className = 'dim'; plan.textContent = ' · plan on file';
    w.append(name, plan);
    planCells.push([s, plan]);
    const pill = document.createElement('span'); pill.className = 'pill'; pill.textContent = lineStatusWords(s.status);
    if (String(s.status || '').toLowerCase() === 'active') pill.classList.add('ok');
    if (s.imsProvisioned === false) { pill.classList.remove('ok'); pill.classList.add('warn'); pill.textContent += ' · not on IMS yet'; }
    row.append(t, w, pill);
    row.addEventListener('click', () => openLine(s));
    lines.append(row);
  }

  const recent = document.createElement('div'); recent.className = 'decision-list'; recent.dataset.testid = 'ecs-list';
  const last = requests.slice(0, 30);
  if (!last.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No phone has asked yet.'; recent.append(p); }
  for (const r of last) recent.append(requestRow(r, true));

  panel.append(section('Lines'), lines, section('Recent requests from phones'), recent);

  // the plan name lives in the detail — fill it in after the list is on screen, a few lines at a time
  const current = active;
  for (const [s, cell] of planCells.slice(0, 40)) {
    if (active !== current || !s.imsi) break;
    const d = await detailOf(s.imsi);
    const plan = d?.entitlements?.plan;
    if (plan) cell.textContent = ` · ${plan}`;
  }
}
