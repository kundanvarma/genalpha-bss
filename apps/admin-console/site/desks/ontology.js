'use strict';

/* ---------------- What the BSS can do: the Operational Semantic Registry, in words ---------------- */
const ONTOLOGY_BASE = '/ontology/v1';
const ontoTitle = (camel) => camel.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase()).toLowerCase().replace(/^./, (c) => c.toUpperCase());
const ontoWho = (a) => (a.permissions?.anyOf || []).map((c) => (c.self ? `the ${c.self} themselves` : `anyone holding ${c.role}`)).join(', or ');

async function renderOntology() {
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  closeSideDrawer();
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'ontology-pane';
  const get = (p) => authFetch(`${ONTOLOGY_BASE}${p}`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const [overview, actions, concepts, capabilities] = await Promise.all([get(''), get('/actions'), get('/concepts'), get('/capabilities')]);
  if (!overview) {
    const p = document.createElement('p'); p.textContent = 'The registry is not reachable right now.'; panel.append(p); return;
  }
  const capOf = (id) => (capabilities || []).find((c) => c.id === id) || { id, component: '?', meaning: id };
  const head = document.createElement('p'); head.className = 'dim';
  head.textContent = `${overview.concepts} concepts · ${overview.actions} governed action${overview.actions === 1 ? '' : 's'} · ${overview.capabilities} capabilities across ${overview.components} components`
    + (overview.tenantOverlay ? ' · this operator has extended the core with rules of its own' : '') + `. ${overview.rule}`;
  panel.append(head);

  const h = document.createElement('h3'); h.textContent = 'Actions — what can be done, and under which conditions'; panel.append(h);
  for (const a of actions || []) {
    const card = document.createElement('div'); card.dataset.testid = 'ontology-action';
    card.style.cssText = 'border:1px solid var(--line,#e5e5ea);border-radius:10px;padding:.8rem 1rem;margin:0 0 .7rem;display:grid;gap:.35rem';
    const t = document.createElement('div'); t.style.cssText = 'display:flex;align-items:center;gap:.6rem;flex-wrap:wrap';
    const name = document.createElement('strong'); name.className = 'q-title'; name.style.fontSize = '15px'; name.textContent = ontoTitle(a.action);
    const pill = document.createElement('span'); pill.className = 'pill'; pill.style.fontSize = '.75rem';
    pill.textContent = `v${a.version} · ${a.status}${a.tenantExtended ? ' · extended by this operator' : ''}`;
    t.append(name, pill); card.append(t);
    const m = document.createElement('div'); m.textContent = a.meaning; card.append(m);
    const w = document.createElement('div'); w.className = 'dim'; w.textContent = `Who may: ${ontoWho(a)}. Acts on a ${a.concept}. Governance: autonomy ${a.governance?.autonomy}, approval ${a.governance?.approval}, audit ${a.governance?.audit}.`; card.append(w);
    const pcs = document.createElement('details'); const s = document.createElement('summary'); s.textContent = `Before it happens — ${(a.preconditions || []).length} conditions`; pcs.append(s);
    const ul = document.createElement('ul'); ul.style.cssText = 'margin:.3rem 0 0 1.1rem;padding:0;font-size:13px';
    for (const p of a.preconditions || []) { const li = document.createElement('li'); li.textContent = p.says; ul.append(li); }
    pcs.append(ul); card.append(pcs);
    const ex = capOf(a.executes?.capability);
    const e = document.createElement('div'); e.className = 'dim';
    e.textContent = `Executed by ${ex.component} (${ex.tmf || ex.id}).${(a.effects || []).length ? ` What follows: ${a.effects.map((f) => capOf(f.capability).meaning).join(' ')}` : ''}`;
    card.append(e);
    const ev = document.createElement('div'); ev.className = 'dim'; ev.style.fontSize = '12px';
    ev.textContent = `Emits: ${(a.emits || []).map((x) => x.event).join(', ')}.`; card.append(ev);
    if (a.governance?.approval === 'human') {
      const ap = document.createElement('div'); ap.className = 'dim'; ap.style.fontSize = '12px';
      ap.textContent = `Approval: a human holding ${a.governance.approverRole || 'the approver role'} decides`
        + (a.governance.approvalAbove ? ` above ${a.governance.approvalAbove.amount} (${a.governance.approvalAbove.input})` : '')
        + (a.governance.limits ? `; limits ${Object.entries(a.governance.limits).map(([k, v]) => `${k} ${v}`).join(', ')}` : '') + '.';
      card.append(ap);
    }
    if (a.status === 'deprecated') {
      const dp = document.createElement('div'); dp.className = 'dim'; dp.style.fontSize = '12px';
      dp.textContent = `Retired ${a.deprecated || ''}${a.supersededBy ? ` — use ${ontoTitle(a.supersededBy)}` : ''}. Refused past its date; absent from the agents' tools and the SDK.`; card.append(dp);
    }
    // the learning contract for this action's decision point, when the operator has written one
    authFetch(`/tmf-api/campaignManagement/v4/learningContract/ontology.${a.action}`).then((r) => (r.ok ? r.json() : null)).then((lc) => {
      if (!lc || !lc.contract || lc.contract.defaults) return;
      const l = document.createElement('div'); l.className = 'dim'; l.style.fontSize = '12px'; l.dataset.testid = 'ontology-contract';
      l.textContent = `Learning contract v${lc.contract.version}: measured by "${lc.contract.objective}"${(lc.contract.guardrails || []).length ? `; guardrails: ${lc.contract.guardrails.join('; ')}` : ''}.`;
      ev.after(l);
    }).catch(() => {});
    const row = document.createElement('div'); row.style.cssText = 'display:flex;gap:.5rem;flex-wrap:wrap';
    const explain = document.createElement('button'); explain.className = 'ghost small'; explain.textContent = 'Explain the journey'; explain.dataset.testid = 'ontology-explain';
    explain.addEventListener('click', async () => {
      const j = await get(`/explain/journey/${a.action}`);
      const body = document.createElement('div');
      for (const line of j?.lines || []) { const p = document.createElement('p'); p.textContent = line; body.append(p); }
      const sh = document.createElement('h4'); sh.textContent = 'Step by step'; body.append(sh);
      const ol = document.createElement('ol'); ol.style.cssText = 'margin:.3rem 0 0 1.1rem;padding:0;font-size:13px';
      for (const st of j?.steps || []) { const li = document.createElement('li'); li.textContent = `${st.kind}: ${st.name} — ${st.says}`; ol.append(li); }
      body.append(ol);
      openSideDrawer(`${ontoTitle(a.action)} — the journey`, body);
    });
    const dry = document.createElement('button'); dry.className = 'ghost small'; dry.textContent = 'Try a dry run'; dry.dataset.testid = 'ontology-check';
    dry.addEventListener('click', () => {
      const form = document.createElement('div'); form.style.cssText = 'display:grid;gap:.5rem';
      const intro = document.createElement('p'); intro.className = 'dim'; intro.textContent = 'Nothing changes: the registry says whether this could happen for these inputs, and names every condition and its verdict.'; form.append(intro);
      const inputs = {};
      for (const i of a.inputs || []) {
        const label = document.createElement('label'); label.style.cssText = 'display:grid;gap:.2rem;font-size:13px';
        label.textContent = i.meaning || i.name; const inp = document.createElement('input'); inp.placeholder = i.concept ? `${i.concept} id` : i.name; inp.dataset.testid = `ontology-input-${i.name}`;
        label.append(inp); form.append(label); inputs[i.name] = inp;
      }
      const go = document.createElement('button'); go.className = 'primary small'; go.textContent = 'Check'; go.dataset.testid = 'ontology-run-check';
      const out = document.createElement('div'); out.dataset.testid = 'ontology-verdicts';
      go.addEventListener('click', async () => {
        go.disabled = true; out.textContent = 'Checking…';
        const body = {}; for (const [k, v] of Object.entries(inputs)) body[k] = v.value.trim();
        const r = await authFetch(`${ONTOLOGY_BASE}/actions/${a.action}/check`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        const c = await r.json().catch(() => ({}));
        out.replaceChildren();
        const verdict = document.createElement('p'); verdict.style.fontWeight = '600';
        verdict.textContent = c.allowed ? 'This could happen.' : `Refused: ${c.refusal || c.message || r.status}.`; out.append(verdict);
        for (const p of c.preconditions || []) {
          const line = document.createElement('div'); line.style.fontSize = '13px';
          line.textContent = `${p.verdict === 'holds' ? '✓' : p.verdict === 'fails' ? '✗' : '?'} ${p.says}${p.detail ? ` — ${p.detail}` : ''}`; out.append(line);
        }
        if (c.permission) { const pm = document.createElement('div'); pm.className = 'dim'; pm.style.fontSize = '13px'; pm.textContent = `Permission: ${c.permission.says}.`; out.append(pm); }
        if (c.policy) { const po = document.createElement('div'); po.className = 'dim'; po.style.fontSize = '13px'; po.textContent = `Policy: ${c.policy.says}.`; out.append(po); }
        go.disabled = false;
      });
      form.append(go, out);
      openSideDrawer(`${ontoTitle(a.action)} — dry run`, form);
    });
    row.append(explain, dry); card.append(row);
    panel.append(card);
  }

  const h2 = document.createElement('h3'); h2.textContent = 'Concepts — the things the business knows'; panel.append(h2);
  for (const c of concepts || []) {
    const d = document.createElement('details'); d.dataset.testid = 'ontology-concept';
    const s = document.createElement('summary'); s.textContent = `${c.concept}${c.canonical ? ' (canonical — spans several resources)' : ''}`; d.append(s);
    const body = document.createElement('div'); body.style.cssText = 'font-size:13px;display:grid;gap:.3rem;margin:.3rem 0 .6rem';
    const m = document.createElement('div'); m.textContent = c.meaning; body.append(m);
    const l = document.createElement('div'); l.className = 'dim'; l.textContent = `Lineage: SID ${c.lineage?.sid}; TM Forum ${(c.lineage?.tmf || []).join(', ')}. Held by ${capOf(c.backedBy?.capability).component} as "${c.backedBy?.resource}".`; body.append(l);
    const st = document.createElement('div'); st.className = 'dim'; st.textContent = `States (${c.states?.field}): ${(c.states?.values || []).join(', ')}${c.states?.live ? ` — in service when ${c.states.live.join(', ')}` : ''}.`; body.append(st);
    if ((c.links || []).length) { const lk = document.createElement('div'); lk.className = 'dim'; lk.textContent = `Related: ${c.links.map((k) => `${k.name} → ${k.to}`).join('; ')}.`; body.append(lk); }
    d.append(body); panel.append(d);
  }
}
