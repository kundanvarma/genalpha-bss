/* Two more boxes that asked a product manager to type a payload: what an
 * offering requires or excludes, and which variant a stock row counts.
 *
 * Both are choices over things the catalog already holds — other offerings,
 * and the configurable characteristics of the specification behind an
 * offering — so both become pickers. Neither invents an identifier: an id
 * typed by hand is an id typed wrong. As in the price builder, a row merges
 * its edits into the entry it was read from, so keys this form does not model
 * survive the round trip untouched.
 *
 * Loaded after core/price-builder.js: it shares that file's catalogChoices().
 */
'use strict';

/* TMF620's three, as the configurator enforces them (requires carries a role;
 * exchangableTo is the like-for-like change list the upgrade path reads). */
const OFFERING_RELATIONSHIPS = [
  ['requires', 'requires'],
  ['excludes', 'excludes'],
  ['exchangableTo', 'can be changed to'],
];
const REQUIRES_ROLES = [
  ['prompt', 'ask the customer'],
  ['auto-add', 'add it automatically'],
  ['block', 'block the order without it'],
];

function pickerRow() {
  const row = document.createElement('div');
  row.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;align-items:flex-end;border:1px solid var(--line,#ddd);border-radius:8px;padding:8px';
  return row;
}

function pickerField(text, node) {
  const wrap = document.createElement('label');
  wrap.style.cssText = 'display:flex;flex-direction:column;gap:2px;font-size:.78rem;color:var(--dim,#666)';
  const span = document.createElement('span');
  span.textContent = text;
  wrap.append(span, node);
  return wrap;
}

function pickerButton(text, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'ghost';
  b.textContent = text;
  b.addEventListener('click', onClick);
  return b;
}

function selectOf(pairs, value, onChange) {
  const sel = document.createElement('select');
  for (const [v, label] of pairs) sel.append(new Option(label, v));
  sel.value = value || pairs[0][0];
  sel.addEventListener('change', () => onChange(sel.value));
  return sel;
}

/* ------------------------------------------------------ requires / excludes --
 * A type, an offering by name, and — when it requires — what the shop does
 * about it. */
function offeringRelationshipControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  const rows = document.createElement('div');
  rows.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  box.append(rows, pickerButton('+ Relationship', () => {
    entries.push({ relationshipType: 'requires', role: 'prompt' });
    render();
  }));

  let entries = [];
  let picklist = [];

  function offeringSelect(entry) {
    const sel = document.createElement('select');
    sel.append(new Option('— offering —', ''));
    for (const item of picklist) {
      const option = new Option(item.name || item.id, item.id);
      option.disabled = item.id === editingId;   // an offering cannot require itself
      sel.append(option);
    }
    sel.value = entry.id || '';
    sel.style.minWidth = '14rem';
    sel.addEventListener('change', () => {
      entry.id = sel.value;
      const hit = picklist.find((p) => p.id === sel.value);
      entry.name = hit ? (hit.name || sel.value) : undefined;
    });
    return sel;
  }

  function render() {
    rows.replaceChildren();
    for (const entry of entries) {
      const row = pickerRow();
      row.append(
        pickerField('This offering', selectOf(OFFERING_RELATIONSHIPS, entry.relationshipType, (v) => {
          entry.relationshipType = v;
          render();
        })),
        pickerField('Offering', offeringSelect(entry)),
      );
      if (entry.relationshipType === 'requires') {
        row.append(pickerField('And the shop should', selectOf(REQUIRES_ROLES, entry.role, (v) => { entry.role = v; })));
      }
      row.append(pickerButton('✕', () => { entries = entries.filter((e) => e !== entry); render(); }));
      rows.append(row);
    }
  }

  loadPicklist({ resource: 'productOffering' }).then((items) => {
    picklist = Array.isArray(items) ? items : [];
    render();
  });

  controls[field.name] = {
    get: () => {
      const live = entries.filter((e) => e.id);
      if (!live.length) return undefined;
      return live.map((e) => {
        const out = { ...(e.original || {}) };
        out.id = e.id;
        if (e.name !== undefined) out.name = e.name;
        out.relationshipType = e.relationshipType;
        if (e.relationshipType === 'requires') {
          if (e.role) out.role = e.role;
        } else {
          delete out.role;
        }
        return out;
      });
    },
    set: (item) => {
      entries = (Array.isArray(item[field.name]) ? item[field.name] : []).map((e) => ({
        id: e.id, name: e.name, relationshipType: e.relationshipType || 'requires',
        role: e.role || 'prompt', original: e,
      }));
      render();
    },
  };
  return [box];
}

/* ------------------------------------------------------------- stock variant --
 * Which configured variant this row counts: one picker per configurable
 * characteristic of the specification behind the chosen offering. Blank = the
 * offering as a whole, exactly as an empty box meant. */
function stockVariantControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px';
  const rows = document.createElement('div');
  rows.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px';
  const note = document.createElement('p');
  note.style.cssText = 'margin:0;font-size:.8rem;color:var(--dim,#666)';
  box.append(rows, note);

  let catalog = { specs: [], offerings: [] };
  let chosen = {};        // characteristic name → typed value
  let original = null;    // the stockedProduct read off the wire
  let offeringId = '';

  /* The offering this stock row counts drives everything, and the operator may
   * still be choosing it — so read it at render time rather than once. */
  function currentOffering() {
    const picked = controls.productOffering && controls.productOffering.get();
    const id = (picked && picked.id) || offeringId;
    return catalog.offerings.find((o) => o.id === id) || null;
  }

  function configurable() {
    const offering = currentOffering();
    if (!offering) return [];
    const specId = (offering.productSpecification || {}).id;
    const spec = catalog.specs.find((s) => s.id === specId);
    if (!spec) return [];
    return (spec.productSpecCharacteristic || []).filter((c) => c && c.configurable && c.name);
  }

  function render() {
    rows.replaceChildren();
    const chars = configurable();
    if (!chars.length) {
      note.textContent = currentOffering()
        ? 'This offering’s specification declares no choices, so there is no variant to count — leave it as the offering as a whole.'
        : 'Choose the offering above, then pick the variant this row counts.';
      return;
    }
    note.textContent = 'Blank = the offering as a whole. A variant with no stock is not selectable in the shop.';
    for (const c of chars) {
      const values = (c.productSpecCharacteristicValue || []).map((v) => v.value).filter((v) => v !== undefined);
      const sel = document.createElement('select');
      sel.append(new Option('— any —', ''));
      for (const v of values) {
        const option = new Option(String(v), String(v));
        option.selected = String(chosen[c.name]) === String(v);
        sel.append(option);
      }
      sel.addEventListener('change', () => {
        const hit = values.find((v) => String(v) === sel.value);
        if (sel.value === '') delete chosen[c.name]; else chosen[c.name] = hit === undefined ? sel.value : hit;
      });
      rows.append(pickerField(c.name, sel));
    }
  }

  const refresh = () => render();
  box.addEventListener('focusin', refresh);          // the offering may have changed above
  document.addEventListener('change', refresh);

  catalogChoices().then((loaded) => { catalog = loaded; render(); });

  controls[field.name] = {
    get: () => {
      const names = Object.keys(chosen);
      if (!names.length) return undefined;
      const offering = currentOffering();
      const out = { ...(original || {}) };
      if (offering) out.productOffering = { ...(out.productOffering || {}), id: offering.id };
      const before = (original || {}).productCharacteristic || [];
      out.productCharacteristic = names.map((name) => {
        const was = before.find((c) => c && c.name === name) || {};
        return { ...was, name, value: chosen[name] };
      });
      return out;
    },
    set: (item) => {
      original = item[field.name] || null;
      offeringId = ((item.productOffering || {}).id) || ((original || {}).productOffering || {}).id || '';
      chosen = {};
      for (const c of (original || {}).productCharacteristic || []) {
        if (c && c.name) chosen[c.name] = c.value;
      }
      render();
    },
  };
  return [box];
}
