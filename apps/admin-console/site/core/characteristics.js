/* The product specification's characteristics: one row each, never a JSON array.
 *
 * A row is a name, what kind of value it holds, the value or values, and
 * whether the customer chooses between them (TMF620 `configurable`). Names a
 * seam reads — a charging plan, a slice profile — are offered by their words
 * and stored by their technical name, so nobody spells `chargingSpecId`; any
 * other name is still allowed, because a specification may carry anything.
 *
 * Rows keep the characteristic they came from and merge edits into it, so the
 * keys this editor does not model — a description, a unit of measure, which
 * value is the default — survive untouched. A characteristic whose values are
 * a RANGE is shown read-only in words rather than flattened: the per-unit
 * pricing algorithm reads those bounds, and an editor that quietly dropped
 * them would be worse than the box it replaces.
 *
 * One file per complex control is this console's own shape — see
 * step-builder.js and composer.js.
 */
'use strict';

const ROW_BOX = 'border:1px solid var(--line,#ddd);border-radius:8px;padding:.5rem .6rem;margin-bottom:.5rem';
const ROW_LINE = 'display:flex;gap:.4rem;align-items:center;flex-wrap:wrap';

function characteristicsControl(field) {
  const box = document.createElement('div');
  box.dataset.testid = 'characteristics';
  const rows = document.createElement('div');
  const warnings = document.createElement('div'); warnings.dataset.testid = 'characteristics-warnings';
  const names = document.createElement('datalist'); names.id = 'consumed-names';
  const add = document.createElement('button');
  add.type = 'button'; add.className = 'ghost'; add.dataset.testid = 'add-characteristic'; add.textContent = '+ Characteristic';
  add.addEventListener('click', () => { addRow().querySelector('[data-testid="char-name"]').focus(); });
  box.append(rows, add, warnings, names);

  const isRange = (c) => (c.productSpecCharacteristicValue || []).some((v) => v.valueFrom !== undefined || v.valueTo !== undefined);
  const rowsOf = () => [...rows.querySelectorAll('[data-char-row]')];

  function lockedRow(origin, drop) {
    const row = document.createElement('div');
    row.dataset.charRow = '1'; row._origin = origin; row._locked = true;
    row.style.cssText = `${ROW_BOX};${ROW_LINE}`;
    const v = (origin.productSpecCharacteristicValue || [])[0] || {};
    const unit = v.unitOfMeasure ? ` ${v.unitOfMeasure}` : '';
    const said = document.createElement('span');
    said.textContent = `${consumedLabel(origin.name)} — a range from ${v.valueFrom} to ${v.valueTo}${unit}, read by the tier pricing.`;
    row.append(said, drop);
    return row;
  }

  function addRow(origin) {
    const drop = document.createElement('button');
    drop.type = 'button'; drop.className = 'ghost'; drop.textContent = '×'; drop.title = 'Remove this characteristic';
    if (origin && isRange(origin)) {
      const locked = lockedRow(origin, drop);
      drop.addEventListener('click', () => { locked.remove(); describe(); });
      rows.append(locked); describe(); return locked;
    }
    // one bordered block per characteristic: a head line (what it is) and a
    // values line (what it holds). Without the block the values of one row
    // wrap into the next and nobody can tell which belongs to which.
    const row = document.createElement('div');
    row.dataset.charRow = '1'; row._origin = origin || null;
    row.style.cssText = ROW_BOX;
    const head = document.createElement('div'); head.style.cssText = ROW_LINE;
    const body = document.createElement('div'); body.style.cssText = ROW_LINE;
    drop.addEventListener('click', () => { row.remove(); describe(); });

    const name = document.createElement('input');
    name.setAttribute('list', 'consumed-names'); name.placeholder = 'Name, e.g. Data';
    name.dataset.testid = 'char-name'; name.value = origin ? origin.name || '' : '';
    const said = document.createElement('span');
    said.className = 'dim'; said.style.cssText = 'font-size:.8rem';
    const type = document.createElement('select');
    type.dataset.testid = 'char-type';
    type.append(new Option('Text', 'string'), new Option('Number', 'number'), new Option('—', ''));
    type.value = origin ? (origin.valueType || '') : 'string';
    const values = document.createElement('div'); values.dataset.testid = 'char-values';
    const more = document.createElement('button');
    more.type = 'button'; more.className = 'ghost'; more.textContent = '+ value'; more.title = 'Offer the customer another choice';
    more.addEventListener('click', () => { addValue(''); describe(); });
    const conf = document.createElement('input');
    conf.type = 'checkbox'; conf.dataset.testid = 'char-configurable'; conf.checked = Boolean(origin && origin.configurable);
    const confLabel = document.createElement('label');
    confLabel.style.cssText = 'display:flex;gap:.25rem;align-items:center;font-size:.85rem';
    confLabel.append(conf, document.createTextNode('the customer chooses'));

    function addValue(v) {
      const input = document.createElement('input');
      input.value = v; input.placeholder = 'Value'; input.dataset.testid = 'char-value';
      values.append(input);
    }
    const existing = (origin && origin.productSpecCharacteristicValue) || [];
    if (existing.length) existing.forEach((v) => addValue(v.value === undefined ? '' : String(v.value)));
    else addValue('');

    const say = () => { said.textContent = CONSUMED_WORDS[name.value] ? `read as ${CONSUMED_WORDS[name.value]}` : ''; };
    name.addEventListener('input', () => { say(); describe(); });
    say();

    row._read = () => {
      const out = origin ? JSON.parse(JSON.stringify(origin)) : {};
      out.name = name.value.trim();
      if (type.value) out.valueType = type.value; else delete out.valueType;
      // a key the wire never carried is not added just because a form was opened
      if (!origin || conf.checked || 'configurable' in out) out.configurable = conf.checked;
      const old = out.productSpecCharacteristicValue || [];
      const typed = [...values.querySelectorAll('input')].map((i) => i.value.trim()).filter((x) => x !== '');
      out.productSpecCharacteristicValue = typed.map((v, i) => ({ ...(old[i] || {}), value: v }));
      return out;
    };
    head.append(name, said, type, drop);
    body.append(values, more, confLabel);
    row.append(head, body);
    rows.append(row); describe();
    return row;
  }

  /* What the chosen fulfilment pattern wants and this specification lacks. The
   * pattern picker publishes it as it describes itself; saying it here, beside
   * the rows, is the difference between a warning and a fix. */
  function describe() {
    warnings.replaceChildren();
    const have = new Set(rowsOf().map((r) => (r._locked ? r._origin.name : r._read().name)).filter(Boolean));
    for (const want of (window.fulfilmentWants || []).filter((w) => !have.has(w.name))) {
      const line = document.createElement('p');
      line.className = 'dim'; line.style.cssText = 'margin:.2rem 0;font-size:.85rem';
      const fix = document.createElement('button');
      fix.type = 'button'; fix.className = 'ghost'; fix.dataset.testid = `add-${want.name}`;
      fix.textContent = `Add ${consumedLabel(want.name).toLowerCase()}`;
      fix.addEventListener('click', () => {
        addRow({ name: want.name, valueType: 'string', configurable: false, productSpecCharacteristicValue: [] })
          .querySelector('[data-testid="char-value"]').focus();
      });
      line.append(document.createTextNode(`⚠ ${want.effect} `), fix);
      warnings.append(line);
    }
  }
  document.addEventListener('fulfilment-changed', describe);

  authFetch(`${SERVICE_CATALOG_BASE}/serviceSpecification?limit=100`, { headers: { 'Cache-Control': 'no-cache' } })
    .then((r) => (r.ok ? r.json() : [])).then((specs) => {
      const offered = new Set();
      for (const s of specs) {
        for (const e of s.serviceSpecRelationship || []) {
          for (const c of e.serviceSpecRelationshipCharacteristic || []) {
            if (c.name !== 'consumes') continue;
            const v = ((c.serviceSpecCharacteristicValue || [])[0] || {}).value || '';
            v.split(',').map((x) => x.trim()).filter(Boolean).forEach((x) => offered.add(x));
          }
        }
      }
      [...offered].sort().forEach((n) => {
        const option = document.createElement('option');
        option.value = n; option.label = consumedLabel(n);
        names.append(option);
      });
    }).catch(() => { /* the field still takes any name typed by hand */ });

  controls[field.name] = {
    get: () => {
      const out = rowsOf().map((r) => (r._locked ? r._origin : r._read())).filter((c) => c.name);
      return out.length ? out : undefined;
    },
    set: (item) => {
      rows.replaceChildren();
      ((item && item[field.name]) || []).forEach((c) => addRow(c));
      describe();
    },
  };
  return [box];
}
