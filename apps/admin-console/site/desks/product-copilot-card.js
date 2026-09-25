/* Product Copilot — the proposal card and the chat pane. */
'use strict';

function copilotProposalCard(reply, context, log) {
  const proposal = reply.proposal;
  const repairs = copilotSanitize(proposal);
  const card = document.createElement('div');
  card.className = 'copilot-proposal';
  card.dataset.testid = 'copilot-proposal';
  const rows = [];
  for (const spec of proposal.specs || []) {
    rows.push(`spec · ${spec.name}`);
    // the fulfilment pattern the copilot proposes for this spec, and what the product lacks for it (CFS step 3)
    const fp = spec.fulfilmentPattern;
    if (fp && fp.cfsName) {
      rows.push(`fulfilment · ${fp.cfsName}${fp.reason ? ` — ${fp.reason}` : ''}`);
      for (const m of fp.missingConsumed || []) rows.push(`  ⚠ ${m.effect}`);
    } else {
      rows.push('fulfilment · no pattern proposed — pick one on the specification after it is created');
    }
    // the customer's choices, in the open: a configurable characteristic and its allowed values
    for (const c of spec.productSpecCharacteristic || []) {
      const vals = (c.productSpecCharacteristicValue || c.values || []).map((v) => (v && typeof v === 'object' ? v.value : v)).filter(Boolean);
      if (c.configurable !== false && vals.length > 1) rows.push(`choice · ${c.name}: ${vals.join(' / ')}`);
    }
  }
  for (const price of proposal.prices || []) {
    const conds = (price.prodSpecCharValueUse || []).map((c) => `${c.name} = ${(c.productSpecCharacteristicValue || []).map((v) => v.value != null ? v.value : `${v.valueFrom ?? '…'}–${v.valueTo ?? '…'}`).join('/')}`);
    const cond = conds.length ? ` (only when ${conds.join(' and ')})` : '';
    const uom = price.unitOfMeasure?.units ? ` per ${price.unitOfMeasure.amount || 1} ${price.unitOfMeasure.units}` : '';
    const pla = (price.pricingLogicAlgorithm || [])[0];
    const algo = pla ? (pla.plaSpecId === 'perUnitAbove' ? ` — ${pla.unitPrice} for each ${pla.characteristic} above ${pla.threshold}` : pla.plaSpecId === 'stepped' ? ` — stepped by ${pla.characteristic || 'quantity'}: ${(pla.tier || []).map((t) => `${t.valueFrom}-${t.valueTo} at ${t.price}`).join(', ')}` : ` — algorithm ${pla.plaSpecId}`) : '';
    const win = price.validFor ? ` — ${price.validFor.startDateTime ? 'from ' + String(price.validFor.startDateTime).slice(0, 10) : ''}${price.validFor.endDateTime ? ' until ' + String(price.validFor.endDateTime).slice(0, 10) : ''}` : '';
    const kind = price.priceType === 'recurring' ? '/month' : price.priceType === 'oneTime' ? ' one-time' : price.priceType === 'penalty' ? ' if leaving early (declining over the term)' : price.priceType === 'usage' ? ' per use' : '';
    rows.push(`price · ${price.name} — ${price.price?.value} ${price.price?.unit}${uom}${kind}${cond}${algo}${win}`);
  }
  for (const o of proposal.offerings || []) {
    const kids = (o.bundledChildren || []).length ? ` — bundle of ${o.bundledChildren.length}` : '';
    const term = (o.productOfferingTerm || [])[0]?.duration;
    const bind = term?.amount ? ` — ${term.amount}-${term.units || 'month'} commitment` : '';
    rows.push(`offering · ${o.name} [${(o.category || [])[0]?.name || 'no category'}]${kids}${bind}`);
    for (const rel of o.relationships || []) rows.push(`  ${rel.relationshipType} · ${rel.existingName || rel.offeringRef}${rel.role ? ` (${rel.role})` : ''}`);
  }
  for (const rule of proposal.pricingRules || []) {
    const scope = rule.audience === 'consumer' ? ' (private customers only)'
      : rule.audience === 'business' ? ' (business customers only)' : '';
    rows.push(`pricing rule · ${rule.name} — ${rule.adjustmentValue}`
      + (rule.adjustmentType === 'amount' ? '' : '%')
      + ` when the cart has ${(rule.whenCartHas || []).join(' + ')}${scope}`);
  }
  for (const er of proposal.experienceRules || []) {
    rows.push(`experience rule · ${er.name} — banner "${er.banner}" for guests browsing`
      + ` ${er.whenInterest}${er.pinOffering ? ` (pins ${er.pinOffering})` : ''}`);
  }
  const problems = copilotValidate(proposal, context);
  const hardProblems = problems.filter((x) => !x.includes('is new'));
  // P2 — the FORECAST RECEIPT: a proposal that reprices an existing offering
  // arrives pre-scored by the commercial simulator; the owner approves a
  // number, not a vibe. Absence is visible (no forecast line), never blocking.
  const fc = reply.forecast;
  // Everything below is model-authored (names, banners, assumptions) or comes
  // from the simulator — plain text in every case, so it is escaped on the way
  // into the card. Only the markup this file writes itself stays live.
  const forecastHtml = fc && fc.lines ? `<p class="copilot-forecast" data-testid="copilot-forecast">
      📊 <b>Forecast</b>: ${(fc.lines || []).map((l) =>
        `${esc(l.offeringName)}: ${esc(l.subscribers)} subs, ${esc(l.currentMonthly)}→${esc(l.proposedMonthly)} = `
        + `${l.annualRevenueDelta > 0 ? '+' : ''}${esc(l.annualRevenueDelta)} ${esc(fc.currency || '')}/yr`
        + (l.subscribersAtChurnRisk ? ` (${esc(l.subscribersAtChurnRisk)} at churn risk)` : '')).join(' · ')}
      <span class="dim" style="font-size:11px"> — ${esc((fc.assumptions || [])[0] || '')}</span></p>` : '';
  card.innerHTML = `<b>The copilot will create:</b>
    <ul>${rows.map((r) => `<li>${esc(r)}</li>`).join('')}</ul>
    ${forecastHtml}
    ${repairs.length ? `<p class="dim" style="font-size:12px">auto-repaired: ${repairs.map(esc).join('; ')}</p>` : ''}
    ${problems.length ? `<p class="copilot-warn">${problems.map(esc).join('<br>')}</p>` : ''}`;
  const actions = document.createElement('div');
  const create = document.createElement('button');
  create.className = 'primary';
  create.textContent = 'Yes — create it';
  create.dataset.testid = 'copilot-create';
  create.disabled = hardProblems.length > 0;
  const status = document.createElement('span');
  status.style.marginLeft = '10px';
  create.addEventListener('click', async () => {
    create.disabled = true;
    status.textContent = 'creating…';
    try {
      const made = await copilotExecute(proposal, context);
      status.innerHTML = '';
      const done = document.createElement('div');
      done.className = 'copilot-msg copilot-done';
      done.dataset.testid = 'copilot-created';
      done.innerHTML = `✓ Created ${made.length + (proposal.specs || []).length + (proposal.prices || []).length}
        catalog resources. ${made.map((o) =>
          `<a href="/shop/offering/${esc(o.id)}" target="_blank">${esc(o.name)} — see it in the shop</a>`).join(' · ')}`;
      for (const v of made.verdicts || []) {
        const line = document.createElement('div'); line.dataset.testid = 'copilot-governance'; line.style.cssText = 'margin-top:4px;font-size:13px';
        line.textContent = `Launch governance: ${v}`; done.append(line);
      }
      log.append(done);
      log.scrollTop = log.scrollHeight;
      copilotChat.push({ role: 'assistant', content: 'Created: ' + made.map((o) => o.name).join(', ') });
      copilotRemember();
    } catch (e) {
      create.disabled = false;
      status.textContent = 'rolled back — ' + e.message;
    }
  });
  actions.append(create, status);
  if (hardProblems.length) {
    // close the loop: the validator's findings go BACK to the model as a
    // corrective turn instead of dead-ending the owner on red text
    const fix = document.createElement('button');
    fix.className = 'ghost';
    fix.textContent = 'Ask the copilot to fix this';
    fix.dataset.testid = 'copilot-fix';
    fix.style.marginLeft = '8px';
    fix.addEventListener('click', () => {
      const input = document.getElementById('copilot-input');
      const send = document.getElementById('copilot-send');
      if (input && send) {
        input.value = 'That proposal failed validation: ' + hardProblems.join('; ')
          + '. Please revise it — remember a proposal needs at least one offering.';
        send.click();
      }
    });
    actions.append(fix);
  }
  card.append(actions);
  return card;
}

async function renderProductCopilot() {
  const panel = copilotPanel();
  panel.replaceChildren();
  const context = await copilotCatalogContext();

  const intro = document.createElement('p');
  intro.className = 'dim';
  intro.style.cssText = 'font-size:13px;margin:6px 0 10px';
  intro.textContent = 'Describe the product you want to sell — the copilot models it '
    + 'against this catalog and creates it when you confirm. It proposes; you decide.';
  const log = document.createElement('div');
  log.id = 'copilot-log';
  const bar = document.createElement('div');
  bar.className = 'staffbar';
  const input = document.createElement('input');
  input.placeholder = 'e.g. I want to sell a streaming service…';
  input.id = 'copilot-input';
  input.style.flex = '1';
  const send = document.createElement('button');
  send.className = 'primary';
  send.textContent = 'Send';
  send.id = 'copilot-send';
  // a fresh product deserves a fresh conversation — the history rides into
  // every request (context cost) and steers the model (old asks bleed in)
  const clear = document.createElement('button');
  clear.className = 'ghost';
  clear.textContent = 'Clear chat';
  clear.id = 'copilot-clear';
  clear.type = 'button';
  clear.addEventListener('click', () => {
    copilotChat.length = 0;
    copilotRemember();
    log.replaceChildren();
    input.focus();
  });

  const bubble = (cls, text) => {
    const b = document.createElement('div');
    b.className = 'copilot-msg ' + cls;
    b.textContent = text;
    log.append(b);
    log.scrollTop = log.scrollHeight;
    return b;
  };
  for (const m of copilotChat) {
    if (m.role === 'user') bubble('copilot-user', m.content);
    else bubble('copilot-ai', m.content);
  }

  async function submit() {
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    bubble('copilot-user', text);
    copilotChat.push({ role: 'user', content: text });
    copilotRemember();
    const thinking = bubble('copilot-ai', '…');
    try {
      const res = await authFetch('/ai/v1/productCopilot', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: copilotChat, catalog: context }),
      });
      if (!res.ok) {
        const problem = await res.json().catch(() => null);
        throw new Error(problem?.message
          ? problem.message + ' — try sending your message again, or rephrase it.'
          : 'copilot unavailable (HTTP ' + res.status + ')');
      }
      const reply = await res.json();
      thinking.textContent = reply.message;
      copilotChat.push({ role: 'assistant', content: reply.message });
      copilotRemember();
      if (reply.kind === 'proposal' && reply.proposal) {
        log.append(copilotProposalCard(reply, context, log));
        log.scrollTop = log.scrollHeight;
      }
    } catch (e) {
      thinking.textContent = e.message;
      thinking.classList.add('copilot-warn');
    }
  }
  send.addEventListener('click', submit);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(); });

  bar.append(input, send, clear);
  attachCopilotMic(input, bar, send); // speak the product idea instead of typing it
  panel.append(intro, log, bar);
  input.focus();
}
