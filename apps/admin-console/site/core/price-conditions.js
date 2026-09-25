/* "Applies only when": the configured choice a price rides on.
 *
 * Split from core/price-builder.js, which holds the shared helpers this file
 * uses (catalogChoices, choicesFor, suggestions, typedValue, labelled,
 * numberInput, rowBox, ghostButton) and must load first — the console's
 * classic scripts share globals in load order.
 *
 * Both shapes the catalog reads: a value ("screens is 5+") and a range
 * ("extraProfiles from 3 to 10"). A row merges its edits into the entry it was
 * read from, and a value that came off the wire as a number goes back as one.
 */
'use strict';

function priceConditionControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  const rows = document.createElement('div');
  rows.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  const note = document.createElement('p');
  note.style.cssText = 'margin:0;font-size:.8rem;color:var(--dim,#666)';
  box.append(rows, ghostButton('+ Condition', () => { entries.push({ name: '', mode: 'value', value: '' }); render(); }), note);

  let entries = [];
  let names = new Map();

  function render() {
    rows.replaceChildren();
    for (const entry of entries) rows.append(conditionRow(entry));
    const scoped = [...names.values()].some((v) => v.specs.length);
    // The field's own hint already says the catalog refuses an undeclared
    // choice; this line only says where the choices on offer came from.
    note.textContent = names.size
      ? `Choices offered from ${scoped ? 'the specification this price is sold on' : 'the catalog'}.`
      : '';
  }

  function conditionRow(entry) {
    const row = rowBox();
    const name = document.createElement('input');
    name.setAttribute('list', 'cond-characteristics');
    name.placeholder = 'screens';
    name.value = entry.name || '';
    name.style.width = '11rem';
    name.addEventListener('input', () => { entry.name = name.value; render(); });
    const mode = document.createElement('select');
    mode.append(new Option('is', 'value'), new Option('from … to', 'range'));
    mode.value = entry.mode;
    mode.addEventListener('change', () => { entry.mode = mode.value; render(); });
    row.append(labelled('When', name), suggestions('cond-characteristics', [...names.keys()]), labelled(' ', mode));

    if (entry.mode === 'range') {
      const from = numberInput('3', '5rem');
      from.value = entry.valueFrom ?? '';
      from.addEventListener('input', () => { entry.valueFrom = from.value; });
      const to = numberInput('10', '5rem');
      to.value = entry.valueTo ?? '';
      to.addEventListener('input', () => { entry.valueTo = to.value; });
      row.append(labelled('From', from), labelled('To', to));
    } else {
      const known = (names.get(entry.name) || {}).values || [];
      const listId = `cond-values-${Math.abs(hashName(entry.name))}`;
      const value = document.createElement('input');
      value.setAttribute('list', listId);
      value.placeholder = known.length ? String(known[0]) : '5+';
      value.value = entry.value === undefined || entry.value === null ? '' : String(entry.value);
      value.style.width = '10rem';
      value.addEventListener('input', () => { entry.value = value.value; entry.touched = true; });
      row.append(labelled('Is', value), suggestions(listId, known));
    }
    row.append(ghostButton('✕', () => { entries = entries.filter((e) => e !== entry); render(); }));
    return row;
  }

  catalogChoices().then((catalog) => { names = choicesFor(catalog, editingId); render(); });

  controls[field.name] = {
    get: () => {
      const live = entries.filter((e) => (e.name || '').trim());
      if (!live.length) return undefined;
      return live.map((e) => {
        const out = { ...(e.original || {}) };
        out.name = e.name.trim();
        const known = (names.get(out.name) || {}).values || [];
        if (e.mode === 'range') {
          const one = { ...((e.originalValue) || {}) };
          delete one.value;
          if (e.valueFrom !== '' && e.valueFrom !== undefined) one.valueFrom = Number(e.valueFrom); else delete one.valueFrom;
          if (e.valueTo !== '' && e.valueTo !== undefined) one.valueTo = Number(e.valueTo); else delete one.valueTo;
          out.productSpecCharacteristicValue = [one];
        } else {
          const one = { ...((e.originalValue) || {}) };
          delete one.valueFrom;
          delete one.valueTo;
          // A value the operator never touched goes back BYTE FOR BYTE. Older
          // conditions hold real numbers ({"value": 4}) while a specification
          // now declares its values as strings, so re-deriving an untouched
          // value from what the form displayed would quietly restringify it.
          one.value = e.touched || !(e.originalValue && 'value' in e.originalValue)
            ? typedValue(String(e.value ?? '').trim(), known)
            : e.originalValue.value;
          out.productSpecCharacteristicValue = [one];
        }
        return out;
      });
    },
    set: (item) => {
      entries = (Array.isArray(item[field.name]) ? item[field.name] : []).map((e) => {
        const first = ((e.productSpecCharacteristicValue || [])[0]) || {};
        const range = first.valueFrom !== undefined || first.valueTo !== undefined;
        return {
          name: e.name || '', mode: range ? 'range' : 'value', touched: false,
          value: first.value, valueFrom: first.valueFrom, valueTo: first.valueTo,
          original: e, originalValue: first,
        };
      });
      render();
    },
  };
  return [box];
}

/* A stable id per characteristic name, so each row's suggestion list is its own. */
function hashName(name) {
  let h = 0;
  for (const ch of String(name || '')) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return h;
}
