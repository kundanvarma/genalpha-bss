/* Product artwork and the bundle composer. */
'use strict';

/**
 * Product artwork without JSON or a separate DAM: upload an image, it lands
 * in the document component (TMF667) and the offering's attachment list —
 * exactly what the storefront and app render. Name an image "gallery-*" for
 * the product-page gallery or "variant-<colour>" to follow the colour picker.
 * Operators with their own PIM skip this entirely: the catalog resolves
 * their imagery per tenant through the same attachment contract.
 */
function artworkControl(field) {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px';
  let entries = [];

  const strip = document.createElement('div');
  strip.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap';

  function redraw() {
    strip.replaceChildren(...entries.map((a, i) => {
      const cell = document.createElement('div');
      cell.style.cssText = 'display:flex;flex-direction:column;align-items:center;gap:3px;font-size:10.5px;color:var(--dim,#8a979c)';
      const img = document.createElement('img');
      img.src = a.url;
      img.alt = a.name || '';
      img.style.cssText = 'width:52px;height:64px;object-fit:cover;border:1px solid var(--line);border-radius:6px;background:#fff';
      const cap = document.createElement('span');
      cap.textContent = a.name || 'image';
      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'ghost';
      del.textContent = '✕';
      del.style.cssText = 'padding:0 6px;font-size:10px';
      del.addEventListener('click', () => { entries.splice(i, 1); redraw(); });
      cell.append(img, cap, del);
      return cell;
    }));
    if (!entries.length) {
      strip.innerHTML = '<span style="font-size:12px;color:var(--dim,#8a979c)">No images yet — the shop shows this offering text-only.</span>';
    }
  }

  const row = document.createElement('div');
  row.style.cssText = 'display:flex;gap:8px;align-items:center;flex-wrap:wrap';
  const role = document.createElement('input');
  role.placeholder = 'name, e.g. gallery-front or variant-Icy Blue';
  role.style.cssText = 'flex:1;min-width:220px';
  role.dataset.testid = 'art-role';
  const file = document.createElement('input');
  file.type = 'file';
  file.accept = 'image/*';
  file.dataset.testid = 'art-file';
  const status = document.createElement('span');
  status.style.fontSize = '12px';
  file.addEventListener('change', async () => {
    const picked = file.files[0];
    if (!picked) return;
    status.textContent = 'uploading…';
    try {
      const bytes = new Uint8Array(await picked.arrayBuffer());
      let binary = '';
      for (const b of bytes) binary += String.fromCharCode(b);
      const offeringName = el('fields').querySelector('input[name="name"]')?.value || 'offering';
      const res = await authFetch('/tmf-api/documentManagement/v4/document', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: `${offeringName} — ${role.value || picked.name}`,
          category: 'offering', mimeType: picked.type || 'image/png', content: btoa(binary),
        }),
      });
      if (!res.ok) throw new Error(`upload failed: HTTP ${res.status}`);
      const doc = await res.json();
      entries.push({ name: role.value || `gallery-${entries.length + 1}`,
        mimeType: picked.type || 'image/png', url: doc.attachmentUrl, '@type': 'Attachment' });
      role.value = '';
      file.value = '';
      status.textContent = '✓ added — save the offering to publish';
      redraw();
    } catch (e) {
      status.textContent = e.message;
    }
  });
  row.append(role, file, status);
  box.append(strip, row);
  redraw();

  controls[field.name] = {
    get: () => entries,
    set: (item) => { entries = [...(item[field.name] || [])]; redraw(); },
  };
  return [box];
}

/**
 * The bundle composer: a product owner assembles what used to take raw
 * TMF620 JSON — mandatory/optional components and pick-N-of-M choice groups
 * with defaults. Unknown entry shapes survive a round trip untouched.
 */
function bundleComposerControl(field) {
  const box = document.createElement('div');
  box.className = 'composer';
  box.style.cssText = 'display:flex;flex-direction:column;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px';
  let entries = [];       // {kind:'component',refId,role,original} | {kind:'choice',name,lower,upper,def,optionIds}
  let passthrough = [];   // shapes the composer does not understand
  let picklist = [];      // [{id,name}]

  const offeringRef = (id) => {
    const item = picklist.find((p) => p.id === id);
    return { id, href: `${API_BASE}/${field.resource}/${id}`,
      name: item ? (item.name || id) : id, '@referredType': field.referredType };
  };

  function offeringSelect(value, onChange) {
    const sel = document.createElement('select');
    sel.append(new Option('— offering —', ''));
    for (const item of picklist) {
      const option = new Option(item.name || item.id, item.id);
      option.disabled = item.id === editingId;
      sel.append(option);
    }
    sel.value = value || '';
    sel.addEventListener('change', () => onChange(sel.value));
    return sel;
  }

  function removeButton(entry) {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'ghost'; b.textContent = '✕';
    b.addEventListener('click', () => { entries = entries.filter((e) => e !== entry); render(); });
    return b;
  }

  function render() {
    box.replaceChildren();
    for (const entry of entries) {
      const row = document.createElement('div');
      row.style.cssText = 'display:flex;gap:6px;align-items:center;flex-wrap:wrap';
      if (entry.kind === 'component') {
        row.dataset.composerRow = 'component';
        const role = document.createElement('select');
        role.append(new Option('Included (mandatory)', 'mandatory'),
          new Option('Optional add-on', 'optional'));
        role.value = entry.role;
        role.addEventListener('change', () => { entry.role = role.value; });
        row.append(offeringSelect(entry.refId, (v) => { entry.refId = v; }), role, removeButton(entry));
      } else {
        row.dataset.composerRow = 'choice';
        row.style.cssText += ';border-left:3px solid var(--teal);padding-left:8px';
        const name = document.createElement('input');
        name.placeholder = 'Choice group name (e.g. Choose your phone)';
        name.value = entry.name; name.style.minWidth = '220px';
        name.addEventListener('input', () => { entry.name = name.value; });
        const lower = document.createElement('input');
        lower.type = 'number'; lower.min = '0'; lower.value = entry.lower;
        lower.style.width = '4.5em'; lower.title = 'minimum picks';
        lower.addEventListener('input', () => { entry.lower = Number(lower.value); });
        const upper = document.createElement('input');
        upper.type = 'number'; upper.min = '1'; upper.value = entry.upper;
        upper.style.width = '4.5em'; upper.title = 'maximum picks';
        upper.addEventListener('input', () => { entry.upper = Number(upper.value); });
        const opts = document.createElement('select');
        opts.multiple = true; opts.size = 4; opts.style.minWidth = '220px';
        for (const item of picklist) {
          const option = new Option(item.name || item.id, item.id);
          option.selected = entry.optionIds.includes(item.id);
          option.disabled = item.id === editingId;
          opts.append(option);
        }
        const def = document.createElement('select');
        const syncDefault = () => {
          entry.optionIds = [...opts.selectedOptions].map((o) => o.value);
          def.replaceChildren(new Option('no default', ''));
          for (const id of entry.optionIds) {
            def.append(new Option('default: ' + (picklist.find((p) => p.id === id)?.name || id), id));
          }
          def.value = entry.optionIds.includes(entry.def) ? entry.def : '';
          entry.def = def.value;
        };
        opts.addEventListener('change', syncDefault);
        def.addEventListener('change', () => { entry.def = def.value; });
        const pickLabel = document.createElement('span');
        pickLabel.className = 'dimhint';
        pickLabel.textContent = 'pick';
        const dash = document.createElement('span');
        dash.className = 'dimhint';
        dash.textContent = '–';
        row.append(name, pickLabel, lower, dash, upper, opts, def, removeButton(entry));
        syncDefault();
      }
      box.append(row);
    }
    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:8px';
    const addComponent = document.createElement('button');
    addComponent.type = 'button'; addComponent.className = 'ghost';
    addComponent.textContent = '+ Component';
    addComponent.dataset.composerAdd = 'component';
    addComponent.addEventListener('click', () => {
      entries.push({ kind: 'component', refId: '', role: 'mandatory' }); render();
    });
    const addChoice = document.createElement('button');
    addChoice.type = 'button'; addChoice.className = 'ghost';
    addChoice.textContent = '+ Choice group';
    addChoice.dataset.composerAdd = 'choice';
    addChoice.addEventListener('click', () => {
      entries.push({ kind: 'choice', name: '', lower: 1, upper: 1, def: '', optionIds: [] }); render();
    });
    actions.append(addComponent, addChoice);
    if (passthrough.length) {
      const note = document.createElement('span');
      note.className = 'dimhint';
      note.textContent = `${passthrough.length} advanced entr${passthrough.length > 1 ? 'ies' : 'y'} kept as-is`;
      actions.append(note);
    }
    box.append(actions);
  }

  loadPicklist(field).then((items) => { picklist = items; render(); });

  controls[field.name] = {
    get: () => {
      const out = [];
      for (const entry of entries) {
        if (entry.kind === 'component' && entry.refId) {
          const base = entry.original && entry.original.id === entry.refId
            ? { ...entry.original } : offeringRef(entry.refId);
          base.bundledProductOfferingOption = {
            numberRelOfferLowerLimit: entry.role === 'optional' ? 0 : 1,
            numberRelOfferUpperLimit: 1,
          };
          out.push(base);
        } else if (entry.kind === 'choice' && entry.name && entry.optionIds.length) {
          out.push({
            '@type': 'BundledProductOfferingChoice',
            name: entry.name,
            ...(entry.def ? { default: entry.def } : {}),
            numberRelOfferLowerLimit: entry.lower,
            numberRelOfferUpperLimit: entry.upper,
            options: entry.optionIds.map(offeringRef),
          });
        }
      }
      out.push(...passthrough);
      return out.length ? out : undefined;
    },
    set: (item) => {
      entries = []; passthrough = [];
      for (const e of (item[field.name] || [])) {
        if (Array.isArray(e.options)) {
          entries.push({ kind: 'choice', name: e.name || '',
            lower: e.numberRelOfferLowerLimit ?? 1, upper: e.numberRelOfferUpperLimit ?? 1,
            def: e.default || '', optionIds: e.options.map((o) => o.id).filter(Boolean) });
        } else if (e.id) {
          entries.push({ kind: 'component', refId: e.id, original: e,
            role: e.bundledProductOfferingOption?.numberRelOfferLowerLimit === 0 ? 'optional' : 'mandatory' });
        } else {
          passthrough.push(e);
        }
      }
      render();
    },
  };
  return [box];
}
