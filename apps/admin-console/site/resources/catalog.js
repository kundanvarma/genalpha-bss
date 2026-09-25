/* Resources 1/9 — Home and the product catalog. RESOURCES is declared here; the other resources/*.js files push into it, in load order. */
'use strict';

const RESOURCES = [
  // Home / My Work: the first screen — what needs me, my work, health, recent, quick actions (home.js)
  { path: 'home', title: 'Home', home: true, readOnly: true, fields: [], columns: [] },
  {
    path: 'productOffering',
    title: 'Product Offerings',
    fields: [
      // row 1 — identity
      { name: 'name', label: 'Name', required: true, half: true },
      { name: 'description', label: 'Description', half: true, hint: 'What the shop shows under the name.' },
      // row 2 — lifecycle and window
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active', hint: 'In study → In design → In test → Active → Retired' },
      { name: 'version', label: 'Version', placeholder: '1.0' },
      { name: 'validFrom', label: 'Available from', kind: 'date', read: (o) => (o.validFor || {}).startDateTime, hint: 'Blank = available immediately' },
      { name: 'validTo', label: 'Available until', kind: 'date', endOfDay: true, read: (o) => (o.validFor || {}).endDateTime, hint: 'The last day it is sold. Blank = forever' },
      // row 3 — what it is
      { name: 'productSpecification', label: 'Specification', kind: 'ref', resource: 'productSpecification', referredType: 'ProductSpecification', half: true, hint: 'The facts: data, validity, network…' },
      { name: 'productOfferingTerm', label: 'Commitment', kind: 'commitment', hint: 'Binding period, if any' },
      { name: 'productOfferingRelationship', label: 'Requires / excludes', kind: 'relationships', wide: true, hint: 'What this offer needs, rules out, or can be changed to. The configurator enforces it in every channel.' },
      { name: 'isBundle', label: 'Is a bundle', kind: 'checkbox' },
      // row 4 — placement and price
      { name: 'category', label: 'Categories', kind: 'reflist', resource: 'category', referredType: 'Category', half: true, hint: 'Drive shop placement and fulfilment' },
      { name: 'productOfferingPrice', label: 'Prices', kind: 'reflist', resource: 'productOfferingPrice', referredType: 'ProductOfferingPrice', half: true, hint: 'One or more; discounts are pricing rules' },
      // row 5 — where it is sold
      { name: 'channel', label: 'Channels', kind: 'multiselect', options: CHANNELS, refType: 'Channel', wide: true, hint: 'Where this offer is sold. Nothing ticked = every channel, including AI agents.' },
      // row 6 — composition and art
      { name: 'bundledProductOffering', label: 'Bundle composition', kind: 'bundlecomposer', resource: 'productOffering', referredType: 'ProductOffering', wide: true },
      { name: 'attachment', label: 'Artwork', kind: 'artwork', wide: true, hint: 'Gallery shots and colour variants' },
      // row 7 — how the orchestrator will fulfil it, read off the catalog (product spec → CFS → RFS → resource spec)
      { name: 'decomposition', label: 'Decomposition', kind: 'decomposition', wide: true,
        hint: 'What this offering needs from the network and partners, as the catalog declares it. Read-only.' },
    ],
    assemble: (body) => {
      const out = { ...body };
      if (body.validFrom || body.validTo) {
        out.validFor = { startDateTime: body.validFrom || undefined, endDateTime: body.validTo || undefined };
      }
      delete out.validFrom; delete out.validTo;
      return out;
    },
    // L1 — governed catalogs climb the ladder one rung at a time; the row
    // action offers exactly the next rung
    rowAction: {
      label: (item) => {
        const next = { 'In study': 'In design', 'In design': 'In test', 'In test': 'Launched',
          Launched: 'Retired', Active: 'Retired' }[item.lifecycleStatus];
        return next ? `→ ${next}` : '—';
      },
      apply: (item) => {
        const next = { 'In study': 'In design', 'In design': 'In test', 'In test': 'Launched',
          Launched: 'Retired', Active: 'Retired' }[item.lifecycleStatus];
        return next ? authFetch(`${API_BASE}/productOffering/${item.id}`, {
          method: 'PATCH', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ lifecycleStatus: next }) }) : Promise.resolve();
      },
    },
    columns: ['name', 'lifecycleStatus', 'isBundle', 'version', 'lastUpdate'],
  },
  {
    path: 'productSpecification',
    title: 'Product Specifications',
    fields: [
      { name: 'name', label: 'Name', required: true, half: true },
      { name: 'brand', label: 'Brand', half: true, hint: 'Shown on device cards; blank for plans' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active', hint: 'In study → In design → In test → Active → Retired' },
      // A specification has no selling window: TMF620 puts `validFor` on the
      // OFFERING, and this form's two date fields were silently dropped by the
      // API for as long as they existed. Removed rather than faked.
      { name: 'productSpecCharacteristic', label: 'Characteristics', kind: 'characteristics', wide: true,
        hint: 'The facts the shop shows and the systems read. Tick "the customer chooses" and add values to make it a picker in the shop.' },
      // how products built on this spec are fulfilled: a pattern by name, consequences in words (CFS step 3, ticket 8a)
      { name: 'serviceSpecification', label: 'Fulfilment', kind: 'fulfilment', wide: true,
        hint: 'Pick how the orchestrator fulfils products built on this specification. What each pattern needs is spelled out underneath.' },
    ],
    // changing the pattern on an existing spec is a governed action with a receipt, not a raw write
    beforeSave: async (body, editingId) => {
      const picked = (body.serviceSpecification || [])[0];
      const before = fulfilmentState.original;
      if (!editingId || (picked?.id || '') === (before || '')) return body;
      if (!picked) { fulfilmentState.receipt = null; return body; } // clearing to "none" stays a plain edit
      const res = await authFetch('/ontology/v1/actions/assignFulfilmentPattern/execute', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ inputs: { specId: editingId, cfsId: picked.id } }) });
      const receipt = await res.json().catch(() => ({}));
      if (!res.ok || !receipt.done) throw new Error(receipt.refusal || receipt.message || `fulfilment pattern refused (HTTP ${res.status})`);
      fulfilmentState.receipt = { pattern: picked.name, id: receipt.decisionId };
      const rest = { ...body }; delete rest.serviceSpecification; // the action wrote it
      return rest;
    },
    afterSave: () => {
      const r = fulfilmentState.receipt; fulfilmentState.receipt = null;
      if (!r) return;
      document.querySelector('[data-testid="fulfilment-receipt"]')?.remove();
      const p = document.createElement('p');
      p.className = 'dim'; p.dataset.testid = 'fulfilment-receipt';
      p.textContent = `Fulfilment set to ${r.pattern} — recorded with receipt …${String(r.id || '').slice(-8)}.`;
      document.querySelector('.panel-head')?.after(p);
    },
    columns: ['name', 'brand', 'lifecycleStatus', 'lastUpdate'],
  },
  {
    path: 'productOfferingPrice',
    title: 'Product Offering Prices',
    fields: [
      { name: 'name', label: 'Name', required: true, half: true },
      { name: 'priceType', label: 'Price type', kind: 'select', options: [{ value: 'recurring', label: 'recurring (per period)' }, { value: 'oneTime', label: 'one-time' }, { value: 'usage', label: 'usage' }, { value: 'penalty', label: 'early termination (never charged on a configuration — what leaving early costs, declining over the term)' }], default: 'recurring', hint: 'What is charged, when' },
      { name: 'price', label: 'Price', kind: 'money', hint: 'Amount and currency' },
      { name: 'recurringChargePeriodType', label: 'Charge period', kind: 'select', options: [{ value: 'month', label: 'month' }, { value: 'week', label: 'week' }, { value: 'day', label: 'day' }, { value: 'year', label: 'year' }], default: 'month', hint: 'Recurring prices only' },
      { name: 'recurringChargePeriodLength', label: 'Period length', kind: 'number', placeholder: '1', hint: 'e.g. 1 = every month' },
      { name: 'version', label: 'Version', placeholder: '1.0' },
      { name: 'isBundle', label: 'Bundle price', kind: 'checkbox' },
      { name: 'lifecycleStatus', label: 'Lifecycle status', placeholder: 'Active', hint: 'In study → In design → In test → Active → Retired' },
      { name: 'unitOfMeasure', label: 'Per unit of', kind: 'unitofmeasure', hint: 'Quantity pricing: the price applies per this many (per seat, per 5 GB). Blank = a flat price.' },
      { name: 'validFrom', label: 'Price from', kind: 'date', read: (p) => (p.validFor || {}).startDateTime, hint: 'When this price line starts. Blank = always' },
      { name: 'validTo', label: 'Price until', kind: 'date', endOfDay: true, read: (p) => (p.validFor || {}).endDateTime, hint: 'Its last day. Blank = forever. Never edit a live price on an offering with subscribers — add a dated segment' },
      { name: 'pricingLogicAlgorithm', label: 'Algorithm', kind: 'algorithm', wide: true, hint: 'A named algorithm from the documented set, never a formula: per unit above a threshold, or a tier table.' },
      { name: 'prodSpecCharValueUse', label: 'Applies only when', kind: 'pricecondition', wide: true, hint: 'The configured choice this price rides on. The specification must declare the choice and its values, or the catalog refuses the offering.' },
    ],
    // two calendars, one TMF window — the same shape the offering assembles
    assemble: (body) => {
      const out = { ...body };
      if (body.validFrom || body.validTo) {
        out.validFor = { startDateTime: body.validFrom || undefined, endDateTime: body.validTo || undefined };
      }
      delete out.validFrom; delete out.validTo;
      return out;
    },
    columns: ['name', 'priceType', 'price', 'recurringChargePeriodType', 'lifecycleStatus', 'lastUpdate'],
  },
  {
    path: 'productStock',
    base: STOCK_BASE,
    title: 'Product Stock',
    fields: [
      { name: 'name', label: 'Name', required: true },
      { name: 'productOffering', label: 'Offering', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering' },
      { name: 'stockedQuantity', label: 'Stocked', kind: 'quantity' },
      { name: 'stockedProduct', label: 'Variant (optional)', kind: 'stockvariant', wide: true, hint: 'Count this row per configured variant (a colour, a storage size). Blank = the offering as a whole.' },
    ],
    columns: ['name', 'productOffering', 'stockedQuantity', 'reservedQuantity', 'availableQuantity', 'lastUpdate'],
  },
];

/* The Fulfilment picker (CFS step 3, ticket 8a): the product manager picks a customer-facing
 * service BY NAME with its family in words; the resource-facing services it relies on appear
 * underneath as sentences ("Number assignment (always)", "Charging subscriber (only when the
 * product carries a charging plan)"), never as controls. get() is the standard TMF620
 * serviceSpecification reference list; the save routes a change through the governed action. */
const fulfilmentState = { original: null, receipt: null };
const FAMILY_WORDS = { mobile: 'a network line', internet: 'an install', tv: 'a digital entitlement', device: 'a parcel',
  partner: 'activated with the partner', security: 'a feature toggle', compute: 'compute on the edge', 'billing-only': 'nothing to provision' };
// CONSUMED_WORDS moved to core/config.js: the characteristics editor offers the
// same names this picker explains, and one vocabulary cannot drift from itself.
function fulfilmentControl(field) {
  const select = document.createElement('select');
  select.name = field.name;
  select.append(new Option('(none — the orchestrator falls back to the category)', ''));
  const consequences = document.createElement('p');
  consequences.className = 'dim'; consequences.dataset.testid = 'fulfilment-consequences';
  const specValue = (s, list, name) => { const c = (s[list] || []).find((x) => x.name === name); const v = c ? (c[list.replace('Characteristic', 'CharacteristicValue')] || [])[0] : null; return v ? v.value : undefined; };
  const byId = {}; let pending = null;
  const listCfs = authFetch(`${SERVICE_CATALOG_BASE}/serviceSpecification?serviceType=CFS&limit=100`, { headers: { 'Cache-Control': 'no-cache' } })
    .then((r) => (r.ok ? r.json() : [])).catch(() => []);
  listCfs.then((cfss) => {
    for (const cfs of cfss.filter((c) => c.serviceType === 'CFS' && !/^SUITE\d+/.test(c.name || '')).sort((a, b) => String(a.name).localeCompare(String(b.name)))) {
      byId[cfs.id] = cfs;
      const family = specValue(cfs, 'serviceSpecCharacteristic', 'fulfilmentFamily');
      const option = new Option(`${cfs.name}${FAMILY_WORDS[family] ? ` — ${FAMILY_WORDS[family]}` : ''}`, cfs.id);
      option.dataset.name = cfs.name;
      select.append(option);
    }
    if (pending) { select.value = pending; pending = null; }
    describe();
  });
  /* The pattern says what it reads off the specification; the characteristics
   * editor listens, so a missing charging plan is said beside the row that
   * would supply it rather than only in this sentence. */
  const wants = [];
  async function describe() {
    wants.length = 0;
    await describePattern();
    window.fulfilmentWants = wants.slice();
    document.dispatchEvent(new CustomEvent('fulfilment-changed'));
  }
  async function describePattern() {
    const cfs = byId[select.value];
    if (!cfs) { consequences.textContent = select.value ? 'Reading the pattern…' : 'No pattern: the orchestrator decides from the offering\'s category, as it always did.'; return; }
    const edges = (cfs.serviceSpecRelationship || []).filter((e) => e.relationshipType === 'reliesOn');
    if (!edges.length) {
      const family = specValue(cfs, 'serviceSpecCharacteristic', 'fulfilmentFamily');
      consequences.textContent = family === 'billing-only' ? 'Nothing to provision: products on this pattern only bill.' : 'Needs nothing from the network or a partner: realised inside this BSS.';
      return;
    }
    const parts = [];
    for (const edge of edges) {
      const rfs = edge.name || (await authFetch(`${SERVICE_CATALOG_BASE}/serviceSpecification/${edge.id}`).then((r) => (r.ok ? r.json() : null)).catch(() => null))?.name || 'a resource-facing service';
      const chars = edge.serviceSpecRelationshipCharacteristic || [];
      const required = ((chars.find((c) => c.name === 'required') || {}).serviceSpecCharacteristicValue || [{}])[0].value;
      const consumes = (((chars.find((c) => c.name === 'consumes') || {}).serviceSpecCharacteristicValue || [{}])[0].value || '').split(',').map((x) => x.trim()).filter(Boolean);
      const when = String(required) === 'false' ? ` (only when the product carries ${consumes.map((c) => CONSUMED_WORDS[c] || c).join(' or ') || 'what it needs'})` : ' (always)';
      parts.push(`${rfs}${when}`);
      // what this pattern reads off the specification, so the characteristics
      // editor can say what is missing beside the rows that would fix it
      for (const c of consumes) wants.push({ name: c, effect: `no ${CONSUMED_WORDS[c] || c}: ${rfs.toLowerCase()} will not run` });
    }
    consequences.textContent = `Needs: ${parts.join('; ')}.`;
  }
  select.addEventListener('change', describe);
  controls[field.name] = {
    get: () => {
      if (!select.value) return [];
      const o = select.selectedOptions[0];
      return [{ id: select.value, href: `${SERVICE_CATALOG_BASE}/serviceSpecification/${select.value}`, name: o.dataset.name || o.textContent, '@referredType': 'ServiceSpecification' }];
    },
    set: (item) => {
      const id = item?.serviceSpecification?.[0]?.id || '';
      fulfilmentState.original = id || null;
      if ([...select.options].some((o) => o.value === id)) { select.value = id; describe(); } else { pending = id; }
    },
  };
  return [select, consequences];
}

/* The Decomposition panel (CFS step 2): product spec → customer-facing service (its
 * fulfilment family) → resource-facing services (what each consumes) → resource
 * specification (its seam) → this tenant's vendor for that seam. Read-only, built
 * with createElement, and honest when a link is missing: a spec that names no CFS
 * says the orchestrator falls back to the category; a seam nobody configures says
 * the fleet's own adapter serves it. Nothing here is ever submitted. */
function decompositionControl(field) {
  const box = document.createElement('div');
  box.className = 'decomposition';
  box.dataset.testid = 'decomposition';
  const line = (text, cls) => { const p = document.createElement('p'); if (cls) p.className = cls; p.textContent = text; box.appendChild(p); return p; };
  const fetchJson = (path) => authFetch(`${API_BASE.replace(/\/tmf-api\/.*$/, '')}${path}`, { headers: { 'Cache-Control': 'no-cache' } })
    .then((r) => (r.ok ? r.json() : null)).catch(() => null);
  const charValue = (entity, list, name) => {
    const c = (entity[list] || []).find((x) => x.name === name);
    const vals = c ? (c[list.replace('Characteristic', 'CharacteristicValue')] || []) : [];
    return vals.length ? vals[0].value : undefined;
  };
  const vendors = (window.BSS_CONSOLE_CONFIG || {}).seamVendors || {};
  async function render(item) {
    box.replaceChildren();
    const specRef = item && item.productSpecification;
    if (!specRef || !specRef.id) { line('This offering names no product specification yet.', 'muted'); return; }
    const spec = await fetchJson(`/tmf-api/productCatalogManagement/v4/productSpecification/${specRef.id}`);
    if (!spec) { line('The product specification could not be read.', 'muted'); return; }
    line(`Product specification: ${spec.name}`);
    const cfsRef = (spec.serviceSpecification || [])[0];
    if (!cfsRef || !cfsRef.id) { line('This specification names no customer-facing service — the orchestrator falls back to the offering\'s category.', 'muted'); await dryRun(item); return; }
    const cfs = await fetchJson(`/tmf-api/serviceCatalogManagement/v4/serviceSpecification/${cfsRef.id}`);
    if (!cfs) { line(`Customer-facing service ${cfsRef.name || ''} could not be read.`, 'muted'); return; }
    line(`Customer-facing service: ${cfs.name} — fulfilled as ${charValue(cfs, 'serviceSpecCharacteristic', 'fulfilmentFamily') || 'the category decides'}`);
    const edges = (cfs.serviceSpecRelationship || []).filter((e) => e.relationshipType === 'reliesOn');
    const family = charValue(cfs, 'serviceSpecCharacteristic', 'fulfilmentFamily');
    if (!edges.length) { line(family === 'billing-only' ? 'Nothing to provision: this product only bills.' : 'Needs no resource-facing service: realised inside this BSS.', 'muted'); await dryRun(item); return; }
    const list = document.createElement('ul');
    for (const edge of edges) {
      const rfs = await fetchJson(`/tmf-api/serviceCatalogManagement/v4/serviceSpecification/${edge.id}`);
      const consumes = ((edge.serviceSpecRelationshipCharacteristic || []).find((c) => c.name === 'consumes') || {});
      const consumed = ((consumes.serviceSpecCharacteristicValue || [])[0] || {}).value;
      const rsRef = rfs ? (rfs.resourceSpecification || [])[0] : null;
      const rs = rsRef && rsRef.id ? await fetchJson(`/tmf-api/resourceCatalogManagement/v4/resourceSpecification/${rsRef.id}`) : null;
      const seam = rs ? charValue(rs, 'resourceSpecCharacteristic', 'seam') : undefined;
      const vendor = seam ? (vendors[seam] ? `${vendors[seam]}, this tenant's choice` : 'the fleet\'s built-in adapter') : undefined;
      const li = document.createElement('li');
      li.textContent = `${rfs ? rfs.name : edge.name || 'resource-facing service'}`
        + (consumed ? ` — reads ${consumed.split(',').join(', ')}` : '')
        + (rs ? ` → ${rs.name} (${seam || 'seam not declared'}; provided by ${vendor})` : ' → names no resource specification');
      list.appendChild(li);
    }
    box.appendChild(list);
    await dryRun(item);
  }
  /* What will happen when someone orders this — the executor's plan with no adapter
   * called, one sentence per step, and whether it can launch here. Read-only. */
  async function dryRun(item) {
    const r = await authFetch('/som/v1/fulfilment/dryRun', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
      body: JSON.stringify({ offeringId: item.id }) }).then((x) => (x.ok ? x.json() : null)).catch(() => null);
    const head = line('What will happen when someone orders this', 'dry-run-head');
    head.dataset.testid = 'dry-run';
    if (!r) { line('The orchestrator could not be asked right now.', 'muted'); return; }
    const ol = document.createElement('ul'); ol.className = 'dry-run';
    for (const s of (r.summary || []).slice(0, -1)) { const li = document.createElement('li'); li.textContent = s; ol.appendChild(li); }
    box.appendChild(ol);
    const verdict = line((r.summary || []).slice(-1)[0] || r.reason || '', r.verdict === 'LAUNCHABLE' ? 'ok' : r.verdict === 'FALLBACK' ? 'muted' : 'warn');
    verdict.dataset.verdict = r.verdict || '';
  }
  controls[field.name] = { get: () => undefined, set: (item) => { render(item); } };
  return [box];
}
