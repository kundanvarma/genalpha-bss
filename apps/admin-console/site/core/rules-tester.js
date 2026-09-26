/* Two rule windows: the dry run on the policy page, and — the other direction —
 * the rules that reference an offering, on the offering page. */
'use strict';

/**
 * Rules dry-run: a sample order/cart in, the live engine's verdict out —
 * exactly the /evaluate and /price calls the order pipeline and billing make,
 * so what you see here is what a customer would get.
 */
function testerRow() {
  const wrap = document.createElement('div');
  wrap.className = 'field aiassist';
  const caption = document.createElement('span');
  caption.textContent = 'Dry run — test the enabled rules';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  // Each dry-run input carries a visible mini-label — placeholders vanish
  // the moment a value is typed, labels don't.
  const labelled = (labelText, input) => {
    const box = document.createElement('span');
    box.style.cssText = 'display:inline-flex;flex-direction:column;gap:2px;';
    const cap = document.createElement('span');
    cap.textContent = labelText;
    cap.style.cssText = 'font-size:10px;color:var(--dim,#8a979c);letter-spacing:0.04em;text-transform:uppercase;';
    box.append(cap, input);
    return box;
  };
  const offering = document.createElement('input');
  offering.placeholder = 'any offering';
  offering.id = 'test-offering';
  const qty = document.createElement('input');
  qty.type = 'number';
  qty.value = '1';
  qty.id = 'test-qty';
  qty.style.maxWidth = '70px';
  const subtotal = document.createElement('input');
  subtotal.type = 'number';
  subtotal.value = '100';
  subtotal.id = 'test-subtotal';
  subtotal.style.maxWidth = '100px';
  const verified = document.createElement('label');
  verified.className = 'small';
  const verifiedBox = document.createElement('input');
  verifiedBox.type = 'checkbox';
  verifiedBox.id = 'test-verified';
  verified.append(verifiedBox, ' verified customer');
  const orderBtn = document.createElement('button');
  orderBtn.type = 'button';
  orderBtn.className = 'ghost';
  orderBtn.id = 'test-order';
  orderBtn.textContent = 'Test order';
  const priceBtn = document.createElement('button');
  priceBtn.type = 'button';
  priceBtn.className = 'ghost';
  priceBtn.id = 'test-price';
  priceBtn.textContent = 'Test price';
  const result = document.createElement('div');
  result.className = 'small';
  result.id = 'test-result';

  const context = () => {
    const id = offering.value.trim();
    const quantity = Number(qty.value) || 1;
    return {
      offeringIds: id ? [id] : [],
      quantityByOffering: id ? { [id]: quantity } : {},
      maxLineQuantity: quantity,
      totalQuantity: quantity,
      lineCount: id ? 1 : 0,
      subtotal: Number(subtotal.value) || 0,
      verifiedIdentity: verifiedBox.checked,
    };
  };
  orderBtn.addEventListener('click', async () => {
    result.textContent = '…';
    try {
      const res = await authFetch(`${POLICY_BASE}/evaluate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ domain: 'order', context: context() }),
      });
      const v = await res.json();
      result.textContent = v.decision === 'deny'
        ? `✕ DENIED by "${v.ruleName}": ${v.message}`
        : '✓ ALLOWED — no enabled order rule blocks this';
    } catch (e) { result.textContent = 'dry run failed: ' + e.message; }
  });
  priceBtn.addEventListener('click', async () => {
    result.textContent = '…';
    try {
      const res = await authFetch(`${POLICY_BASE}/price`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ context: context() }),
      });
      const v = await res.json();
      const adj = (v.adjustments || [])
        .map((a) => `${a.label}: ${Number(a.amount) > 0 ? '+' : ''}${Number(a.amount).toFixed(2)}`)
        .join(' · ');
      result.textContent = adj
        ? `base ${Number(v.basePrice).toFixed(2)} → ${adj} → total ${Number(v.total).toFixed(2)}`
        : `no pricing rule matches — price stays ${Number(v.basePrice).toFixed(2)}`;
    } catch (e) { result.textContent = 'dry run failed: ' + e.message; }
  });

  row.append(labelled('Offering id', offering), labelled('Qty', qty), labelled(`Subtotal ${tenantCurrency()}`, subtotal), verified, orderBtn, priceBtn);
  wrap.append(caption, row, result);
  return wrap;
}

/* ---------------- The rules that reference an offering (#157) ----------------
 * A rule attaches to an offering by naming it inside its condition, so the
 * attachment is one-way in the model — and an offering was silent about the
 * 50 NOK off sitting on it. This is the reverse read: every pricing and
 * blocking rule that names this offering, INCLUDING the ones switched off,
 * because the state is the point. A disabled rule is what you are hunting when
 * you ask why nothing is happening; a live one is the warning you need before
 * retiring the offering.
 *
 * It reads the AUTHENTICATED /policyRule/referencing, never /price/teaser: the
 * teaser is the anonymous shop window, and a negotiated company rate must not
 * reach a reader without policy:read. Read-only on purpose — the Rules page
 * edits, this window only tells — and built with createElement + textContent,
 * so a rule someone named with markup prints instead of running. */
const RULE_EFFECT_WORDS = {
  adjust: 'Changes the price', deny: 'Blocks the order',
  allow: 'Permits the order', experience: 'Changes what the shop shows',
};

/**
 * The adjustment in words — "15% off", "a flat 50 added" — or nothing to say.
 *
 * NO CURRENCY. A rule stores a bare number; the currency is whatever the cart
 * it fires on is denominated in, and the tenant-config fallback is EUR. Early
 * on this panel printed "50 EUR off" beside the operator's own message saying
 * 50 NOK — a unit invented by a default, contradicting the sentence next to it.
 * "A flat 50" says it is an amount and not a percentage, and claims nothing the
 * rule does not hold; the operator's message carries the currency if it matters.
 */
function ruleAdjustmentWords(rule) {
  const value = Number(rule.adjustmentValue);
  if (!rule.adjustmentType || !Number.isFinite(value) || value === 0) return '';
  const size = rule.adjustmentType === 'percent' ? `${Math.abs(value)}%` : `a flat ${Math.abs(value)}`;
  return value < 0 ? `${size} off` : `${size} added`;
}

/** One rule as a sentence: its name, its state in a word, what it does, and the way to it. */
function offeringRuleRow(rule) {
  const row = document.createElement('li');
  row.dataset.testid = 'offering-rule';
  row.dataset.enabled = String(Boolean(rule.enabled));
  const head = document.createElement('div');
  head.className = 'offering-rule-head';
  const name = document.createElement('strong');
  name.textContent = rule.name || 'an unnamed rule';
  const state = document.createElement('span');
  // the WORD carries the state; the colour only agrees with it
  state.className = 'pill ' + (rule.enabled ? 'ok' : 'warn');
  state.textContent = rule.enabled ? 'Live' : 'Switched off';
  head.append(name, state);
  // `visible` is the rail's gated list: no dead button for an operator whose
  // roles hide the Rules page
  const page = visible.find((r) => r.path === 'policyRule');
  if (page) {
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'ghost';
    open.textContent = 'Open rule';
    open.addEventListener('click', () => {
      closeDrawer();
      active = page; offset = 0; listSortCol = null; stopEditing();
      listFilter = String(rule.name || '').toLowerCase(); // land on this rule, not page 1 of every rule
      sessionStorage.setItem('bss.console.tab', page.path);
      renderTabs(); loadList();
    });
    head.append(open);
  }
  const says = document.createElement('p');
  const words = [RULE_EFFECT_WORDS[rule.effect] || 'Applies to this offering', ruleAdjustmentWords(rule)]
    .filter(Boolean).join(' — ');
  says.textContent = rule.message ? `${words}. “${rule.message}”` : `${words}.`;
  row.append(head, says);
  return row;
}

function offeringRulesControl(field) {
  const box = document.createElement('div');
  box.className = 'offering-rules';
  box.dataset.testid = 'offering-rules';
  const line = (text) => { const p = document.createElement('p'); p.className = 'muted'; p.textContent = text; box.append(p); };
  async function render(item) {
    box.replaceChildren();
    if (!item || !item.id) { line('Save the offering first — then the rules that name it are listed here.'); return; }
    const res = await authFetch(`${POLICY_BASE}/policyRule/referencing?offeringId=${encodeURIComponent(item.id)}`,
      { headers: { 'Cache-Control': 'no-cache' } }).catch(() => null);
    if (res && (res.status === 401 || res.status === 403)) {
      line('Your roles do not include reading rules, so this panel stays shut. An administrator can grant rule access.');
      return;
    }
    const rules = res && res.ok ? await res.json().catch(() => null) : null;
    if (!Array.isArray(rules)) { line('The rules could not be read right now.'); return; }
    if (!rules.length) {
      line('No rule names this offering.');
    } else {
      const list = document.createElement('ul');
      list.className = 'offering-rule-list';
      for (const rule of rules) list.append(offeringRuleRow(rule));
      box.append(list);
    }
    // THE BLIND SPOT, SAID OUT LOUD. The rule form only shows its Item field for
    // "Price: discount when the cart has an item" — every other kind (a company's
    // negotiated deal, a campaign on a colour, a discount for everyone, a volume
    // deal) discounts this offering WITHOUT naming it, so nothing can list it
    // here. An empty panel saying "no rule prices this offering" would have been
    // a lie; it means "no rule names it", which is a different and smaller fact.
    line('Only rules that name this offering. A discount for everyone, a company’s negotiated deal or a'
      + ' campaign on a configured choice applies without naming one — look for those on the Rules page.'
      + ' Volume pricing sits on the price lines.');
  }
  // nothing here is ever submitted: a window, not a second rule editor
  controls[field.name] = { get: () => undefined, set: (item) => { render(item); } };
  render(null); // the + New form says what this will hold rather than showing an empty box
  return [box];
}
