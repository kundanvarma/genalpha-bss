'use strict';

/* ---------------- Learning contracts: "what the system may decide" (phase 4) ---------------- */
const OBJECTIVES = [['conversion', 'purchases (conversions)'], ['adopted', 'proposals adopted'], ['accepted', 'suggestions accepted'], ['', 'nothing in particular — just record']];
const AUTONOMY_CARDS = [
  ['', 'Leave it as it is', ''],
  ['high', 'Runs on its own', 'reversible choices, like which message to send'],
  ['medium', 'Recommends, a person can override', 'traffic shifts, proposals'],
  ['low', 'Never without a person', 'money, rights, statute'],
];
function contractSentence(row) {
  const c = row.contract || {}; const parts = [];
  const obj = OBJECTIVES.find((o) => o[0] === (c.objective || ''));
  parts.push(c.objective ? `optimises for ${obj ? obj[1] : c.objective}` : 'records, does not optimise');
  if (c.allowedActions) parts.push(`only ${c.allowedActions.map((a) => (a === 'holdout' ? 'the control group' : a === 'message' ? 'the message' : `variant ${a}`)).join(', ')}`);
  else if (row.name === 'journey.enrolment' || row.name === 'campaign.treatment') parts.push('any variant');
  if (c.explorationMaxPercent !== null && c.explorationMaxPercent !== undefined) parts.push(`at most ${c.explorationMaxPercent} % of customers held out`);
  const auto = c.autonomy || row.autonomy; const card = AUTONOMY_CARDS.find((a) => a[0] === auto);
  parts.push(card ? card[1].toLowerCase() : auto);
  if (c.fallbackAction) parts.push(`falls back to ${c.fallbackAction}`);
  return parts.join(' · ');
}

async function renderLearningContracts() {
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  closeSideDrawer();
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'contracts-pane';
  const names = await loadDecisionNames();
  const list = document.createElement('div'); list.dataset.testid = 'contract-list'; list.className = 'contract-list';

  const q = (question, control, hint) => {
    const w = document.createElement('div'); w.className = 'q';
    const t = document.createElement('div'); t.className = 'q-title'; t.textContent = question;
    w.append(t, control);
    if (hint) { const h = document.createElement('div'); h.className = 'dim q-hint'; h.textContent = hint; w.append(h); }
    return w;
  };
  const radios = (name, opts, val, testidPrefix) => {
    const w = document.createElement('div'); w.className = 'radios';
    for (const [v, label, hint] of opts) {
      const l = document.createElement('label'); l.className = 'radio-card';
      const i = document.createElement('input'); i.type = 'radio'; i.name = name; i.value = v; i.checked = (val ?? '') === v; if (testidPrefix) i.dataset.testid = `${testidPrefix}-${v || 'default'}`;
      const s = document.createElement('span'); s.textContent = label;
      l.append(i, s);
      if (hint) { const h = document.createElement('small'); h.className = 'dim'; h.textContent = hint; l.append(h); }
      w.append(l);
    }
    w.value = () => (w.querySelector('input:checked') || {}).value ?? '';
    return w;
  };

  const openEditor = (row) => {
    const c = row.contract || {};
    const body = document.createElement('div'); body.dataset.testid = 'contract-editor'; body.className = 'contract-editor';
    const lead = document.createElement('p'); lead.className = 'dim'; lead.style.margin = '0 0 4px';
    lead.textContent = `${row.description}. Today: ${contractSentence(row)}.`;

    const objective = radios('objective', OBJECTIVES, c.objective, 'contract-objective');
    const guardrails = document.createElement('textarea'); guardrails.rows = 3; guardrails.dataset.testid = 'contract-guardrails';
    guardrails.placeholder = 'One rule per line, e.g. "never message a customer without marketing consent"';
    guardrails.value = (c.guardrails || []).join('\n');

    const allowedWrap = document.createElement('div');
    const allowedMode = radios('allowed', [['any', 'Any variant the journey offers'], ['only', 'Only these']], c.allowedActions ? 'only' : 'any', 'contract-allowed');
    const allowed = document.createElement('input'); allowed.dataset.testid = 'contract-allowed'; allowed.placeholder = 'e.g. holdout, A';
    allowed.value = c.allowedActions ? c.allowedActions.join(', ') : ''; allowed.style.marginTop = '6px';
    const syncAllowed = () => { allowed.hidden = allowedMode.value() !== 'only'; };
    allowedMode.addEventListener('change', syncAllowed); syncAllowed();
    allowedWrap.append(allowedMode, allowed);

    const capWrap = document.createElement('div'); capWrap.className = 'cap';
    const slider = document.createElement('input'); slider.type = 'range'; slider.min = 0; slider.max = 90; slider.step = 1;
    const cap = document.createElement('input'); cap.type = 'number'; cap.min = 0; cap.max = 90; cap.dataset.testid = 'contract-cap'; cap.style.width = '80px';
    const capOff = document.createElement('label'); capOff.className = 'inline';
    const capOffBox = document.createElement('input'); capOffBox.type = 'checkbox'; capOffBox.dataset.testid = 'contract-cap-off';
    capOff.append(capOffBox, document.createTextNode(' no limit'));
    const setCap = (v) => { slider.value = v; cap.value = v; };
    if (c.explorationMaxPercent === null || c.explorationMaxPercent === undefined) { capOffBox.checked = true; setCap(20); } else setCap(c.explorationMaxPercent);
    const syncCap = () => { slider.disabled = capOffBox.checked; cap.disabled = capOffBox.checked; };
    capOffBox.addEventListener('change', syncCap); syncCap();
    slider.addEventListener('input', () => { cap.value = slider.value; });
    cap.addEventListener('input', () => { slider.value = cap.value; capOffBox.checked = false; syncCap(); });
    capWrap.append(slider, cap, document.createTextNode(' %'), capOff);

    const autonomy = radios('autonomy', AUTONOMY_CARDS.map(([v, l, h]) => [v, v ? l : `${l} — ${(AUTONOMY_CARDS.find((a) => a[0] === row.autonomy) || [])[1] || row.autonomy}`, h]), c.autonomy, 'contract-autonomy');

    const fbOpts = row.name === 'journey.enrolment' || row.name === 'campaign.treatment'
      ? [['', 'Hold the customer out (no message)'], ['message', 'Send the plain message'], ['__named', 'A named variant:']]
      : [['', 'The point\'s own default'], ['__named', 'A named action:']];
    const fbCurrent = !c.fallbackAction ? '' : fbOpts.some((o) => o[0] === c.fallbackAction) ? c.fallbackAction : '__named';
    const fallback = radios('fallback', fbOpts, fbCurrent, 'contract-fallback');
    const fbName = document.createElement('input'); fbName.dataset.testid = 'contract-fallback-name'; fbName.placeholder = 'e.g. A'; fbName.style.marginTop = '6px';
    fbName.value = fbCurrent === '__named' ? c.fallbackAction : '';
    const syncFb = () => { fbName.hidden = fallback.value() !== '__named'; };
    fallback.addEventListener('change', syncFb); syncFb();
    const fbWrap = document.createElement('div'); fbWrap.append(fallback, fbName);

    const pauseWrap = document.createElement('label'); pauseWrap.className = 'switch';
    const enabled = document.createElement('input'); enabled.type = 'checkbox'; enabled.checked = c.enabled !== false; enabled.dataset.testid = 'contract-enabled';
    const pauseText = document.createElement('span');
    const syncPause = () => { pauseText.textContent = enabled.checked ? 'Running — the rule decides' : 'PAUSED — the fallback answers every decision, in every journey of this tenant'; pauseWrap.classList.toggle('warn', !enabled.checked); };
    enabled.addEventListener('change', syncPause); syncPause();
    pauseWrap.append(enabled, pauseText);

    const notes = document.createElement('textarea'); notes.rows = 2; notes.dataset.testid = 'contract-notes'; notes.placeholder = 'Why this contract is shaped this way'; notes.value = c.notes || '';

    const actions = document.createElement('div'); actions.className = 'actions'; actions.style.cssText = 'display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:14px';
    const save = document.createElement('button'); save.className = 'primary'; save.textContent = 'Save'; save.dataset.testid = 'contract-save';
    const reset = document.createElement('button'); reset.className = 'ghost'; reset.textContent = 'Back to defaults'; reset.dataset.testid = 'contract-reset';
    const msg = document.createElement('span'); msg.className = 'dim'; msg.dataset.testid = 'contract-msg';
    actions.append(save, reset, msg);
    const split = (s) => s.split(/[\n,]/).map((x) => x.trim()).filter(Boolean);
    const body_ = () => ({
      objective: objective.value() || null, guardrails: split(guardrails.value),
      allowedActions: allowedMode.value() === 'only' && allowed.value.trim() ? split(allowed.value) : null,
      explorationMaxPercent: capOffBox.checked ? null : Number(cap.value),
      autonomy: autonomy.value() || null,
      fallbackAction: fallback.value() === '__named' ? (fbName.value.trim() || null) : (fallback.value() || null),
      enabled: enabled.checked, notes: notes.value.trim() || null, secondaryMetrics: c.secondaryMetrics || [],
    });
    save.addEventListener('click', async () => {
      msg.textContent = 'saving…';
      const r = await authFetch(`${CAMPAIGN_BASE}/learningContract/${encodeURIComponent(row.name)}`, { method: 'PUT',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body_()) });
      if (!r.ok) { msg.textContent = (await r.json().catch(() => ({}))).message || 'save failed'; return; }
      const saved = await r.json();
      msg.textContent = `saved as version ${saved.contract.version}`;
      row.contract = saved.contract; lead.textContent = `${row.description}. Today: ${contractSentence(row)}.`;
      await load();
    });
    reset.addEventListener('click', async () => {
      const r = await authFetch(`${CAMPAIGN_BASE}/learningContract/${encodeURIComponent(row.name)}`, { method: 'DELETE' });
      msg.textContent = r.ok ? 'back to the defaults' : 'reset failed';
      await load(); closeSideDrawer();
    });

    /* try it on a real customer: the journey brings its own variants and control group */
    const dry = document.createElement('div'); dry.className = 'dry';
    const dryTitle = document.createElement('div'); dryTitle.className = 'q-title'; dryTitle.textContent = 'Try it — what would a customer get? Nothing is recorded.';
    const dryBar = document.createElement('div'); dryBar.className = 'staffbar'; dryBar.style.margin = '6px 0 8px';
    const journeySel = document.createElement('select'); journeySel.dataset.testid = 'dryrun-journey';
    const pool = row.name === 'campaign.treatment' ? names.campaigns : names.journeys;
    for (const j of pool) { const o = document.createElement('option'); o.value = j.id; o.textContent = j.name; journeySel.append(o); }
    const party = document.createElement('input'); party.placeholder = 'customer id'; party.value = `customer-${Date.now() % 1000}`; party.dataset.testid = 'dryrun-subject';
    const run = document.createElement('button'); run.className = 'ghost'; run.textContent = 'Try it'; run.dataset.testid = 'dryrun-run';
    dryBar.append(journeySel, party, run);
    const out = document.createElement('div'); out.dataset.testid = 'dryrun-result'; out.className = 'dry-out';
    run.addEventListener('click', async () => {
      out.textContent = 'deciding…';
      const j = pool.find((x) => x.id === journeySel.value) || {};
      const arms = Array.isArray(j.arms) ? j.arms.map((a) => a.name) : (Array.isArray(j.messageVariants) ? j.messageVariants.map((a) => a.name) : []);
      const candidates = ['holdout', ...(arms.length ? arms : ['message'])];
      const ctx = { partyId: party.value.trim(), holdoutPercent: Number(j.holdoutPercent || 0), seed: j.id || 'dry-run' };
      if (row.name === 'campaign.treatment') ctx.campaignId = j.id; else { ctx.journeyId = j.id; if (arms.length && j.armWeights) ctx.weights = j.armWeights; }
      const r = await authFetch(`${CAMPAIGN_BASE}/learningContract/${encodeURIComponent(row.name)}/dryRun`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ subjectId: party.value.trim(), candidates, context: ctx }) });
      const d = await r.json().catch(() => ({}));
      if (!r.ok) { out.textContent = d.message || 'the dry run failed'; return; }
      const capped = (d.constraints || []).find((x) => /capped at/.test(x));
      const removed = (d.constraints || []).filter((x) => !/capped at/.test(x)).map(constraintWords);
      // actionWords() can carry a variant name the campaign service supplied; pct() is Math.round.
      const s1 = document.createElement('div'); s1.innerHTML = `This customer would get <strong>${esc(actionWords(d))}</strong>${d.propensity !== null && d.propensity !== undefined ? ` — a ${pct(d.propensity)} chance` : ''}${d.fallback ? ' (the fallback answered)' : ''}.`;
      const s2 = document.createElement('div'); s2.className = 'dim';
      s2.textContent = `${capped ? 'The control group was ' + capped.replace(/^learning-contract: holdout /, '') + '. ' : ''}${removed.length ? 'Not allowed: ' + removed.join('; ') + '. ' : ''}${d.contract ? `Under learning contract version ${String(d.contract).split('@')[1]}.` : 'Under the default rules.'}`;
      out.replaceChildren(s1, s2);
    });
    dry.append(dryTitle, dryBar, out);
    if (!pool.length) dry.hidden = true;

    body.append(lead,
      q('What should this decision optimise?', objective),
      q('What must never happen?', guardrails, 'Plain rules. The constraints enforce them and the receipt names them.'),
      q('Which choices are allowed?', allowedWrap, 'A variant outside the list is removed before the rule looks.'),
      q('At most how many customers may be held out?', capWrap, 'A journey asking for more is capped, and the receipt says so.'),
      q('How much may it do on its own?', autonomy),
      q('If the rule cannot decide, what happens?', fbWrap),
      q('Is it running?', pauseWrap),
      q('Notes', notes),
      actions, dry);
    openSideDrawer(pointOf(row.name).label === row.name ? row.name : `Learning contract · ${pointOf(row.name).label}`, body);
  };

  const load = async () => {
    list.replaceChildren();
    const rows = await authFetch(`${CAMPAIGN_BASE}/learningContract`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    if (!Array.isArray(rows) || !rows.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'The campaign service is not reachable — no decision points to show.'; list.append(p); return; }
    for (const row of rows) {
      const c = row.contract || {};
      const card = document.createElement('div'); card.className = 'contract-card'; card.dataset.testid = 'contract-row'; card.dataset.point = row.name;
      if (c.enabled === false) card.classList.add('paused');
      const head = document.createElement('div'); head.className = 'contract-head';
      const name = document.createElement('strong'); name.textContent = pointOf(row.name).label === row.name ? row.name : pointOf(row.name).label;
      const desc = document.createElement('span'); desc.className = 'dim'; desc.textContent = row.description;
      head.append(name, desc);
      const sentence = document.createElement('p'); sentence.className = 'contract-sentence'; sentence.textContent = contractSentence(row) + '.';
      const foot = document.createElement('div'); foot.className = 'contract-foot';
      const state = document.createElement('span'); state.className = 'dim'; state.dataset.testid = 'contract-state';
      state.textContent = c.defaults ? 'No contract yet — running on the defaults' : `Version ${c.version}${c.updatedBy ? ` by ${c.updatedBy}` : ''}${c.lastUpdate ? `, ${when(c.lastUpdate)}` : ''}${c.enabled === false ? ' · PAUSED' : ''}`;
      const btns = document.createElement('span'); btns.style.cssText = 'display:flex;gap:8px';
      const edit = document.createElement('button'); edit.className = 'ghost'; edit.textContent = c.defaults ? 'Write the contract' : 'Change'; edit.dataset.testid = 'contract-edit';
      edit.addEventListener('click', () => openEditor(row));
      const pause = document.createElement('button'); pause.className = 'ghost'; pause.dataset.testid = 'contract-pause';
      pause.textContent = c.enabled === false ? 'Resume' : 'Pause';
      pause.addEventListener('click', async () => {
        const body = { ...c, enabled: c.enabled === false, allowedActions: c.allowedActions || null };
        delete body.defaults; delete body.version; delete body.ref; delete body.id; delete body.updatedBy; delete body.lastUpdate;
        if (c.enabled !== false && !window.confirm('Pause this rule? Every journey of this tenant will use the fallback until it is resumed.')) return;
        await authFetch(`${CAMPAIGN_BASE}/learningContract/${encodeURIComponent(row.name)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        await load();
      });
      btns.append(edit, pause);
      foot.append(state, btns);
      card.append(head, sentence, foot);
      list.append(card);
    }
  };
  panel.append(list);
  await load();
}
