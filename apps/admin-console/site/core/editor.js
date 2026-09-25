/* The editor form: reference pickers, rendering the fields, progressive disclosure, AI assist, start/stop editing. */
'use strict';

function refControl(field, multiple) {
  const select = document.createElement('select');
  select.name = field.name;
  // Entries that are not plain refs (e.g. bundle choice groups, edited via the
  // API) must survive an edit-save round trip untouched — and plain refs that
  // carry extra metadata (bundledProductOfferingOption cardinality) must keep it.
  let passthrough = [];
  let originals = {};
  if (multiple) {
    select.multiple = true;
    select.size = 4;
  } else {
    select.append(new Option('—', ''));
  }
  loadPicklist(field).then((items) => {
    for (const item of items) {
      const option = new Option(item.name || item.id, item.id);
      option.dataset.name = item.name || item.id;
      option.disabled = item.id === editingId;
      select.append(option);
    }
    if (pendingSelection[field.name]) {
      applySelection(select, pendingSelection[field.name]);
      delete pendingSelection[field.name];
    }
  });
  controls[field.name] = {
    get: () => {
      const picked = [...select.selectedOptions].filter((o) => o.value);
      if (!picked.length && !passthrough.length) return undefined;
      return multiple
        ? [...picked.map((o) => originals[o.value] || refObject(field, o)), ...passthrough]
        : refObject(field, picked[0]);
    },
    set: (item) => {
      const refs = multiple ? (item[field.name] || []) : [item[field.name]].filter(Boolean);
      passthrough = multiple ? refs.filter((r) => !r.id || r.options) : [];
      originals = Object.fromEntries(refs.filter((r) => r.id && !r.options).map((r) => [r.id, r]));
      const ids = refs.filter((r) => r.id && !r.options).map((r) => r.id);
      if (select.options.length > (multiple ? 0 : 1)) {
        applySelection(select, ids);
      } else {
        pendingSelection[field.name] = ids; // picklist still loading
      }
    },
  };
  return [select];
}

let pendingSelection = {};

function applySelection(select, ids) {
  for (const option of select.options) {
    option.selected = ids.includes(option.value);
    if (option.value) option.disabled = option.value === editingId;
  }
}

function renderEditor() {
  controls = {};
  pendingSelection = {};
  // noCreate: the tab edits existing rows but never authors new ones (e.g. an
  // opportunity is born by qualifying a lead, then worked here) — hide the form
  // until Edit reveals it.
  el('editor').hidden = Boolean(active.readOnly) || (Boolean(active.noCreate) && !editingId);
  el('fields').replaceChildren(...active.fields.map((f) => {
    const wrap = document.createElement('label');
    wrap.className = 'field' + (f.kind === 'checkbox' ? ' check' : '') + ' field-' + (f.kind || 'text')
      + (f.wide ? ' wide' : '') + (f.half ? ' half' : '');
    const caption = document.createElement('span');
    caption.textContent = f.label + (f.required ? ' *' : '');
    const parts =
      f.kind === 'checkbox' ? checkboxControl(f) :
      f.kind === 'date' ? dateControl(f) :
      f.kind === 'money' ? moneyControl(f) :
      f.kind === 'quantity' ? quantityControl(f) :
      f.kind === 'ref' ? refControl(f, false) :
      f.kind === 'reflist' ? refControl(f, true) :
      f.kind === 'commitment' ? commitmentControl(f) :
      f.kind === 'artwork' ? artworkControl(f) :
      f.kind === 'bundlecomposer' ? bundleComposerControl(f) :
      f.kind === 'decomposition' ? decompositionControl(f) :
      f.kind === 'fulfilment' ? fulfilmentControl(f) :
      f.kind === 'jsontext' ? jsonTextControl(f) :
      f.kind === 'stepbuilder' ? stepBuilderControl(f) :
      f.kind === 'characteristics' ? characteristicsControl(f) :
      f.kind === 'unitofmeasure' ? unitOfMeasureControl(f) :
      f.kind === 'algorithm' ? pricingAlgorithmControl(f) :
      f.kind === 'pricecondition' ? priceConditionControl(f) :
      f.kind === 'relationships' ? offeringRelationshipControl(f) :
      f.kind === 'stockvariant' ? stockVariantControl(f) :
      f.kind === 'select' ? selectControl(f) :
      f.kind === 'multiselect' ? multiselectControl(f) :
      f.kind === 'recipe' ? recipeControl(f) :
      f.kind === 'longtext' ? longTextControl(f) :
      f.kind === 'codepick' ? codePickControl(f) :
      textControl(f, f.kind === 'number' ? 'number' : 'text');
    wrap.append(caption, ...parts);
    if (f.hint) { const h = document.createElement('span'); h.className = 'hint'; h.textContent = f.hint; wrap.append(h); }
    if (f.tokens && parts[0] && (parts[0].tagName === 'INPUT' || parts[0].tagName === 'TEXTAREA')) {
      wrap.append(tokenChips(parts[0])); // personalization chips + {{ autocomplete
    }
    wrap.dataset.field = f.name;
    return wrap;
  }));
  // presets the desk learned for this form: one click prefills the shared values
  if (!active.readOnly && active.fields.length) {
    authFetch(`/insight/v1/desk/presets?desk=console&form=${encodeURIComponent(active.path)}`).then(async (r) => {
      if (!r.ok) return;
      let list = await r.json();
      if (!Array.isArray(list) || !list.length) return;
      const seen = new Set();
      list = list.filter((p) => { const k = p.name + '|' + JSON.stringify(p.values || {}); if (seen.has(k)) return false; seen.add(k); return true; });
      el('fields').querySelectorAll('.presets').forEach((x) => x.remove());
      const row = document.createElement('div');
      row.className = 'presets';
      row.style.cssText = 'grid-column:1/-1;display:flex;gap:.4rem;flex-wrap:wrap;align-items:center;font-size:.85rem';
      const cap = document.createElement('span'); cap.className = 'dim'; cap.textContent = 'Presets the desk learned:'; row.append(cap);
      for (const p of list) {
        const chip = document.createElement('button'); chip.type = 'button'; chip.className = 'ghost small'; chip.textContent = p.name;
        chip.title = Object.entries(p.values || {}).map(([k, v]) => `${k} = ${v}`).join(' · ');
        chip.addEventListener('click', () => {
          for (const [k, v] of Object.entries(p.values || {})) {
            const c = el('editor').elements[k]; if (!c) continue;
            if (c.type === 'checkbox') c.checked = String(v) === 'true'; else c.value = v;
            c.dispatchEvent(new Event('input', { bubbles: true })); c.dispatchEvent(new Event('change', { bubbles: true }));
          }
          desk('preset.use', active.path, { preset: p.id });
        });
        row.append(chip);
      }
      el('fields').prepend(row);
    }).catch(() => {});
  }
  wireVisibility();
  if (active.aiAssist) {
    el('fields').append(aiAssistRow(active.aiAssist));
  }
  if (active.tester) {
    el('fields').append(testerRow());
  }
}

/**
 * Progressive disclosure: a field with showWhen {field, in: [...]} only renders
 * while the controlling select holds one of those values. An empty controlling
 * value (e.g. editing an existing row) shows everything — never hide data.
 */
function wireVisibility() {
  const dependents = active.fields.filter((f) => f.showWhen);
  if (!dependents.length) return;
  const sources = [...new Set(dependents.map((f) => f.showWhen.field))];
  const apply = () => {
    for (const f of dependents) {
      const src = el('fields').querySelector(`[name="${f.showWhen.field}"]`);
      const v = src ? src.value : '';
      const wrap = el('fields').querySelector(`[data-field="${f.name}"]`);
      // No kind chosen yet: creating → keep the form minimal; editing an
      // existing row → show everything, never hide data.
      const show = v ? f.showWhen.in.includes(v) : Boolean(editingId);
      if (wrap) wrap.style.display = show ? '' : 'none';
    }
  };
  for (const name of sources) {
    const src = el('fields').querySelector(`[name="${name}"]`);
    if (src) src.addEventListener('change', apply);
  }
  apply();
}

/** A brief in, a draft out — into the message fields, still editable. */
function aiAssistRow(assist) {
  const wrap = document.createElement('div');
  wrap.className = 'field aiassist';
  const caption = document.createElement('span');
  caption.textContent = 'AI assist';
  const row = document.createElement('div');
  row.className = 'moneyrow';
  const brief = document.createElement('input');
  brief.placeholder = assist.placeholder;
  brief.id = 'ai-brief';
  const button = document.createElement('button');
  button.type = 'button';
  button.id = 'ai-draft';
  button.className = 'ghost';
  button.textContent = '✨ Draft';
  button.addEventListener('click', async () => {
    if (!brief.value.trim()) return;
    button.disabled = true;
    button.textContent = 'Drafting…';
    try {
      await assist.draft(brief.value.trim());
    } catch (e) {
      el('editor-error').textContent = 'AI assist: ' + e.message;
      el('editor-error').hidden = false;
    } finally {
      button.disabled = false;
      button.textContent = '✨ Draft';
    }
  });
  row.append(brief, button);
  wrap.append(caption, row);
  return wrap;
}

function stopEditing() {
  editingId = null;
  el('editor-title').textContent = 'New';
  el('save').textContent = 'Create';
  el('editor').reset();
  if (active && active.noCreate) el('editor').hidden = true; // stays edit-only
  el('editor-error').hidden = true;
  for (const option of el('fields').querySelectorAll('option')) {
    option.disabled = false;
  }
}

function startEditing(item) {
  editingId = item.id;
  el('editor-title').textContent = 'Edit ' + (item.name || item.id);
  el('save').textContent = 'Save changes';
  el('editor').hidden = false; // reveal for noCreate tabs
  openDrawer();
  for (const f of active.fields) {
    controls[f.name].set(item);
  }
  el('editor').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
