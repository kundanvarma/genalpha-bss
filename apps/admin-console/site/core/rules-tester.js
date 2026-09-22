/* Rules dry-run on the policy page. */
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
