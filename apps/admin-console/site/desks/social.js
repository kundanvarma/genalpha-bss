'use strict';

// SOCIAL LISTENING: the inbound half of social — what's said ABOUT the brand.
// Pull mentions, see the mood (sentiment) and where it's happening (platform),
// read the feed. Outbound campaigns are elsewhere; this is the ear.
async function renderSocialListening() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'social-listening';
  const intro = document.createElement('p');
  intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'What people say ABOUT the brand — pulled from social, scored for sentiment. '
    + 'Sync to refresh; the mood and the feed are the ear on the market.';
  const bar = document.createElement('div'); bar.className = 'staffbar';
  const sync = document.createElement('button'); sync.className = 'primary'; sync.textContent = 'Sync mentions';
  sync.dataset.testid = 'listening-sync';
  const note = document.createElement('span'); note.className = 'dim'; note.style.cssText = 'font-size:12px;margin-left:8px';
  bar.append(sync, note);
  const summaryWrap = document.createElement('div'); summaryWrap.dataset.testid = 'listening-summary'; summaryWrap.style.margin = '14px 0';
  const feed = document.createElement('div'); feed.dataset.testid = 'listening-feed';

  const chip = (label, n, color) => {
    const s = document.createElement('span');
    s.style.cssText = `display:inline-block;margin:0 8px 6px 0;padding:3px 10px;border-radius:12px;font-size:12px;background:${color};color:#fff`;
    s.textContent = `${label}: ${n}`; return s;
  };
  const load = async () => {
    const sum = await (await authFetch('/insight/v1/listening/summary')).json().catch(() => ({ total: 0, sentiment: {}, byPlatform: {} }));
    summaryWrap.replaceChildren();
    const h = document.createElement('h3'); h.textContent = `Mentions: ${sum.total || 0}`; h.style.cssText = 'font-size:15px;margin:0 0 8px';
    const s = sum.sentiment || {};
    const row = document.createElement('div');
    row.append(chip('Positive', s.positive || 0, '#2e7d32'), chip('Neutral', s.neutral || 0, '#607d8b'), chip('Negative', s.negative || 0, '#c62828'));
    const plat = document.createElement('div'); plat.className = 'dim'; plat.style.cssText = 'font-size:12px;margin-top:4px';
    plat.textContent = 'By platform: ' + (Object.entries(sum.byPlatform || {}).map(([k, v]) => `${k} ${v}`).join(' · ') || '—');
    summaryWrap.append(h, row, plat);

    const list = await (await authFetch('/insight/v1/listening/mentions')).json().catch(() => []);
    feed.replaceChildren();
    const fh = document.createElement('h3'); fh.textContent = 'Recent mentions'; fh.style.cssText = 'font-size:14px;margin:6px 0';
    feed.append(fh);
    if (!list.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No mentions yet — Sync to pull them in.'; feed.append(p); return; }
    for (const m of list.slice(0, 50)) {
      const card = document.createElement('div'); card.className = 'panel'; card.dataset.testid = 'mention-row';
      card.style.cssText = 'padding:8px 12px;margin:6px 0';
      const col = { positive: '#2e7d32', negative: '#c62828', neutral: '#607d8b' }[m.sentiment] || '#607d8b';
      const meta = document.createElement('div'); meta.style.cssText = 'font-size:12px;margin-bottom:2px';
      // col is one of the three literals above — never a value off the wire.
      meta.innerHTML = `<span style="color:${esc(col)};font-weight:600">${esc(m.sentiment || 'neutral')}</span> · `
        + `<span class="dim">${esc(m.platform || '')} · @${esc(m.author || '')}</span>`;
      const txt = document.createElement('div'); txt.textContent = m.text || ''; txt.style.fontSize = '13px';
      card.append(meta, txt); feed.append(card);
    }
  };
  sync.addEventListener('click', async () => {
    note.textContent = 'syncing…';
    const r = await authFetch('/insight/v1/listening/sync', { method: 'POST' });
    const res = r.ok ? await r.json() : { ingested: 0 };
    note.textContent = `pulled ${res.ingested} new mention(s)`;
    load();
  });
  /* ---------- organic publishing: put a post OUT on the brand handle ---------- */
  const pub = document.createElement('div'); pub.className = 'panel'; pub.dataset.testid = 'publish-card';
  pub.style.cssText = 'padding:14px 16px;margin:14px 0';
  const ph = document.createElement('h3'); ph.textContent = 'Publish to the brand handle'; ph.style.cssText = 'font-size:14px;margin:0 0 8px';
  const pt = document.createElement('textarea'); pt.dataset.testid = 'publish-text'; pt.rows = 3;
  pt.style.cssText = 'width:100%;box-sizing:border-box'; pt.placeholder = "What's new? — an organic post to your followers";
  const pbar = document.createElement('div'); pbar.className = 'staffbar'; pbar.style.marginTop = '8px';
  const pbtn = document.createElement('button'); pbtn.className = 'primary'; pbtn.textContent = 'Publish'; pbtn.dataset.testid = 'publish-btn';
  const pnote = document.createElement('span'); pnote.className = 'dim'; pnote.style.cssText = 'font-size:12px;margin-left:8px'; pnote.dataset.testid = 'publish-note';
  pbar.append(pbtn, pnote);
  const pfeed = document.createElement('div'); pfeed.dataset.testid = 'publish-feed'; pfeed.style.marginTop = '8px';
  const loadPosts = async () => {
    const list = await (await authFetch('/insight/v1/social/posts')).json().catch(() => []);
    pfeed.replaceChildren();
    for (const p of list.slice(-10).reverse()) {
      const c = document.createElement('div'); c.className = 'dim'; c.style.cssText = 'font-size:12px;padding:4px 0;border-top:1px solid rgba(128,128,128,.2)';
      c.textContent = '↗ ' + (p.message || ''); pfeed.append(c);
    }
  };
  pbtn.addEventListener('click', async () => {
    const content = pt.value.trim(); if (!content) { pnote.textContent = 'write something first'; return; }
    pnote.textContent = 'publishing…';
    const r = await authFetch('/insight/v1/social/publish', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ content }) });
    const res = r.ok ? await r.json() : { published: false };
    pnote.textContent = res.published ? 'published ✓' : 'publish unavailable';
    if (res.published) { pt.value = ''; loadPosts(); }
  });
  pub.append(ph, pt, pbar, pfeed);
  loadPosts();

  load();
  panel.append(intro, pub, bar, summaryWrap, feed);
}

// SOCIAL CARE: inbound DMs, triaged. Sync pulls direct messages, scores mood +
// support intent, and routes the ones that need a human to trouble tickets over
// the bus. The queue is what the care team works; praise stays out of it.
async function renderSocialCare() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'social-care';
  const intro = document.createElement('p');
  intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'Inbound direct messages, triaged. Sync pulls DMs and scores them; a negative or '
    + 'support message is routed to a trouble ticket automatically (over the bus, not a call). Praise is left alone.';
  const bar = document.createElement('div'); bar.className = 'staffbar';
  const sync = document.createElement('button'); sync.className = 'primary'; sync.textContent = 'Sync DMs';
  sync.dataset.testid = 'care-sync';
  const note = document.createElement('span'); note.className = 'dim'; note.style.cssText = 'font-size:12px;margin-left:8px';
  bar.append(sync, note);
  const summaryWrap = document.createElement('div'); summaryWrap.dataset.testid = 'care-summary'; summaryWrap.style.margin = '14px 0';
  const queue = document.createElement('div'); queue.dataset.testid = 'care-queue';

  const chip = (label, n, color) => {
    const s = document.createElement('span');
    s.style.cssText = `display:inline-block;margin:0 8px 6px 0;padding:3px 10px;border-radius:12px;font-size:12px;background:${color};color:#fff`;
    s.textContent = `${label}: ${n}`; return s;
  };
  const load = async () => {
    const sum = await (await authFetch('/insight/v1/care/summary')).json().catch(() => ({ total: 0, needCare: 0, sentiment: {} }));
    summaryWrap.replaceChildren();
    const h = document.createElement('h3'); h.textContent = `DMs: ${sum.total || 0} · needs care: ${sum.needCare || 0}`;
    h.style.cssText = 'font-size:15px;margin:0 0 8px';
    const s = sum.sentiment || {};
    const row = document.createElement('div');
    row.append(chip('Positive', s.positive || 0, '#2e7d32'), chip('Neutral', s.neutral || 0, '#607d8b'), chip('Negative', s.negative || 0, '#c62828'));
    summaryWrap.append(h, row);

    const list = await (await authFetch('/insight/v1/care/queue')).json().catch(() => []);
    queue.replaceChildren();
    const fh = document.createElement('h3'); fh.textContent = 'Care queue'; fh.style.cssText = 'font-size:14px;margin:6px 0';
    queue.append(fh);
    if (!list.length) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = 'No DMs yet — Sync to pull them in.'; queue.append(p); return; }
    for (const m of list.slice(0, 50)) {
      const card = document.createElement('div'); card.className = 'panel'; card.dataset.testid = 'care-row';
      card.style.cssText = 'padding:8px 12px;margin:6px 0';
      const col = { positive: '#2e7d32', negative: '#c62828', neutral: '#607d8b' }[m.sentiment] || '#607d8b';
      const meta = document.createElement('div'); meta.style.cssText = 'font-size:12px;margin-bottom:2px';
      // col is one of the three literals above — never a value off the wire.
      meta.innerHTML = `<span style="color:${esc(col)};font-weight:600">${esc(m.sentiment || 'neutral')}</span> · `
        + `<span class="dim">${esc(m.platform || '')} · ${esc(m.handle || ('@' + (m.author || '')))}</span>`
        + (m.ticketRequested ? ' · <span style="color:#c62828;font-weight:600">ticket opened</span>'
          : (m.needsCare ? ' · <span class="dim">needs care</span>' : ''));
      const txt = document.createElement('div'); txt.textContent = m.text || ''; txt.style.fontSize = '13px';
      card.append(meta, txt); queue.append(card);
    }
  };
  sync.addEventListener('click', async () => {
    note.textContent = 'syncing…';
    const r = await authFetch('/insight/v1/care/sync', { method: 'POST' });
    const res = r.ok ? await r.json() : { ingested: 0, ticketsRequested: 0 };
    note.textContent = `pulled ${res.ingested} DM(s) · ${res.ticketsRequested} routed to tickets`;
    load();
  });
  panel.append(intro, bar, summaryWrap, queue);
  load();
}

/* ---------- Voice of Customer (SI-P4): what customers SAY, aggregated with
 * receipts. v1 is honest SQL over verified classifications — the method label
 * says so on the pane. Deviating aspects carry the early-warning badge. */
async function renderVoc() {
  const panel = copilotPanel();
  panel.replaceChildren();
  panel.dataset.testid = 'voc-pane';
  const intro = document.createElement('p');
  intro.className = 'dim'; intro.style.cssText = 'font-size:13px;margin:6px 0 12px';
  intro.textContent = 'What customers SAY — every signal PII-firewalled at ingest, every '
    + 'classification carrying a verbatim evidence quote. Aggregated per aspect over 28 days.';
  const method = document.createElement('p');
  method.className = 'dim'; method.dataset.testid = 'voc-method';
  method.style.cssText = 'font-size:11px;margin:0 0 10px;font-style:italic';
  const alertsWrap = document.createElement('div'); alertsWrap.dataset.testid = 'voc-alerts';
  const cards = document.createElement('div'); cards.dataset.testid = 'voc-aspects';
  const drill = document.createElement('div'); drill.dataset.testid = 'voc-drill';

  const chip = (label, n, color) => {
    const c = document.createElement('span');
    c.style.cssText = `display:inline-block;margin:0 6px 4px 0;padding:2px 9px;border-radius:12px;font-size:12px;background:${color};color:#fff`;
    c.textContent = `${label} ${n}`; return c;
  };

  const sum = await (await authFetch('/insight/v1/voc/summary')).json()
    .catch(() => ({ aspects: [], alerts: [], method: '' }));
  method.textContent = 'Method: ' + (sum.method || '');

  for (const al of (sum.alerts || []).slice(0, 5)) {
    const b = document.createElement('div'); b.dataset.testid = 'voc-alert';
    b.style.cssText = 'padding:8px 12px;margin:0 0 8px;border-left:3px solid #c62828;background:#fdecea;font-size:13px;border-radius:4px';
    b.textContent = `⚠ ${al.aspect}: ${al.weekNegatives} negatives this week vs a `
      + `${al.baselineAvg}/week baseline (${al.isoWeek}) — customers are telling you something.`;
    alertsWrap.append(b);
  }

  if (!(sum.aspects || []).length) {
    const p = document.createElement('p'); p.className = 'dim';
    p.textContent = 'No classified signals in the window yet — the battery fills this pane as signals arrive.';
    cards.append(p);
  }
  for (const a of (sum.aspects || [])) {
    const card = document.createElement('div'); card.className = 'panel'; card.dataset.testid = 'voc-aspect';
    card.style.cssText = 'padding:10px 14px;margin:8px 0;cursor:pointer';
    const head = document.createElement('div'); head.style.cssText = 'display:flex;align-items:baseline;gap:10px';
    const h = document.createElement('h3'); h.textContent = a.aspect; h.style.cssText = 'font-size:15px;margin:0';
    const trend = document.createElement('span'); trend.style.cssText = 'font-size:12px';
    const base = a.baselineWeeklyNegatives || 0;
    trend.textContent = a.deviating ? '▲ deviating' : (a.weekNegatives > base ? '▲' : a.weekNegatives < base ? '▼' : '→');
    trend.style.color = a.deviating ? '#c62828' : '#607d8b';
    const tot = document.createElement('span'); tot.className = 'dim'; tot.style.fontSize = '12px';
    tot.textContent = `${a.total} signals · ${a.thisWeek} this week`;
    head.append(h, trend, tot);
    const row = document.createElement('div'); row.style.marginTop = '6px';
    row.append(chip('pos', a.positive, '#2e7d32'), chip('neu', a.neutral, '#607d8b'), chip('neg', a.negative, '#c62828'));
    card.append(head, row);
    for (const p of (a.painPoints || []).slice(0, 3)) {
      const pp = document.createElement('div'); pp.className = 'dim';
      pp.style.cssText = 'font-size:12px;margin-top:3px';
      pp.textContent = `· ${p.painPoint}` + (p.impact ? ` (impact ${p.impact}/5)` : '');
      card.append(pp);
    }
    card.addEventListener('click', async () => {
      drill.replaceChildren();
      const dh = document.createElement('h3'); dh.textContent = `Signals — ${a.aspect}`;
      dh.style.cssText = 'font-size:14px;margin:10px 0 6px'; drill.append(dh);
      const rows = await (await authFetch('/insight/v1/signal')).json().catch(() => []);
      for (const r of rows.filter((x) => x.classification && x.classification.aspect === a.aspect).slice(0, 20)) {
        const rc = document.createElement('div'); rc.className = 'panel'; rc.dataset.testid = 'voc-signal';
        rc.style.cssText = 'padding:7px 11px;margin:5px 0';
        const meta = document.createElement('div'); meta.className = 'dim'; meta.style.cssText = 'font-size:11px;margin-bottom:2px';
        const c = r.classification;
        meta.textContent = `${r.source} · ${c.sentiment} · ${c.category}` + (c.churnSignal ? ' · CHURN SIGNAL' : '');
        const t = document.createElement('div'); t.textContent = r.text; t.style.fontSize = '13px';
        rc.append(meta, t); drill.append(rc);
      }
    });
    cards.append(card);
  }
  /* ---------- ask the VoC data (SI-P5): grounded on aggregates only ---------- */
  const ask = document.createElement('div'); ask.className = 'panel'; ask.dataset.testid = 'voc-ask';
  ask.style.cssText = 'padding:12px 14px;margin:14px 0 8px';
  const ah = document.createElement('h3'); ah.textContent = 'Ask the voice of customer';
  ah.style.cssText = 'font-size:14px;margin:0 0 6px';
  const asub = document.createElement('p'); asub.className = 'dim';
  asub.style.cssText = 'font-size:11px;margin:0 0 8px;font-style:italic';
  asub.textContent = 'Answers come from the aggregates only — raw customer words never reach the model. Claims cite [signalId] receipts.';
  const abar = document.createElement('div'); abar.style.cssText = 'display:flex;gap:8px';
  const ain = document.createElement('input'); ain.dataset.testid = 'voc-ask-input';
  ain.placeholder = 'e.g. What are customers complaining about this week?';
  ain.style.cssText = 'flex:1;padding:6px 10px';
  const abtn = document.createElement('button'); abtn.className = 'primary'; abtn.textContent = 'Ask';
  abtn.dataset.testid = 'voc-ask-btn';
  const aout = document.createElement('div'); aout.dataset.testid = 'voc-ask-answer';
  aout.style.cssText = 'font-size:13px;margin-top:10px;white-space:pre-wrap';
  abtn.addEventListener('click', async () => {
    if (!ain.value.trim()) return;
    aout.textContent = 'thinking…';
    try {
      const r = await authFetch('/ai/v1/voc/ask', { method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: ain.value.trim() }) });
      const res = await r.json();
      aout.textContent = r.ok ? res.answer : (res.message || 'the ask surface is unavailable');
    } catch { aout.textContent = 'the ask surface is unavailable'; }
  });
  abar.append(ain, abtn); ask.append(ah, asub, abar, aout);
  panel.append(intro, method, alertsWrap, ask, cards, drill);
}
