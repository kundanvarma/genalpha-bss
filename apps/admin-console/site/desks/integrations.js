'use strict';

// INTEGRATIONS — the provider catalog over the platform seams (P1: CMS live,
// the rest shown as built-in/deploy-configured).
async function renderIntegrations() {
  const panel = panelFor('integrations-panel');

  const intro = document.createElement('p');
  intro.className = 'dimhint';
  intro.textContent = 'Providers behind the platform seams. Built-in adapters ship configured at deploy; the content seam is per-tenant configurable here (the pattern the other seams follow next).';
  panel.append(intro);
  const grid = document.createElement('div');
  grid.style.cssText = 'display:flex;flex-wrap:wrap;gap:0.9rem;margin-top:0.8rem;align-items:flex-start';
  panel.append(grid);

  const pill = (text, ok) => {
    const s = document.createElement('span');
    s.textContent = text;
    s.style.cssText = 'font-size:0.72rem;padding:0.1rem 0.5rem;border-radius:999px;'
      + (ok ? 'background:#e6f4ea;color:#1a7f37' : 'background:#eef1f3;color:#5a6b73');
    return s;
  };
  const card = (title, sub) => {
    const c = document.createElement('div');
    c.style.cssText = 'flex:1 1 17rem;min-width:16rem;max-width:22rem;padding:1rem;border:1px solid var(--line,#ddd);border-radius:12px;background:var(--card,#fafafa)';
    const head = document.createElement('div');
    head.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:0.5rem';
    const t = document.createElement('b'); t.textContent = title;
    head.append(t);
    const s = document.createElement('div'); s.className = 'dimhint'; s.textContent = sub;
    s.style.marginTop = '0.15rem';
    c.append(head, s);
    return { c, head };
  };
  const builtin = (title, sub, provider) => {
    const { c, head } = card(title, sub);
    head.append(pill('built-in', false));
    const p = document.createElement('div');
    p.style.cssText = 'margin-top:0.6rem;font-size:0.9rem';
    p.textContent = provider;
    c.append(p);
    grid.append(c);
  };

  await integrationsContent(grid, card, pill);
  await integrationsLogistics(grid, card, pill);
  await integrationsInstallers(grid, card, pill);
  await integrationsSlices(grid, card, pill);
  await integrationsPayment(grid, card, pill);

  builtin('Charging', 'OCS seam', 'Online charging endpoint — configured at deploy.');
  builtin('AI models', 'LLM seam', 'Per-tenant provider + model routing (fast/smart tiers).');
}

// --- CMS / DAM: LIVE, per-tenant configurable ---
async function integrationsContent(grid, card, pill) {
  const CONTENT = '/tmf-api/documentManagement/v4';
  const { c: cmsC, head: cmsHead } = card('Content / DAM', 'TMF667 content seam');
  grid.append(cmsC);
  async function loadCms() {
    // strip everything after the sub/head + status
    [...cmsC.querySelectorAll('[data-cms-body]')].forEach((n) => n.remove());
    cmsHead.querySelector('[data-cms-pill]')?.remove();
    const res = await authFetch(`${CONTENT}/contentProvider`);
    const bound = res.ok ? await res.json() : null;
    const label = !bound ? 'Built-in DAM (hosted)'
      : (bound.provider === 'sanity' ? 'Sanity' : bound.provider === 'http' ? 'Generic HTTP CMS' : bound.provider);
    const p = pill(bound ? 'external' : 'hosted', !!bound); p.dataset.cmsPill = '1';
    cmsHead.append(p);

    const wrap = document.createElement('div');
    wrap.dataset.cmsBody = '1';
    wrap.style.cssText = 'margin-top:0.7rem;display:flex;flex-direction:column;gap:0.5rem';
    const now = document.createElement('div');
    now.style.fontSize = '0.9rem';
    now.innerHTML = 'Serving from: <b>' + label + '</b>';
    wrap.append(now);

    const sel = document.createElement('select');
    ['Built-in DAM (hosted)', 'Sanity', 'Generic HTTP CMS'].forEach((o, i) => sel.append(new Option(o, ['hosted', 'sanity', 'http'][i])));
    sel.value = bound ? bound.provider : 'hosted';
    wrap.append(sel);

    const fields = document.createElement('div');
    fields.style.cssText = 'display:flex;flex-direction:column;gap:0.35rem';
    wrap.append(fields);
    const input = (ph, val) => { const i = document.createElement('input'); i.placeholder = ph; if (val) i.value = val; i.style.cssText = 'padding:0.3rem 0.4rem'; return i; };
    const ta = document.createElement('textarea'); ta.rows = 4; ta.placeholder = 'connector config JSON'; ta.style.cssText = 'padding:0.3rem 0.4rem;font-family:monospace;font-size:0.8rem';
    const f = {};
    function syncFields() {
      fields.replaceChildren();
      if (sel.value === 'sanity') {
        f.baseUrl = input('baseUrl (blank = real Sanity)', bound && bound.baseUrl);
        f.projectId = input('projectId', bound && bound.projectId);
        f.dataset = input('dataset (e.g. production)', bound && bound.dataset);
        f.secretRef = input('secretRef (env var name for the token)', bound && bound.secretRef);
        fields.append(f.baseUrl, f.projectId, f.dataset, f.secretRef);
      } else if (sel.value === 'http') {
        f.secretRef = input('secretRef (env var name, optional)', bound && bound.secretRef);
        if (bound && bound.provider === 'http') { /* config not echoed back; leave for edit */ }
        ta.value = ta.value || '{\n  "uploadUrl": "",\n  "uploadMode": "multipart",\n  "fileField": "files",\n  "assetIdPath": "/0/url",\n  "resolveBase": "",\n  "renditionMode": "none"\n}';
        fields.append(f.secretRef, ta);
      }
    }
    sel.addEventListener('change', syncFields);
    syncFields();

    const row = document.createElement('div');
    row.style.cssText = 'display:flex;gap:0.5rem;align-items:center';
    const apply = document.createElement('button'); apply.textContent = 'Apply'; apply.style.cssText = 'padding:0.3rem 0.9rem';
    const msg = document.createElement('span'); msg.className = 'dimhint';
    row.append(apply, msg);
    wrap.append(row);
    cmsC.append(wrap);

    apply.addEventListener('click', async () => {
      msg.textContent = 'saving…';
      try {
        let r;
        if (sel.value === 'hosted') {
          r = await authFetch(`${CONTENT}/contentProvider`, { method: 'DELETE' });
        } else {
          const dto = { provider: sel.value };
          if (sel.value === 'sanity') {
            dto.baseUrl = f.baseUrl.value || null; dto.projectId = f.projectId.value;
            dto.dataset = f.dataset.value || 'production'; dto.secretRef = f.secretRef.value || null;
          } else {
            dto.secretRef = f.secretRef.value || null;
            dto.config = JSON.parse(ta.value || '{}');
          }
          r = await authFetch(`${CONTENT}/contentProvider`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto),
          });
        }
        msg.textContent = r.ok ? 'saved' : (r.status === 403 ? 'not authorized (needs document:write)' : 'failed (' + r.status + ')');
        if (r.ok) loadCms();
      } catch (e) {
        msg.textContent = 'invalid config JSON';
      }
    });
  }
  await loadCms();
}

// --- Logistics / carriers: LIVE, per-tenant menu ---
async function integrationsLogistics(grid, card, pill) {
  const SHIP = '/tmf-api/shippingOrderManagement/v4';
  const { c: logiC, head: logiHead } = card('Logistics', 'Carrier seam');
  grid.append(logiC);
  async function loadLogi() {
    [...logiC.querySelectorAll('[data-logi-body]')].forEach((n) => n.remove());
    logiHead.querySelector('[data-logi-pill]')?.remove();
    const res = await authFetch(`${SHIP}/carrier`);
    const carriers = res.ok ? await res.json() : [];
    const p = pill(carriers.length ? `${carriers.length} carrier${carriers.length > 1 ? 's' : ''}` : 'built-in', carriers.length > 0);
    p.dataset.logiPill = '1';
    logiHead.append(p);

    const wrap = document.createElement('div');
    wrap.dataset.logiBody = '1';
    wrap.style.cssText = 'margin-top:0.7rem;display:flex;flex-direction:column;gap:0.5rem';
    if (!carriers.length) {
      const none = document.createElement('div');
      none.style.fontSize = '0.9rem';
      none.innerHTML = 'Built-in default: <b>Helthjem</b> (deploy-configured). Add a carrier to override per tenant.';
      wrap.append(none);
    } else {
      for (const cr of carriers) {
        const line = document.createElement('div');
        line.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:0.5rem;font-size:0.9rem';
        const label = document.createElement('span');
        label.innerHTML = `<b>${cr.displayName || cr.carrier}</b>${cr.postcodePrefix ? ' · ' + cr.postcodePrefix + '∗' : ''}${cr.isDefault ? ' · default' : ''}${cr.enabled === false ? ' · off' : ''}`;
        // Test = reachability probe of the provider's /health — never a booking
        const tst = document.createElement('button');
        tst.textContent = 'Test';
        tst.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
        tst.addEventListener('click', async () => {
          tst.textContent = '…';
          const r = await authFetch(`${SHIP}/carrier/${cr.carrier}/test`, { method: 'POST' });
          const body = r.ok ? await r.json() : { ok: false };
          tst.textContent = body.ok ? '✓ reachable' : '✗ unreachable';
          setTimeout(() => { tst.textContent = 'Test'; }, 4000);
        });
        const del = document.createElement('button');
        del.textContent = 'Remove';
        del.style.cssText = 'padding:0.2rem 0.6rem;font-size:0.8rem';
        del.addEventListener('click', async () => { await authFetch(`${SHIP}/carrier/${cr.carrier}`, { method: 'DELETE' }); loadLogi(); });
        line.append(label, tst, del);
        wrap.append(line);
      }
    }
    const form = document.createElement('div');
    form.style.cssText = 'display:flex;flex-direction:column;gap:0.35rem;border-top:1px solid var(--line,#eee);padding-top:0.5rem;margin-top:0.2rem';
    const sel = document.createElement('select');
    [['helthjem', 'Helthjem'], ['bring', 'Posten/Bring'], ['postnord', 'PostNord']].forEach(([v, l]) => sel.append(new Option(l, v)));
    const base = document.createElement('input'); base.placeholder = 'carrier API base url'; base.style.cssText = 'padding:0.3rem 0.4rem';
    const prefix = document.createElement('input'); prefix.placeholder = 'postcode prefix (optional, e.g. 0)'; prefix.style.cssText = 'padding:0.3rem 0.4rem';
    const defWrap = document.createElement('label'); defWrap.style.cssText = 'font-size:0.85rem;display:flex;gap:0.3rem;align-items:center';
    const defC = document.createElement('input'); defC.type = 'checkbox'; defWrap.append(defC, document.createTextNode('default carrier'));
    const addRow = document.createElement('div'); addRow.style.cssText = 'display:flex;gap:0.5rem;align-items:center';
    const add = document.createElement('button'); add.textContent = 'Add / update'; add.style.cssText = 'padding:0.3rem 0.9rem';
    const msg = document.createElement('span'); msg.className = 'dimhint';
    addRow.append(add, msg);
    form.append(sel, base, prefix, defWrap, addRow);
    wrap.append(form);
    add.addEventListener('click', async () => {
      msg.textContent = 'saving…';
      const carrier = sel.value;
      const dto = { carrier, displayName: sel.options[sel.selectedIndex].text, baseUrl: base.value || null,
        methods: (carrier === 'bring' || carrier === 'postnord') ? ['home', 'pickupPoint'] : ['home'],
        postcodePrefix: prefix.value || null, isDefault: defC.checked };
      const r = await authFetch(`${SHIP}/carrier`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) });
      msg.textContent = r.ok ? 'saved' : (r.status === 403 ? 'not authorized (needs ordering:write)' : 'failed (' + r.status + ')');
      if (r.ok) loadLogi();
    });
    logiC.append(wrap);
  }
  await loadLogi();
}
