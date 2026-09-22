/* The journey step builder. */
'use strict';

/**
 * Step builder: author a journey stage by stage — add / reorder / remove step
 * cards, each with the fields its type needs — instead of hand-writing JSON.
 * The JSON is kept in sync underneath (and editable under Advanced), so the
 * same object still reaches the API. This is how a marketer builds a journey;
 * the JSON is just the wire format, never the authoring surface.
 */
function stepBuilderControl(field) {
  const TYPES = ['message', 'wait', 'waitForEvent', 'decision', 'exit'];
  const SVGNS = 'http://www.w3.org/2000/svg';
  let uid = 0; const nid = () => 'n' + (++uid);
  let model = [];        // ordered nodes: { id, type, stage, ...fields, x, y }
  let selected = null;
  let viewMode = 'list';

  const container = document.createElement('div'); container.className = 'stepbuilder';
  const bar = document.createElement('div'); bar.className = 'sbtoolbar';
  const mkTab = (txt, testid) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'sbtab'; b.textContent = txt; b.dataset.testid = testid; return b; };
  const listBtn = mkTab('☰ List', 'view-list'); const canvasBtn = mkTab('▦ Canvas', 'view-canvas');
  const addBtn = document.createElement('button'); addBtn.type = 'button'; addBtn.className = 'ghost'; addBtn.textContent = '＋ Add step'; addBtn.dataset.testid = 'add-step';
  bar.append(listBtn, canvasBtn, addBtn);
  const cards = document.createElement('div'); cards.className = 'stepcards';
  const canvas = document.createElement('div'); canvas.className = 'jcanvas-edit'; canvas.dataset.testid = 'journey-canvas-edit'; canvas.hidden = true;
  const hidden = document.createElement('textarea'); hidden.name = field.name; hidden.style.display = 'none';
  const adv = document.createElement('details'); adv.className = 'stepjson';
  const sum = document.createElement('summary'); sum.textContent = 'Advanced: edit as JSON';
  const jsonArea = document.createElement('textarea'); jsonArea.rows = 6; jsonArea.dataset.testid = 'steps-json';
  adv.append(sum, jsonArea);
  container.append(bar, cards, canvas, adv, hidden);

  const inp = (ph, v) => { const i = document.createElement('input'); i.placeholder = ph; i.value = v ?? ''; return i; };
  const ta = (ph, v) => { const t = document.createElement('textarea'); t.rows = 2; t.placeholder = ph; t.value = v ?? ''; return t; };
  const selEl = (opts, v) => { const s = document.createElement('select'); for (const o of opts) s.append(new Option(o, o)); if (v) s.value = v; return s; };
  const row = (label, el, wide) => { const l = document.createElement('label'); l.className = 'stepfield' + (wide ? ' wide' : ''); const s = document.createElement('span'); s.textContent = label; l.append(s, el); return l; };

  // ---- model <-> steps ----
  function toStep(n) {
    const s = { type: n.type };
    if (n.type !== 'exit' && n.stage) s.stage = n.stage;
    if (n.type === 'message') { s.channel = n.channel || 'inApp'; if (n.subject) s.subject = n.subject; if (n.content) s.content = n.content; }
    else if (n.type === 'wait') { if (n.days) s.days = Number(n.days); if (n.hours) s.hours = Number(n.hours); }
    else if (n.type === 'waitForEvent') { if (n.event) s.event = n.event; if (n.state) s.state = n.state; if (n.days) s.days = Number(n.days); if (n.tsub || n.tcon) s.onTimeout = { subject: n.tsub, content: n.tcon }; }
    else if (n.type === 'decision') { if (n.inSegment) s.inSegment = n.inSegment; if (n.tsub || n.tcon) s.then = { subject: n.tsub, content: n.tcon }; if (n.esub || n.econ) s.else = { subject: n.esub, content: n.econ }; }
    return s;
  }
  function fromStep(s, i) {
    const n = { id: nid(), type: s.type || 'message', stage: s.stage, x: 24, y: 20 + i * 118 };
    if (n.type === 'message') { n.channel = s.channel || 'inApp'; n.subject = s.subject; n.content = s.content; }
    else if (n.type === 'wait') { n.days = s.days; n.hours = s.hours; }
    else if (n.type === 'waitForEvent') { n.event = s.event; n.state = s.state; n.days = s.days; n.tsub = s.onTimeout && s.onTimeout.subject; n.tcon = s.onTimeout && s.onTimeout.content; }
    else if (n.type === 'decision') { n.inSegment = s.inSegment; n.tsub = s.then && s.then.subject; n.tcon = s.then && s.then.content; n.esub = s.else && s.else.subject; n.econ = s.else && s.else.content; }
    return n;
  }
  function serialize() { const steps = model.map(toStep); hidden.value = JSON.stringify(steps); jsonArea.value = JSON.stringify(steps, null, 2); }
  function loadSteps(steps) { model = (Array.isArray(steps) ? steps : []).map(fromStep); serialize(); }
  function nodeLabel(n) {
    return n.type === 'message' ? (n.subject || 'message')
      : n.type === 'wait' ? ('wait ' + (['days', 'hours'].filter((k) => n[k]).map((k) => n[k] + k[0]).join(' ') || ''))
      : n.type === 'waitForEvent' ? ('wait for ' + (n.event || 'event'))
      : n.type === 'decision' ? ('decision: ' + (n.inSegment || '')) : n.type;
  }

  // ---- shared field editor (binds directly to the node object) ----
  function nodeFields(node) {
    const rows = [];
    const bind = (key, el) => el.addEventListener('input', () => { node[key] = el.value.trim() || undefined; serialize(); refreshLabels(); });
    // a message-bearing row also gets the personalization token chips + {{ autocomplete
    const msgRow = (label, el, wide) => { const r = row(label, el, wide); r.append(tokenChips(el)); return r; };
    const t = node.type;
    if (t !== 'exit') { const i = inp('e.g. Welcome', node.stage); bind('stage', i); rows.push(row('Stage (label)', i)); }
    if (t === 'message') {
      const ch = selEl(['inApp', 'email', 'sms', 'push', 'whatsapp'], node.channel || 'inApp'); ch.addEventListener('change', () => { node.channel = ch.value; serialize(); }); rows.push(row('Channel', ch));
      const su = inp('Subject line — type {{ for a name', node.subject); bind('subject', su); rows.push(msgRow('Subject', su));
      const co = ta('Message body — {{ inserts a name, {code} a promo code', node.content); bind('content', co); rows.push(msgRow('Message', co, true));
    } else if (t === 'wait') {
      const d = inp('Days', node.days); bind('days', d); const h = inp('Hours (optional)', node.hours); bind('hours', h);
      rows.push(row('Wait — days', d), row('Wait — hours', h));
    } else if (t === 'waitForEvent') {
      const ev = inp('e.g. ProductOrderStateChangeEvent', node.event); bind('event', ev);
      const st = inp('e.g. completed (optional)', node.state); bind('state', st);
      const d = inp('Timeout — days', node.days); bind('days', d);
      const ts = inp('If it never comes — subject', node.tsub); bind('tsub', ts);
      const tc = ta('If it never comes — message', node.tcon); bind('tcon', tc);
      rows.push(row('Wait for event', ev), row('State', st), row('Timeout (days)', d), msgRow('On timeout — subject', ts), msgRow('On timeout — message', tc, true));
    } else if (t === 'decision') {
      const sg = inp('Insight segment, e.g. Devices', node.inSegment); bind('inSegment', sg);
      const ts = inp('If in segment — subject', node.tsub); bind('tsub', ts);
      const tc = ta('If in segment — message', node.tcon); bind('tcon', tc);
      const es = inp('Otherwise — subject', node.esub); bind('esub', es);
      const ec = ta('Otherwise — message', node.econ); bind('econ', ec);
      rows.push(row('Decide on segment', sg), msgRow('Then — subject', ts), msgRow('Then — message', tc, true), msgRow('Otherwise — subject', es), msgRow('Otherwise — message', ec, true));
    }
    return rows;
  }

  // ---- LIST view ----
  function renderList() {
    cards.replaceChildren();
    model.forEach((node) => {
      const card = document.createElement('div'); card.className = 'stepcard'; card.dataset.testid = 'step-card';
      const head = document.createElement('div'); head.className = 'stephead';
      const ts = selEl(TYPES, node.type); ts.className = 'steptype';
      ts.addEventListener('change', () => { node.type = ts.value; serialize(); renderList(); });
      const mk = (txt, cls, fn) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'ghost' + (cls || ''); b.textContent = txt; b.addEventListener('click', fn); return b; };
      const idx = () => model.indexOf(node);
      const up = mk('↑', '', () => { const i = idx(); if (i > 0) { model.splice(i - 1, 0, model.splice(i, 1)[0]); serialize(); renderList(); } });
      const dn = mk('↓', '', () => { const i = idx(); if (i < model.length - 1) { model.splice(i + 1, 0, model.splice(i, 1)[0]); serialize(); renderList(); } });
      const rm = mk('✕', ' danger', () => { model = model.filter((n) => n !== node); serialize(); renderList(); }); rm.dataset.testid = 'remove-step';
      head.append(ts, up, dn, rm);
      const body = document.createElement('div'); body.className = 'stepbody'; body.append(...nodeFields(node));
      card.append(head, body); cards.append(card);
    });
  }

  // ---- CANVAS view ----
  let surface = null;
  function refreshLabels() { if (!surface) return; surface.querySelectorAll('.cnode').forEach((el) => { const n = model.find((m) => m.id === el.dataset.id); const lab = el.querySelector('.cnlabel'); if (n && lab) lab.textContent = nodeLabel(n); }); }
  function drawEdges() {
    if (!surface) return; const svg = surface.querySelector('svg.cedges'); if (!svg) return;
    svg.replaceChildren(); const w = surface.getBoundingClientRect();
    for (let i = 0; i < model.length - 1; i++) {
      const a = surface.querySelector('.cnode[data-id="' + model[i].id + '"]'); const b = surface.querySelector('.cnode[data-id="' + model[i + 1].id + '"]');
      if (!a || !b) continue; const ra = a.getBoundingClientRect(); const rb = b.getBoundingClientRect();
      const x1 = ra.left - w.left + ra.width / 2; const y1 = ra.bottom - w.top; const x2 = rb.left - w.left + rb.width / 2; const y2 = rb.top - w.top; const m = (y1 + y2) / 2;
      const p = document.createElementNS(SVGNS, 'path'); p.setAttribute('d', 'M' + x1 + ',' + y1 + ' C' + x1 + ',' + m + ' ' + x2 + ',' + m + ' ' + x2 + ',' + y2); p.setAttribute('class', 'cedge'); svg.append(p);
    }
    svg.setAttribute('width', surface.scrollWidth); svg.setAttribute('height', surface.scrollHeight);
  }
  function addNode(type) { const last = model[model.length - 1]; const n = fromStep({ type }, model.length); n.x = last ? (last.x || 24) : 24; n.y = last ? (last.y || 20) + 118 : 20; model.push(n); selected = n; serialize(); renderCanvas(); }
  function connectAfter(src, tgt) { if (src === tgt) return; model = model.filter((n) => n !== tgt); const i = model.indexOf(src); model.splice(i + 1, 0, tgt); serialize(); renderCanvas(); }
  function startDrag(e, node, el) {
    if (e.button !== 0) return; e.preventDefault();
    const sx = e.clientX; const sy = e.clientY; const ox = node.x || 0; const oy = node.y || 0;
    const mv = (ev) => { node.x = Math.max(0, ox + ev.clientX - sx); node.y = Math.max(0, oy + ev.clientY - sy); el.style.left = node.x + 'px'; el.style.top = node.y + 'px'; drawEdges(); };
    const up = () => { document.removeEventListener('mousemove', mv); document.removeEventListener('mouseup', up); };
    document.addEventListener('mousemove', mv); document.addEventListener('mouseup', up);
  }
  function startConnect(e, src) {
    e.preventDefault(); e.stopPropagation();
    const svg = surface.querySelector('svg.cedges'); const w = surface.getBoundingClientRect();
    const srcEl = surface.querySelector('.cnode[data-id="' + src.id + '"]'); const ra = srcEl.getBoundingClientRect();
    const x1 = ra.left - w.left + ra.width / 2; const y1 = ra.bottom - w.top;
    const tmp = document.createElementNS(SVGNS, 'path'); tmp.setAttribute('class', 'cedge tmp'); svg.append(tmp);
    const mv = (ev) => { tmp.setAttribute('d', 'M' + x1 + ',' + y1 + ' L' + (ev.clientX - w.left) + ',' + (ev.clientY - w.top)); };
    const up = (ev) => {
      document.removeEventListener('mousemove', mv); document.removeEventListener('mouseup', up); tmp.remove();
      const t = document.elementFromPoint(ev.clientX, ev.clientY); const tn = t && t.closest('.cnode');
      if (tn) { const tgt = model.find((n) => n.id === tn.dataset.id); if (tgt && tgt !== src) connectAfter(src, tgt); }
    };
    document.addEventListener('mousemove', mv); document.addEventListener('mouseup', up);
  }
  function renderCanvas() {
    canvas.replaceChildren();
    const pal = document.createElement('div'); pal.className = 'cpalette';
    const hint = document.createElement('span'); hint.className = 'chint'; hint.textContent = 'Add:'; pal.append(hint);
    TYPES.forEach((t) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'ghost'; b.textContent = '+ ' + t; b.dataset.testid = 'palette-' + t; b.addEventListener('click', () => addNode(t)); pal.append(b); });
    surface = document.createElement('div'); surface.className = 'csurface'; surface.dataset.testid = 'canvas-surface';
    const svg = document.createElementNS(SVGNS, 'svg'); svg.setAttribute('class', 'cedges'); surface.append(svg);
    let maxY = 0;
    model.forEach((node) => {
      const el = document.createElement('div'); el.className = 'cnode cnode-' + node.type + (node === selected ? ' sel' : ''); el.dataset.testid = 'cnode'; el.dataset.id = node.id;
      el.style.left = (node.x || 20) + 'px'; el.style.top = (node.y || 20) + 'px'; maxY = Math.max(maxY, (node.y || 20) + 120);
      const head = document.createElement('div'); head.className = 'cnhead';
      const ty = document.createElement('span'); ty.className = 'cntype'; ty.textContent = node.type;
      const del = document.createElement('button'); del.type = 'button'; del.className = 'cndel'; del.dataset.testid = 'cnode-del'; del.textContent = '✕';
      del.addEventListener('click', (e) => { e.stopPropagation(); model = model.filter((n) => n !== node); if (selected === node) selected = null; serialize(); renderCanvas(); });
      head.append(ty, del);
      const lab = document.createElement('div'); lab.className = 'cnlabel'; lab.textContent = nodeLabel(node);
      const pin = document.createElement('div'); pin.className = 'cport in';
      const pout = document.createElement('div'); pout.className = 'cport out'; pout.dataset.testid = 'cport-out';
      el.append(head, lab, pin, pout); surface.append(el);
      el.addEventListener('click', (e) => { if (e.target.closest('.cport') || e.target.closest('.cndel')) return; selected = node; renderCanvas(); });
      head.addEventListener('mousedown', (e) => { if (e.target.closest('.cndel')) return; startDrag(e, node, el); });
      lab.addEventListener('mousedown', (e) => startDrag(e, node, el));
      pout.addEventListener('mousedown', (e) => startConnect(e, node));
    });
    surface.style.minHeight = Math.max(300, maxY) + 'px';
    canvas.append(pal, surface);
    if (selected && model.includes(selected)) {
      const panel = document.createElement('div'); panel.className = 'cpanel'; panel.dataset.testid = 'cnode-panel';
      const t = document.createElement('div'); t.className = 'cpanel-title'; t.textContent = 'Edit ' + selected.type + ' step';
      panel.append(t, ...nodeFields(selected)); canvas.append(panel);
    }
    drawEdges();
  }

  function setView(v) {
    viewMode = v;
    listBtn.classList.toggle('on', v === 'list'); canvasBtn.classList.toggle('on', v === 'canvas');
    cards.hidden = v !== 'list'; canvas.hidden = v !== 'canvas'; addBtn.hidden = v !== 'list';
    if (v === 'list') renderList(); else renderCanvas();
  }
  function rerender() { if (viewMode === 'list') renderList(); else renderCanvas(); }

  listBtn.addEventListener('click', () => setView('list'));
  canvasBtn.addEventListener('click', () => setView('canvas'));
  addBtn.addEventListener('click', () => { model.push(fromStep({ type: 'message' }, model.length)); serialize(); renderList(); });
  jsonArea.addEventListener('input', () => { try { const arr = JSON.parse(jsonArea.value); if (Array.isArray(arr)) { loadSteps(arr); rerender(); } } catch { /* mid-typing */ } });

  controls[field.name] = {
    get: () => hidden.value.trim() || undefined,
    set: (item) => { let steps = item[field.name]; if (typeof steps === 'string') { try { steps = JSON.parse(steps); } catch { steps = []; } } loadSteps(steps); rerender(); },
  };
  loadSteps([{ type: 'message' }]); setView('list');
  return [container];
}
