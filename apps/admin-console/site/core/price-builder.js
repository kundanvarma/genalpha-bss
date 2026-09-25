/* Two of the price form's built controls: what the price is charged per, and
 * the named algorithm it uses. The third, the configured choice a price is
 * conditioned on, is core/price-conditions.js — it loads after this file and
 * shares the helpers below.
 *
 * All three were JSON boxes. A commercial product manager cannot write a TMF
 * payload, and asking them to is how a catalog acquires prices nobody dares
 * edit. Each control writes EXACTLY what the box wrote for the same input:
 * rows merge their edits into the entry that was read, so a key this form does
 * not model (an entry's own `name`, say) survives a round trip, and a value
 * that came off the wire as a number goes back as a number.
 *
 * Only the two algorithms ADR 0008 allows are offered. There is no free-form
 * formula anywhere, and this form is not the place one arrives.
 */
'use strict';

/* Every characteristic a price may be conditioned on, with the values the
 * specification declares for it. The link from a price to a specification runs
 * through the offerings that sell it, so: the specifications of the offerings
 * that reference THIS price when it is one being edited, and every
 * specification otherwise — a new price has no offering yet. Values keep the
 * type the catalog stores (a number stays a number, "5+" stays text). */
async function catalogChoices() {
  const [specs, offerings] = await Promise.all([
    loadPicklist({ resource: 'productSpecification' }),
    loadPicklist({ resource: 'productOffering' }),
  ]);
  return { specs: Array.isArray(specs) ? specs : [], offerings: Array.isArray(offerings) ? offerings : [] };
}

/* name → { values: [typed…], specs: [spec name…] } for the specifications in scope. */
function choicesFor(catalog, priceId) {
  const selling = priceId
    ? catalog.offerings.filter((o) => (o.productOfferingPrice || []).some((p) => p && p.id === priceId))
    : [];
  const specIds = new Set(selling.map((o) => (o.productSpecification || {}).id).filter(Boolean));
  const scope = specIds.size ? catalog.specs.filter((s) => specIds.has(s.id)) : catalog.specs;
  const out = new Map();
  for (const spec of scope) {
    for (const c of spec.productSpecCharacteristic || []) {
      if (!c || !c.name) continue;
      const entry = out.get(c.name) || { values: [], specs: [] };
      for (const v of c.productSpecCharacteristicValue || []) {
        if (v && v.value !== undefined && !entry.values.some((x) => x === v.value)) entry.values.push(v.value);
      }
      if (!entry.specs.includes(spec.name)) entry.specs.push(spec.name);
      out.set(c.name, entry);
    }
  }
  return out;
}

/* A <datalist> of suggestions: the operator may still type something the
 * catalog knows and this console does not. */
function suggestions(id, values) {
  const list = document.createElement('datalist');
  list.id = id;
  for (const v of values) list.append(new Option(String(v), String(v)));
  return list;
}

/* The typed value behind what was typed: "100" becomes 100 when the
 * specification declares the number 100, and stays "100" when it does not. */
function typedValue(text, known) {
  const hit = (known || []).find((v) => String(v) === text);
  return hit === undefined ? text : hit;
}

function labelled(text, node, width) {
  const wrap = document.createElement('label');
  wrap.style.cssText = `display:flex;flex-direction:column;gap:2px;font-size:.78rem;color:var(--dim,#666)${width ? `;width:${width}` : ''}`;
  const span = document.createElement('span');
  span.textContent = text;
  wrap.append(span, node);
  return wrap;
}

function numberInput(placeholder, width = '6rem') {
  const input = document.createElement('input');
  input.type = 'number';
  input.step = 'any';
  input.placeholder = placeholder;
  input.style.width = width;
  return input;
}

function rowBox() {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;align-items:flex-end;border:1px solid var(--line,#ddd);border-radius:8px;padding:8px';
  return box;
}

function ghostButton(text, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'ghost';
  b.textContent = text;
  b.addEventListener('click', onClick);
  return b;
}

/* ---------------------------------------------------------------- per unit --
 * "The price applies per 1 seat." Blank amount = a flat price, exactly as the
 * empty box meant. */
function unitOfMeasureControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;gap:6px;align-items:center;flex-wrap:wrap';
  const amount = numberInput('1', '5rem');
  const units = document.createElement('input');
  units.placeholder = 'seat';
  units.style.width = '8rem';
  units.setAttribute('list', 'uom-units');
  const lead = document.createElement('span');
  lead.style.cssText = 'font-size:.85rem;color:var(--dim,#666)';
  lead.textContent = 'applies per';
  box.append(lead, amount, units, suggestions('uom-units', ['seat', 'user', 'line', 'month', 'GB', 'device', 'room']));
  let original = null;
  controls[field.name] = {
    get: () => {
      const a = amount.value.trim();
      const u = units.value.trim();
      if (!a && !u) return undefined;
      const out = { ...(original || {}) };
      if (a) out.amount = Number(a); else delete out.amount;
      if (u) out.units = u; else delete out.units;
      return out;
    },
    set: (item) => {
      original = item[field.name] || null;
      amount.value = original && original.amount !== undefined ? original.amount : '';
      units.value = original && original.units !== undefined ? original.units : '';
    },
  };
  return [box];
}

/* --------------------------------------------------------------- algorithm --
 * The two ADR 0008 names, and nothing else. A price with no algorithm is the
 * ordinary case and the form says so. */
function pricingAlgorithmControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  const kind = document.createElement('select');
  kind.append(new Option('No algorithm — the price as it stands', ''),
    new Option('Per unit above a threshold', 'perUnitAbove'),
    new Option('A tier table', 'stepped'));
  const body = document.createElement('div');
  body.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  box.append(labelled('How the amount is worked out', kind), body);

  /* The rule in a sentence, rewritten on every keystroke — a threshold rule
   * read back in words is how an operator catches it being the wrong way up. */
  const said = document.createElement('p');
  said.style.cssText = 'margin:0;font-size:.8rem;color:var(--dim,#666)';
  const say = () => {
    said.textContent = `The price as it stands, plus ${unitPrice || '…'} for every ${charName || 'unit'} above ${threshold || '…'}.`;
  };

  let original = null;      // the entry read off the wire, so its extra keys survive
  let passthrough = [];     // any further entries this form does not model
  let tiers = [];           // {valueFrom, valueTo, price, format, original}
  let charName = '';
  let threshold = '';
  let unitPrice = '';
  let names = new Map();

  function render() {
    body.replaceChildren();
    if (!kind.value) return;
    const char = document.createElement('input');
    char.setAttribute('list', 'pla-characteristics');
    char.placeholder = kind.value === 'stepped' ? 'quantity' : 'extraProfiles';
    char.value = charName;
    char.style.width = '12rem';
    char.addEventListener('input', () => { charName = char.value; say(); });
    const head = rowBox();
    head.append(labelled('What it counts', char), suggestions('pla-characteristics', [...names.keys(), 'quantity']));

    if (kind.value === 'perUnitAbove') {
      const t = numberInput('2');
      t.value = threshold;
      t.addEventListener('input', () => { threshold = t.value; say(); });
      const u = numberInput('10');
      u.value = unitPrice;
      u.addEventListener('input', () => { unitPrice = u.value; say(); });
      head.append(labelled('Free up to', t), labelled('Then each costs', u));
      body.append(head);
      body.append(said);
      say();   // and it keeps saying it as the three boxes are filled in
      return;
    }
    body.append(head);
    for (const tier of tiers) body.append(tierRow(tier));
    body.append(ghostButton('+ Tier', () => { tiers.push({ format: 'perUnit' }); render(); }));
  }

  function tierRow(tier) {
    const row = rowBox();
    const from = numberInput('1', '5rem');
    from.value = tier.valueFrom ?? '';
    from.addEventListener('input', () => { tier.valueFrom = from.value; });
    const to = numberInput('10', '5rem');
    to.value = tier.valueTo ?? '';
    to.addEventListener('input', () => { tier.valueTo = to.value; });
    const price = numberInput('20');
    price.value = tier.price ?? '';
    price.addEventListener('input', () => { tier.price = price.value; });
    const format = document.createElement('select');
    format.append(new Option('each', 'perUnit'), new Option('flat for the band', 'flat'));
    format.value = tier.format || 'perUnit';
    format.addEventListener('change', () => { tier.format = format.value; });
    row.append(labelled('From', from), labelled('To', to), labelled('Price', price), labelled('Charged', format),
      ghostButton('✕', () => { tiers = tiers.filter((t) => t !== tier); render(); }));
    return row;
  }

  kind.addEventListener('change', render);
  catalogChoices().then((catalog) => { names = choicesFor(catalog, editingId); render(); });

  controls[field.name] = {
    get: () => {
      if (!kind.value) return passthrough.length ? passthrough : undefined;
      const entry = { ...(original || {}) };
      entry.plaSpecId = kind.value;
      if (charName.trim()) entry.characteristic = charName.trim(); else delete entry.characteristic;
      if (kind.value === 'perUnitAbove') {
        delete entry.tier;
        if (threshold !== '') entry.threshold = Number(threshold); else delete entry.threshold;
        if (unitPrice !== '') entry.unitPrice = Number(unitPrice); else delete entry.unitPrice;
      } else {
        delete entry.threshold;
        delete entry.unitPrice;
        entry.tier = tiers.filter((t) => t.price !== '' && t.price !== undefined).map((t) => {
          const out = { ...(t.original || {}) };
          if (t.valueFrom !== '' && t.valueFrom !== undefined) out.valueFrom = Number(t.valueFrom); else delete out.valueFrom;
          if (t.valueTo !== '' && t.valueTo !== undefined) out.valueTo = Number(t.valueTo); else delete out.valueTo;
          out.price = Number(t.price);
          if (t.format === 'flat') out.format = 'flat'; else if (out.format !== undefined || t.format === 'perUnit') out.format = 'perUnit';
          return out;
        });
      }
      return [entry, ...passthrough];
    },
    set: (item) => {
      const all = Array.isArray(item[field.name]) ? item[field.name] : [];
      const mine = all.find((e) => e && (e.plaSpecId === 'perUnitAbove' || e.plaSpecId === 'stepped'));
      passthrough = all.filter((e) => e !== mine);
      original = mine || null;
      kind.value = mine ? mine.plaSpecId : '';
      charName = mine && mine.characteristic !== undefined ? String(mine.characteristic) : '';
      threshold = mine && mine.threshold !== undefined ? String(mine.threshold) : '';
      unitPrice = mine && mine.unitPrice !== undefined ? String(mine.unitPrice) : '';
      tiers = ((mine || {}).tier || []).map((t) => ({
        valueFrom: t.valueFrom, valueTo: t.valueTo, price: t.price, format: t.format || 'perUnit', original: t,
      }));
      render();
    },
  };
  return [box];
}

