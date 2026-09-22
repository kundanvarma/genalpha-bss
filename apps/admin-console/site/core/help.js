/* Contextual help: the shelf, Ask Copilot, the help drawer, knowledge gaps. */
'use strict';

/* ---------------- Contextual help: the shelf for this pane ----------------
 * Layer 1 of help, zero tokens: the published articles tagged for the pane
 * the user is on (pane:<path>), audience-gated by the server from the token —
 * a customer never sees a product how-to, a CSR never sees an approval
 * how-to. Search (layer 2) and Ask (layer 3, metered) live in the same drawer. */
function helpButtonFor(resource) {
  const head = document.querySelector('.panel-head');
  if (!head) return;
  let btn = document.getElementById('help-button');
  if (!btn) {
    btn = document.createElement('button');
    btn.id = 'help-button'; btn.type = 'button'; btn.className = 'ghost help-button'; btn.title = 'Help for this page';
    btn.dataset.testid = 'help-button'; btn.textContent = '?';
    btn.addEventListener('click', () => openHelpDrawer(active));
    head.append(btn);
  }
  btn.dataset.pane = resource.path;
  const drawer = document.getElementById('help-drawer');
  if (drawer && !drawer.hidden) openHelpDrawer(resource);
  askCopilotButtonFor(resource);
}

/* "Ask Copilot" — the product copilot as a contextual action, not a destination (Ivan's
 * navigation paper, 21 Sep): on every Catalog & Pricing page a button opens the same chat
 * in the reading drawer, over the work in front of you; the copilot tab stub stays for the
 * palette and the suites. The chat knows which page it was asked from. */
function askCopilotButtonFor(resource) {
  const head = document.querySelector('.panel-head');
  let btn = document.getElementById('ask-copilot');
  const ws = WORKSPACES.find((w) => w.tabs.includes(resource.path));
  const here = Boolean(ws && (ws.quiet || []).includes('copilot') && resource.path !== 'copilot' && visible.some((r) => r.path === 'copilot'));
  if (!here) { if (btn) btn.hidden = true; return; }
  if (!btn) {
    btn = document.createElement('button');
    btn.id = 'ask-copilot'; btn.type = 'button'; btn.className = 'ghost ask-copilot'; btn.textContent = 'Ask Copilot';
    btn.title = 'Describe a product in words — the copilot proposes it against this catalog; you decide';
    btn.dataset.testid = 'ask-copilot';
    btn.addEventListener('click', openCopilotDrawer);
    head.insertBefore(btn, document.getElementById('help-button'));
  }
  btn.hidden = false;
}
async function openCopilotDrawer() {
  const d = openSideDrawer('Product copilot');
  d.classList.add('copilot-drawer');
  const host = document.createElement('div'); host.id = 'copilot-drawer-host'; d.append(host);
  desk('copilot.drawer', active.path);
  copilotHost = host;
  try { await renderProductCopilot(); } finally { copilotHost = null; }
  const panel = host.querySelector('#copilot-panel');
  const note = document.createElement('p'); note.className = 'dim'; note.style.cssText = 'font-size:12px;margin:0 0 6px';
  note.textContent = `Asked from ${active.title}. Your work stays where it is; close this drawer to return.`;
  panel?.prepend(note);
  setTimeout(() => document.getElementById('copilot-input')?.focus(), 120);
}

async function openHelpDrawer(resource) {
  let drawer = document.getElementById('help-drawer');
  if (!drawer) {
    drawer = document.createElement('aside'); drawer.id = 'help-drawer'; drawer.className = 'help-drawer'; drawer.dataset.testid = 'help-drawer';
    document.body.append(drawer);
  }
  drawer.hidden = false;
  drawer.replaceChildren();
  const head = document.createElement('div'); head.className = 'help-head';
  const h = document.createElement('h2'); h.textContent = `Help: ${resource.title}`;
  const close = document.createElement('button'); close.className = 'ghost'; close.textContent = '×'; close.title = 'Close'; close.dataset.testid = 'help-close';
  close.addEventListener('click', () => { drawer.hidden = true; });
  head.append(h, close);
  const search = document.createElement('input'); search.placeholder = 'Search all help…'; search.dataset.testid = 'help-search';
  const list = document.createElement('div'); list.dataset.testid = 'help-list';
  const askRow = document.createElement('div'); askRow.className = 'help-ask';
  const roles = (tokenClaims().realm_access || {}).roles || [];
  drawer.append(head, search, list);
  const render = (articles, label) => {
    list.replaceChildren();
    if (label) { const p = document.createElement('p'); p.className = 'dim'; p.textContent = label; list.append(p); }
    if (!articles.length) {
      const p = document.createElement('p'); p.className = 'dim'; p.dataset.testid = 'help-empty';
      p.textContent = search.value.trim() ? 'Nothing found. Try other words' + (roles.includes('ai:use') ? ', or ask.' : '.') : 'No help written for this page yet.';
      list.append(p);
      // The page can still say what it is for: its own goal line stands in for the missing article.
      const goal = search.value.trim() ? null : (resource.intro || PAGE_GOALS[resource.path]);
      if (goal) { const g = document.createElement('p'); g.dataset.testid = 'help-goal'; g.textContent = goal; list.append(g); }
    }
    for (const a of articles) {
      const d = document.createElement('details'); d.dataset.testid = 'help-article';
      const sum = document.createElement('summary'); sum.textContent = a.title; d.append(sum);
      const body = document.createElement('div'); body.className = 'help-body'; body.textContent = a.body; d.append(body);
      list.append(d);
    }
  };
  const shelf = async () => {
    const r = await authFetch(`${KNOWLEDGE_BASE}/article?tag=${encodeURIComponent('pane:' + resource.path)}`);
    render(r.ok ? await r.json() : [], null);
  };
  let timer = null;
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const q = search.value.trim();
      if (!q) { shelf(); return; }
      const r = await authFetch(`${KNOWLEDGE_BASE}/article?q=${encodeURIComponent(q)}`);
      render(r.ok ? await r.json() : [], `Results for “${q}”`);
    }, 300);
  });
  if (roles.includes('ai:use')) {
    const ask = document.createElement('button'); ask.className = 'primary'; ask.textContent = '✨ Ask'; ask.dataset.testid = 'help-ask';
    // The BSS explains itself: one press asks, in the page's own context, what the page is
    // for and how to use it — answered from its help articles and its manual section.
    const explain = document.createElement('button'); explain.className = 'ghost'; explain.textContent = 'Explain this page'; explain.dataset.testid = 'help-explain';
    const out = document.createElement('div'); out.dataset.testid = 'help-answer'; out.className = 'help-answer';
    const askIt = async (q) => {
      ask.disabled = true; explain.disabled = true; out.textContent = 'Asking…';
      const r = await authFetch('/ai/v1/knowledgeAsk', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: q, context: 'pane:' + resource.path }) });
      const a = await r.json().catch(() => ({}));
      out.replaceChildren();
      if (!r.ok) {
        // an honest face for the governor's answers: the AI kill-switch (403) and the budget ceiling (429)
        const p = document.createElement('p');
        p.textContent = r.status === 403 ? 'Ask is switched off for this tenant (AI kill-switch). The articles above still apply.'
          : r.status === 429 ? 'The AI budget for this period is used up. Search still works; Ask returns next period.'
          : `Ask did not answer (${r.status}${a.message ? ': ' + a.message : ''}).`;
        out.append(p);
      } else {
        // the model is asked for plain text; whatever markdown slips through is shown as words
        const lines = String(a.answer || 'The assistant returned no answer.')
          .replace(/```[a-z]*\n?/g, '').replace(/\*\*/g, '').replace(/`/g, '').split('\n');
        let para = [];
        const flush = () => { if (para.length) { const q = document.createElement('p'); q.textContent = para.join(' '); out.append(q); para = []; } };
        for (const raw of lines) {
          const line = raw.replace(/^#+\s*/, '').trim();
          if (!line) { flush(); continue; }
          if (/^(\d+[.)]|[-•*])\s+/.test(line)) { flush(); const q = document.createElement('p'); q.className = 'help-step'; q.textContent = line.replace(/^[-•*]\s+/, '• '); out.append(q); continue; }
          para.push(line);
        }
        flush();
      }
      if ((a.sources || []).length) { const s = document.createElement('p'); s.className = 'dim'; s.textContent = 'Sources: ' + a.sources.map((x) => x.title).join(' · ') + (a.cached ? ' · cached answer' : ''); out.append(s); }
      ask.disabled = false; explain.disabled = false;
    };
    ask.addEventListener('click', () => {
      const q = search.value.trim(); if (!q) { out.textContent = 'Type a question first.'; return; }
      askIt(q);
    });
    explain.addEventListener('click', () => {
      const q = `What is the "${resource.title}" page for, and how do I use it step by step?`;
      search.value = q;
      askIt(q);
    });
    askRow.append(ask, explain); drawer.append(askRow, out);
  }
  await shelf();
}


/* The Knowledge tab's to-do list: questions people asked that no article answered.
 * Writing the article moves the question from the metered Ask back to the free shelf. */
async function renderKnowledgeGaps(resource) {
  let box = document.getElementById('knowledge-gaps');
  if (resource.path !== 'article') { if (box) box.hidden = true; return; }
  if (!box) {
    box = document.createElement('div'); box.id = 'knowledge-gaps'; box.dataset.testid = 'knowledge-gaps';
    box.style.cssText = 'margin:0 0 12px;padding:10px 14px;border:1px dashed var(--line,#ccc);border-radius:10px;font-size:13px';
    (document.getElementById('tab-intro') || document.querySelector('.panel-head'))?.after(box);
  }
  box.hidden = false;
  box.textContent = 'Loading unanswered questions…';
  const gaps = await authFetch('/ai/v1/knowledgeGaps').then((r) => (r.ok ? r.json() : [])).catch(() => []);
  box.replaceChildren();
  const h = document.createElement('strong'); h.textContent = gaps.length ? `Questions nobody has written help for yet (${gaps.length})` : 'Every question people asked the ? drawer has an article.';
  box.append(h);
  if (gaps.length) {
    const why = document.createElement('div'); why.className = 'dim'; why.style.cssText = 'font-size:12px;margin:2px 0 4px';
    why.textContent = 'Someone typed these into a ? drawer and no article matched. Write it starts the article; Dismiss drops the question.';
    box.append(why);
  }
  if (gaps.length) {
    const ul = document.createElement('ul'); ul.style.cssText = 'margin:6px 0 0 18px;padding:0';
    for (const g of gaps.slice(0, 12)) {
      const li = document.createElement('li'); li.dataset.testid = 'knowledge-gap';
      li.textContent = `“${g.question}” — asked ${g.asked}×${g.context ? ` from ${g.context}` : ''}`;
      const b = document.createElement('button'); b.className = 'ghost'; b.textContent = 'Write it'; b.style.marginLeft = '8px';
      b.addEventListener('click', () => {
        const title = document.querySelector('#fields [name="title"]'); const tags = document.querySelector('#fields [name="tags"]');
        el('editor').hidden = false;
        if (title) title.value = g.question.charAt(0).toUpperCase() + g.question.slice(1) + (g.question.endsWith('?') ? '' : '?');
        if (tags && g.context) tags.value = g.context;
        title?.focus();
      });
      const d = document.createElement('button'); d.className = 'ghost'; d.textContent = 'Dismiss'; d.style.marginLeft = '4px'; d.dataset.testid = 'knowledge-gap-dismiss';
      d.addEventListener('click', async () => { await authFetch(`/ai/v1/knowledgeGaps/${g.id}`, { method: 'DELETE' }); renderKnowledgeGaps(resource); });
      li.append(b, d); ul.append(li);
    }
    box.append(ul);
  }
}
