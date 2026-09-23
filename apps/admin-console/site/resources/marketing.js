/* Resources 4/9 — journeys, campaigns, articles. */
'use strict';

RESOURCES.push(
  {
    path: 'journey',
    base: CAMPAIGN_BASE,
    title: 'Journeys',
    // Sequences as DATA: ordered message/wait steps, a conversion event
    // that is also the always-on exit rule, holdouts for honest lift.
    // Live-editable the way marketers expect (the Klaviyo model): edits
    // apply forward-only — parked enrollees get the new copy at their
    // next send, nobody is re-sent a step they already passed.
    noDelete: true,
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'status', label: 'Status — only Active runs; Draft/Scheduled sit before go-live, Archived hides it', kind: 'select', options: [
        { label: 'Active', value: 'active' },
        { label: 'Draft', value: 'draft' },
        { label: 'Scheduled', value: 'scheduled' },
        { label: 'Paused', value: 'paused' },
        { label: 'Archived', value: 'archived' },
      ] },
      { name: 'triggerEventType', label: 'Trigger event (leave blank for segment journeys)' },
      { name: 'triggerState', label: 'State filter — only fire on this state (optional), e.g. completed / shipped', placeholder: 'e.g. completed' },
      { name: 'segmentName', label: 'Segment (from Insight — enroll with the row action)' },
      { name: 'steps', label: 'Steps — build the journey stage by stage (add a message, a wait, a decision…). Edit as JSON under Advanced.', kind: 'stepbuilder', required: true },
      { name: 'conversionEvent', label: 'Conversion = exit rule (blank: completed orders)', placeholder: 'ProductOrderStateChangeEvent:completed' },
      { name: 'holdoutPercent', label: 'Holdout % — control group, no messages, measurable lift', kind: 'number', placeholder: '0' },
      { name: 'arms', label: 'Message variants (A/B arms) — a JSON list of {name, subject, content}; the first message step speaks in the arm each customer is dealt. Blank = one message.', kind: 'jsontext',
        placeholder: '[{"name":"A","subject":"…","content":"…"},{"name":"B","subject":"…","content":"…"}]' },
      { name: 'autoTune', label: 'Auto-tune — shift traffic to the winning arm once the evidence is clear (each arm keeps a 10 % floor; every shift is logged with its numbers)', kind: 'checkbox' },
      { name: 'priority', label: 'Priority — NBA arbitration (0 = always-on; higher wins when journeys compete for the same customer)', kind: 'number', placeholder: '0' },
    ],
    // Describe the journey and the copilot drafts the staged steps + copy into
    // the Steps box (governed + audited); the marketer reviews and Creates.
    aiAssist: {
      placeholder: 'Describe the journey, e.g. "welcome new fibre customers and nudge them to activate"',
      draft: async (brief) => {
        const res = await authFetch('/ai/v1/journeyDraft', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ brief, brandName: BRAND.brandName || undefined }),
        });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
        const draft = await res.json();
        controls.name?.set({ name: draft.name });
        controls.triggerEventType?.set({ triggerEventType: draft.triggerEventType });
        controls.holdoutPercent?.set({ holdoutPercent: draft.holdoutPercent });
        controls.steps.set({ steps: JSON.stringify(draft.steps, null, 2) });
        DESK.lastDraft = { form: 'journeys', steps: JSON.stringify(draft.steps, null, 2), name: draft.name };
      },
    },
    assemble: (body) => {
      let steps = body.steps;
      try { steps = JSON.parse(body.steps); } catch { /* the API rejects with a clear message */ }
      return { ...body, steps };
    },
    columns: ['name', 'status', 'segmentName', 'holdoutPercent', 'lastUpdate'],
    detail: async (item) => {
      const res = await authFetch(`${CAMPAIGN_BASE}/journey/${item.id}/stats`);
      const s = await res.json();
      return [{
        entered: s.entered, heldOut: s.heldOut,
        inFlight: Object.entries(s.activeAtStep || {}).map(([k, v]) => `${k}: ${v}`).join(' · ') || '—',
        converted: `treated ${s.conversions.treated} (${s.treatedRate ?? '—'}%) · holdout ${s.conversions.holdout} (${s.holdoutRate ?? '—'}%)`,
        lift: s.liftPoints != null ? `${s.liftPoints} points` : '— (needs a holdout)',
        note: [s.note, s.editNote].filter(Boolean).join(' · ') || `${s.completedUnconverted} completed unconverted`,
        revenue: s.revenue ? `treated ${s.revenue.treated} · holdout ${s.revenue.holdout}`
          + (s.revenue.liftPerCustomer != null ? ` · lift ${s.revenue.liftPerCustomer} per customer/month` : '') : '—',
        ...(s.arms ? {
          arms: s.arms.map((a) => `${a.name}: ${a.weight}% of traffic · ${a.enrolled} sent · ${a.converted} converted (${a.rate}%)`).join(' | '),
          tuning: (s.autoTune ? 'auto-tune on · ' : 'auto-tune off · ') + (() => {
            const last = (s.tuningLog || []).slice(-1)[0];
            return last ? `last decision ${last.decision}: ${last.why}` : 'no decision yet';
          })()
        } : {})
      }];
    },
    rowAction: {
      label: () => 'Enroll segment',
      apply: (item) => authFetch(`${CAMPAIGN_BASE}/journey/${item.id}/enroll`, { method: 'POST' }),
    },
    // BB1 — the visual canvas: the SAME journey object the API serves, drawn as
    // a node flow with live per-node counts (reached · active) from the funnel.
    // No draw-vs-run drift: what you see is exactly what runs.
    canvas: async (item) => {
      const s = await (await authFetch(`${CAMPAIGN_BASE}/journey/${item.id}/stats`)).json();
      const funnel = s.funnel || [];
      const steps = Array.isArray(item.steps) ? item.steps : [];
      const wrap = document.createElement('div');
      wrap.className = 'jcanvas';
      wrap.dataset.testid = 'journey-canvas';
      const waitLabel = (st) => ['days', 'hours', 'minutes', 'seconds']
        .filter((u) => st[u]).map((u) => `${st[u]} ${u}`).join(' ') || 'a while';
      const labelHtmlOf = (st) => st.type === 'message' ? esc(st.subject || 'message')
        : st.type === 'wait' ? `wait ${esc(waitLabel(st))}`
        : st.type === 'waitForEvent' ? `wait for ${esc(st.event || 'event')}`
        : (st.type === 'branch' || st.type === 'decision') ? `decision: ${esc(st.inSegment || '')}`
        : st.type === 'exit' ? 'exit' : esc(st.type || 'step');
      steps.forEach((st, i) => {
        const f = funnel[i] || {};
        const sends = st.type === 'message' || st.type === 'branch' || st.type === 'decision';
        const node = document.createElement('div');
        node.className = 'jnode jnode-' + (st.type || 'step');
        node.dataset.testid = 'canvas-node';
        const labelHtml = labelHtmlOf(st);   // already escaped inside labelHtmlOf
        node.innerHTML = `<div class="jtype">${esc(st.type || '')}</div>`
          + (st.stage ? `<span class="jstage">${esc(st.stage)}</span>` : '')
          + `<div class="jlabel">${labelHtml}</div>`
          + (sends ? `<span class="jcount" data-testid="canvas-count">reached ${esc(f.reached ?? 0)} · active ${esc(f.active ?? 0)}</span>` : '');
        wrap.append(node);
        if (i < steps.length - 1) {
          const arrow = document.createElement('div');
          arrow.className = 'jarrow';
          arrow.textContent = '↓';
          wrap.append(arrow);
        }
      });
      return wrap;
    },
  },
  {
    path: 'campaign',
    base: CAMPAIGN_BASE,
    title: 'Campaigns',
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'recipe', label: 'Recipe (optional) — prefills a proven retention play; edit freely', kind: 'recipe',
        options: CAMPAIGN_RECIPES },
      { name: 'name', label: 'Name', required: true },
      { name: 'triggerEventType', label: 'Trigger (event campaigns)', kind: 'select', options: TRIGGER_EVENTS },
      { name: 'segmentName', label: 'Segment (blast campaigns) — an Insight interest (e.g. Devices) or analytics audience', placeholder: 'leave blank for event campaigns' },
      { name: 'triggerState', label: 'State filter', placeholder: 'e.g. completed (optional)' },
      { name: 'promotionCode', label: 'Promo code to include', kind: 'codepick',
        base: PROMOTION_BASE, resource: 'promotion', attribute: 'code' },
      { name: 'messageSubject', label: 'Message subject — type {{ for a name', required: true, tokens: true },
      { name: 'messageContent', label: 'Message — {{ inserts a name, {code} the promo code', kind: 'longtext', required: true, tokens: true },
      { name: 'messageVariants', label: 'A/B arms (JSON, optional) — 2-4 of {name, subject, content}; treated customers split evenly and stats read per arm', kind: 'jsontext',
        placeholder: '[{"name":"A","subject":"…","content":"…"},{"name":"B","subject":"…","content":"…"}]' },
      { name: 'holdoutPercent', label: 'Holdout % — a control group that gets NO message, so lift can be measured', kind: 'number', placeholder: '0' },
      { name: 'conversionWindowDays', label: 'Conversion window (days)', kind: 'number', placeholder: '7' },
    ],
    assemble: (body) => {
      const { messageSubject, messageContent, ...rest } = body;
      return { ...rest, message: { subject: messageSubject, content: messageContent } };
    },
    columns: ['name', 'status', 'triggerEventType', 'segmentName', 'promotionCode', 'reached'],
    detail: async (item) => {
      const res = await authFetch(`${CAMPAIGN_BASE}/campaign/${item.id}/stats`);
      const s = await res.json();
      const row = {
        reached: s.reached, heldOut: s.heldOut,
        converted: `treated ${s.conversions.treated} (${s.treatedRate ?? '—'}%) · holdout ${s.conversions.holdout} (${s.holdoutRate ?? '—'}%)`,
        lift: s.liftPoints != null ? `${s.liftPoints} points` : '— (needs a holdout)',
        note: s.note || `${s.conversionWindowDays}-day window`,
      };
      if (s.revenue) {
        row.revenue = `treated ${s.revenue.treated} · holdout ${s.revenue.holdout}`
          + (s.revenue.liftPerCustomer != null ? ` · lift ${s.revenue.liftPerCustomer} per customer/month` : '');
      }
      if (s.arms) {
        row.abTest = s.arms.arms
          .map((a) => `${a.name}: ${a.conversions}/${a.sent}${a.rate != null ? ` (${a.rate}%)` : ''}`)
          .join(' · ');
        row.verdict = s.arms.verdict || '—';
      }
      return [row];
    },
    rowAction: {
      label: (item) => (item.status === 'active' ? 'Pause' : 'Resume'),
      apply: (item) => authFetch(`${CAMPAIGN_BASE}/campaign/${item.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: item.status === 'active' ? 'paused' : 'active' }),
      }),
    },
    augmentRow: async (item, cell) => {
      const res = await authFetch(`${CAMPAIGN_BASE}/campaign/${item.id}/execution`);
      const executions = await res.json();
      cell.textContent = `${executions.length} customer${executions.length === 1 ? '' : 's'}`;
    },
    // The intelligence component drafts; the marketer edits and saves.
    aiAssist: {
      placeholder: 'Brief for AI, e.g. "thank first-time buyers, warm tone"',
      draft: async (brief) => {
        const res = await authFetch('/ai/v1/campaignCopy', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            brief,
            brandName: BRAND.brandName || undefined,
            triggerEventType: controls.triggerEventType?.get(),
            promotionCode: controls.promotionCode?.get(),
          }),
        });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
        const copy = await res.json();
        controls.messageSubject.set({ messageSubject: copy.subject });
        controls.messageContent.set({ messageContent: copy.content });
      },
    },
  },
  {
    path: 'article',
    base: KNOWLEDGE_BASE,
    title: 'Help articles',
    intro: 'This is where help is WRITTEN. To read the help for the screen you are on, press the ? at the top right of any tab — it shows the articles tagged for that screen, gated by who may read them. Tags say where an article appears (pane:approvals, csr:tickets, shop:bills); the audience says who may read it.',
    // The library every channel reads: FAQs for customers, cheat-sheets for
    // CSRs, how-tos for product owners. Content is DATA — publish here and
    // the shop's Support page, the CSR desk and the ask-AI answer from it
    // immediately.
    fields: [
      { name: 'title', label: 'Title', required: true },
      { name: 'audience', label: 'Who is this for', kind: 'select', options: [
        { value: 'customer', label: 'Customers (shop Support page)' },
        { value: 'csr', label: 'CSRs (agent desk)' },
        { value: 'sales', label: 'Sales' },
        { value: 'productOwner', label: 'Product owners (console)' },
        { value: 'all', label: 'Everyone' },
      ] },
      { name: 'category', label: 'Category (e.g. Mobile data, Family, Catalog how-to)' },
      { name: 'tags', label: 'Search tags (comma-separated)' },
      { name: 'body', label: 'Article body', kind: 'longtext', required: true },
      { name: 'status', label: 'Status', kind: 'select', options: [
        { value: 'published', label: 'Published' },
        { value: 'draft', label: 'Draft (authors only)' },
      ] },
    ],
    columns: ['title', 'audience', 'category', 'status', 'lastUpdate'],
    detail: async (item) => {
      const res = await authFetch(`${KNOWLEDGE_BASE}/article/${item.id}`);
      const full = await res.json();
      return [{ audience: full.audience, category: full.category || '—', body: full.body }];
    },
  },
);
