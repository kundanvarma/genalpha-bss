'use strict';

/* ---------------- Decisions: "why did we do that?" (continuous learning, phase 3) ---------------- */
const DECISIONS_BASE = '/insight/v1/decisions';
const POINTS = [
  { key: 'journey.enrolment', label: 'Journey enrolments', one: 'journey enrolment' },
  { key: 'campaign.treatment', label: 'Campaigns', one: 'campaign send' },
  { key: 'journey.nextBestAction', label: 'Next best action', one: 'next-best-action' },
  { key: 'journey.armWeights', label: 'Traffic shifts', one: 'traffic shift' },
  { key: 'catalog.advisorProposal', label: 'Advisor', one: 'advisor proposal' },
  { key: 'desk.suggestion', label: 'Desk', one: 'desk suggestion' },
];
const pointOf = (k) => POINTS.find((p) => p.key === k) || { key: k, label: k, one: k };
const AUTONOMY_WORDS = {
  high: 'the system on its own — a reversible choice',
  medium: 'the system, with a person able to override',
  low: 'a person has to confirm this kind of choice',
};
const money = (v) => {
  if (v === null || v === undefined || v === '') return '';
  const cur = (window.BSS_CONSOLE_CONFIG || {}).currency || 'NOK';
  const n = Number(v); return Number.isNaN(n) ? String(v) : `${n.toLocaleString(undefined, { maximumFractionDigits: 0 })} ${cur}`;
};
const when = (s) => {
  if (!s) return ''; const d = new Date(s); if (Number.isNaN(d.getTime())) return String(s);
  const mins = Math.round((Date.now() - d.getTime()) / 60000);
  if (mins < 1) return 'just now'; if (mins < 60) return `${mins} min ago`;
  const hrs = Math.round(mins / 60); if (hrs < 24) return `${hrs} h ago`;
  const days = Math.round(hrs / 24); if (days < 7) return `${days} day${days > 1 ? 's' : ''} ago`;
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
};
const clock = (s) => { const d = new Date(s); return Number.isNaN(d.getTime()) ? String(s) : d.toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }); };
const pct = (p) => `${Math.round(Number(p) * 1000) / 10} %`;
const shortId = (s) => (s && String(s).length > 18 ? String(s).slice(0, 8) + '…' : String(s || ''));

/* names for the ids a decision talks about — journeys and campaigns from the campaign service */
let decisionNames = null;
async function loadDecisionNames() {
  if (decisionNames) return decisionNames;
  const [journeys, campaigns] = await Promise.all([
    authFetch(`${CAMPAIGN_BASE}/journey`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
    authFetch(`${CAMPAIGN_BASE}/campaign?limit=200`).then((r) => (r.ok ? r.json() : [])).catch(() => []),
  ]);
  decisionNames = { journey: {}, campaign: {}, journeys: Array.isArray(journeys) ? journeys : [], campaigns: Array.isArray(campaigns) ? campaigns : [] };
  for (const j of decisionNames.journeys) decisionNames.journey[j.id] = j.name;
  for (const c of decisionNames.campaigns) decisionNames.campaign[c.id] = c.name;
  return decisionNames;
}
const journeyName = (names, id) => names.journey[id] || (id ? `journey ${shortId(id)}` : 'a journey');
const campaignName = (names, id) => names.campaign[id] || (id ? `campaign ${shortId(id)}` : 'a campaign');

/* one choice, in words: the action for THIS decision point */
function actionWords(d) {
  const a = d.action;
  switch (d.decisionPoint) {
    case 'journey.enrolment':
    case 'campaign.treatment':
      if (a === 'holdout') return 'no message (control group)';
      if (a === 'message') return 'the message';
      return `message variant ${a}`;
    case 'journey.armWeights':
      if (a === 'shift') { const after = (d.evidence || {}).after || {}; const best = Object.keys(after).sort((x, y) => after[y] - after[x])[0]; return `moved traffic to variant ${best}`; }
      if (a === 'waiting') return 'waited for more evidence';
      return 'kept the split as it is';
    case 'journey.nextBestAction': return 'let one journey speak';
    case 'catalog.advisorProposal': return a === 'TOPUP_ATTACH' ? 'a bigger tier for a plan customers keep topping up' : a === 'MARKET_PRICE' ? 'a counter to a market price' : `a ${a} proposal`;
    case 'desk.suggestion': return `a ${a} suggestion`;
    default: return String(a);
  }
}
/* who or what the decision was about */
function subjectWords(d, names) {
  const ctx = d.context || {};
  switch (d.decisionPoint) {
    case 'journey.enrolment': return `customer ${shortId(d.subjectId)} in ${journeyName(names, ctx.journeyId)}`;
    case 'campaign.treatment': return `customer ${shortId(d.subjectId)} in ${campaignName(names, ctx.campaignId)}`;
    case 'journey.nextBestAction': return `customer ${shortId(d.subjectId)}`;
    case 'journey.armWeights': return journeyName(names, d.subjectId);
    case 'catalog.advisorProposal': return `offer ${d.subjectId}`;
    case 'desk.suggestion': return `the ${d.subjectId} form`;
    default: return String(d.subjectId || '');
  }
}
/* the row as one sentence */
function rowWords(d, names) {
  const ctx = d.context || {}; const ev = d.evidence || {};
  switch (d.decisionPoint) {
    case 'journey.enrolment':
    case 'campaign.treatment': return `${subjectWords(d, names)} got ${actionWords(d)}`;
    case 'journey.nextBestAction': {
      const pr = ctx.priorities || {}; const held = Object.keys(pr).find((k) => k !== d.action);
      return `${journeyName(names, d.action)} spoke to customer ${shortId(d.subjectId)}; ${journeyName(names, held)} waited an hour`;
    }
    case 'journey.armWeights': return `${journeyName(names, d.subjectId)}: ${actionWords(d)}${ev.z !== undefined ? ` (evidence z ${ev.z})` : ''}`;
    case 'catalog.advisorProposal': return `the advisor proposed ${actionWords(d)} for ${d.subjectId}`;
    case 'desk.suggestion': return `the desk suggested “${ev.title || d.reason || d.action}”`;
    default: return `${subjectWords(d, names)}: ${d.action}`;
  }
}
function outcomeWords(d) {
  if (!d.outcome) return null;
  const v = d.outcomeValue ? `, ${money(d.outcomeValue)}` : '';
  const map = { conversion: `bought${v}`, adopted: 'adopted as a draft offer', accepted: 'accepted by the desk', dismissed: 'dismissed by the desk' };
  return map[d.outcome] || d.outcome;
}
function eligibleWords(d) {
  return (d.eligibleActions || []).map((a) => actionWords({ ...d, action: a })).join(', ');
}
function constraintWords(c) {
  if (/^learning-contract: paused$/.test(c)) return 'the rule was paused';
  const m = String(c).match(/^([^:]+): (.+?) — (.+)$/);
  if (!m) return String(c).replace(/^learning-contract: /, 'the learning contract: ');
  const rule = { 'learning-contract': 'the learning contract', consent: 'consent', 'channel-availability': 'channel availability' }[m[1]] || m[1];
  return `${m[2]} was not allowed by ${rule} (${m[3]})`;
}
/* the receipt: six sentences a person can read */
function receiptLines(d, names) {
  const ctx = d.context || {}; const ev = d.evidence || {}; const lines = [];
  const split = ctx.weights ? Object.entries(ctx.weights).map(([k, v]) => `${k} ${v}`).join(' / ') : null;
  switch (d.decisionPoint) {
    case 'journey.enrolment':
    case 'campaign.treatment':
      lines.push(`We looked at: ${split ? `the current split of variants (${split}), ` : ''}a control group of ${ctx.holdoutPercent ?? 0} %, and the customer's id — nothing else about the customer.`);
      break;
    case 'journey.armWeights': {
      const rows = (ctx.rows || []).map((r) => `${r.name}: ${r.converted} of ${r.enrolled} converted`).join('; ');
      lines.push(`We looked at: each variant's results (${rows}), the current split, and whether the difference is evidence yet (needs z ≥ ${ctx.threshold}, at least ${ctx.minPerArm} customers per variant).`);
      break;
    }
    case 'journey.nextBestAction': {
      const pr = ctx.priorities || {};
      lines.push(`We looked at: two journeys wanting the same customer this moment, with priorities ${Object.entries(pr).map(([k, v]) => `${journeyName(names, k)} ${v}`).join(' and ')}.`);
      break;
    }
    default:
      lines.push(`We looked at: ${Object.keys(ctx).length ? Object.entries(ctx).map(([k, v]) => `${k} ${typeof v === 'object' ? JSON.stringify(v) : v}`).join(', ') : 'nothing beyond the rule itself'}.`);
  }
  const removed = (d.constraints || []).filter((c) => !/capped at/.test(c)).map(constraintWords);
  const capped = (d.constraints || []).find((c) => /capped at/.test(c));
  lines.push(`It could have chosen: ${eligibleWords(d) || 'nothing'}.${removed.length ? ' Not this time: ' + removed.join('; ') + '.' : ''}${capped ? ' The control group was ' + capped.replace(/^learning-contract: holdout /, '') + '.' : ''}`);
  const paused = (d.constraints || []).some((c) => /^learning-contract: paused$/.test(c));
  lines.push(d.fallback
    ? `It chose: ${actionWords(d)} — the fallback answer, because ${paused ? 'the rule was paused' : 'the rule could not decide'}.`
    : `It chose: ${actionWords(d)}${d.propensity !== null && d.propensity !== undefined ? ` — a ${pct(d.propensity)} chance under the current split` : ' — no chance involved, this rule is fixed'}.`);
  const why = paused ? 'the learning contract is paused, so nothing is decided until someone resumes it' : (d.reason || 'no reason was recorded');
  lines.push(`Because: ${why}${!/[.)]$/.test(why) ? '.' : ''}`);
  lines.push(`Who was allowed to decide: ${AUTONOMY_WORDS[d.autonomy] || 'unclassified'}, ${d.contract ? `under learning contract version ${String(d.contract).split('@')[1] || '?'}` : 'under the default rules'}.`);
  const o = outcomeWords(d);
  lines.push(o ? `What happened next: the customer ${o}, ${when(d.outcomeAt)}.` : 'What happened next: nothing yet.');
  return lines;
}

async function showReceipt(id, names) {
  const r = await authFetch(`${DECISIONS_BASE}/${encodeURIComponent(id)}`).then((x) => (x.ok ? x.json() : null)).catch(() => null);
  const body = document.createElement('div'); body.dataset.testid = 'decision-receipt';
  if (!r) { body.textContent = 'This decision is not in the log any more.'; openSideDrawer('Receipt', body); return; }
  const lead = document.createElement('p'); lead.className = 'dim'; lead.style.margin = '0 0 10px';
  lead.textContent = `${rowWords(r, names)} · ${clock(r.decidedAt)}`;
  const ol = document.createElement('ol'); ol.className = 'receipt';
  for (const line of receiptLines(r, names)) { const li = document.createElement('li'); li.textContent = line; ol.append(li); }
  const details = document.createElement('details'); details.className = 'record';
  const sum = document.createElement('summary'); sum.textContent = 'Show the record (for engineers)'; details.append(sum);
  const pre = document.createElement('pre'); pre.textContent = JSON.stringify(r, null, 2); details.append(pre);
  body.append(lead, ol, details);
  openSideDrawer(`Why ${r.decisionPoint === 'journey.armWeights' ? 'the traffic changed' : r.decisionPoint === 'journey.nextBestAction' ? 'one journey waited' : 'this customer got that'}`, body);
}

async function renderDecisions() {
  document.getElementById('list-search')?.setAttribute('hidden', '');
  document.querySelector('.pager')?.setAttribute('hidden', '');
  closeSideDrawer();
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'decisions-pane';
  const names = await loadDecisionNames();
  const summary = await authFetch(`${DECISIONS_BASE}/summary`).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const counts = {}; for (const p of (summary?.points || [])) counts[p.decisionPoint] = (counts[p.decisionPoint] || 0) + (p.decisions || 0);

  const chips = document.createElement('div'); chips.className = 'chips'; chips.dataset.testid = 'decisions-chips';
  let point = ''; let onlyFallbacks = false;
  const chip = (label, key, n) => {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'chip'; b.dataset.testid = 'decisions-chip'; b.dataset.point = key;
    b.textContent = n === undefined ? label : `${label} · ${n}`;
    b.addEventListener('click', () => { point = key; onlyFallbacks = false; [...chips.children].forEach((c) => c.classList.toggle('on', c === b)); load(); });
    return b;
  };
  chips.append(chip('All', '', summary?.decisions));
  for (const p of POINTS) if (counts[p.key]) chips.append(chip(p.label, p.key, counts[p.key]));
  const fb = (summary?.points || []).reduce((n, p) => n + (p.fallbacks || 0), 0);
  if (fb) { const b = chip(`Fell back to the default`, '__fallback', fb); b.addEventListener('click', () => { onlyFallbacks = true; }); chips.append(b); }
  chips.children[0].classList.add('on');

  const bar = document.createElement('div'); bar.className = 'staffbar'; bar.style.margin = '10px 0 12px';
  const subject = document.createElement('input'); subject.placeholder = 'find a customer or a journey by id'; subject.dataset.testid = 'decisions-subject';
  const go = document.createElement('button'); go.className = 'ghost'; go.textContent = 'Find'; go.dataset.testid = 'decisions-show';
  bar.append(subject, go);
  const list = document.createElement('div'); list.dataset.testid = 'decisions-list'; list.className = 'decision-list';

  const load = async () => {
    list.replaceChildren();
    const q = new URLSearchParams({ limit: '100' });
    if (point && point !== '__fallback') q.set('decisionPoint', point);
    if (subject.value.trim()) q.set('subjectId', subject.value.trim());
    let rows = await authFetch(`${DECISIONS_BASE}?${q}`).then((r) => (r.ok ? r.json() : [])).catch(() => []);
    if (!Array.isArray(rows)) rows = [];
    if (onlyFallbacks) rows = rows.filter((d) => d.fallback);
    if (!rows.length) {
      const p = document.createElement('p'); p.className = 'dim';
      p.textContent = subject.value.trim() ? 'Nothing on the log for that id.' : 'Nothing here yet. Decisions arrive as journeys enrol customers, campaigns send, the tuner judges and the advisor proposes.';
      list.append(p); return;
    }
    for (const d of rows) {
      const row = document.createElement('button'); row.type = 'button'; row.className = 'decision-row'; row.dataset.testid = 'decision-row'; row.dataset.id = d.decisionId;
      const t = document.createElement('span'); t.className = 'dim when'; t.textContent = when(d.decidedAt); t.title = clock(d.decidedAt);
      const s = document.createElement('span'); s.className = 'what'; s.textContent = rowWords(d, names);
      const pill = document.createElement('span'); pill.className = 'pill';
      const o = outcomeWords(d);
      if (d.fallback) { pill.classList.add('warn'); pill.textContent = 'fell back'; }
      else if (o) { pill.classList.add('ok'); pill.textContent = o; }
      else if (d.propensity !== null && d.propensity !== undefined) { pill.textContent = `${pct(d.propensity)} chance`; }
      else { pill.textContent = pointOf(d.decisionPoint).one; }
      row.append(t, s, pill);
      row.addEventListener('click', () => showReceipt(d.decisionId, names));
      list.append(row);
    }
  };
  go.addEventListener('click', load);
  subject.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); load(); } });
  panel.append(chips, bar, list);
  await load();
}
