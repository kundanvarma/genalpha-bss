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
      { name: 'validFrom', label: 'Available from', placeholder: '2026-10-01T00:00:00+02:00', hint: 'ISO date-time; blank = immediately' },
      { name: 'validTo', label: 'Available until', placeholder: 'blank = forever', hint: 'ISO date-time; blank = forever' },
      // row 3 — what it is
      { name: 'productSpecification', label: 'Specification', kind: 'ref', resource: 'productSpecification', referredType: 'ProductSpecification', half: true, hint: 'The facts: data, validity, network…' },
      { name: 'productOfferingTerm', label: 'Commitment', kind: 'commitment', hint: 'Binding period, if any' },
      { name: 'productOfferingRelationship', label: 'Requires / excludes', kind: 'jsontext', wide: true, placeholder: '[{"id": "<offering id>", "name": "Taranga Fiber 300", "relationshipType": "requires", "role": "prompt"}, {"id": "<offering id>", "name": "Taranga TV", "relationshipType": "excludes"}]', hint: 'TMF620 relationships the configurator enforces: requires (role auto-add | prompt | block), excludes, exchangableTo (the like-for-like change list).' },
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
      { name: 'validFrom', label: 'Available from', placeholder: 'blank = immediately', hint: 'ISO date-time' },
      { name: 'validTo', label: 'Available until', placeholder: 'blank = forever', hint: 'ISO date-time' },
      { name: 'productSpecCharacteristic', label: 'Characteristics', kind: 'jsontext', wide: true,
        hint: 'JSON array. Facts the shop shows and the systems read: Data, Validity, chargingSpecId, sliceProfile, zeroRatedApps. "configurable": true makes a picker.',
        placeholder: '[{"name": "Data", "configurable": false, "productSpecCharacteristicValue": [{"value": "20 GB"}]}]' },
    ],
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
      { name: 'unitOfMeasure', label: 'Per unit of', kind: 'jsontext', placeholder: '{"amount": 1, "units": "seat"}', hint: 'Quantity pricing: the price applies per this many (per seat, per 5 GB). Blank = a flat price.' },
      { name: 'validFor', label: 'Price window', kind: 'jsontext', placeholder: '{"startDateTime": "2026-10-01T00:00:00Z", "endDateTime": "2026-12-31T23:59:59Z"}', hint: 'When this price line applies (an effective-dated segment). Blank = always. Never edit a live price on an offering with subscribers — add a dated segment.' },
      { name: 'pricingLogicAlgorithm', label: 'Algorithm', kind: 'jsontext', wide: true, placeholder: '[{"plaSpecId": "perUnitAbove", "characteristic": "extraProfiles", "threshold": 2, "unitPrice": 10}]  or  [{"plaSpecId": "stepped", "characteristic": "quantity", "tier": [{"valueFrom": 1, "valueTo": 10, "price": 20}, {"valueFrom": 11, "valueTo": 999, "price": 15, "format": "perUnit"}]}]', hint: 'A named algorithm from the documented set: perUnitAbove (base plus unit price above a threshold) or stepped (a tier table on a characteristic or the quantity). Never free-form.' },
      { name: 'prodSpecCharValueUse', label: 'Applies only when', kind: 'jsontext', wide: true, placeholder: '[{"name": "screens", "productSpecCharacteristicValue": [{"value": "5+"}]}]  or a range: [{"name": "extraProfiles", "productSpecCharacteristicValue": [{"valueFrom": 3, "valueTo": 10}]}]', hint: 'The configured choice this price is conditioned on. The specification must declare the choice and its values, or the catalog refuses the offering.' },
    ],
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
      { name: 'stockedProduct', label: 'Variant (optional)', kind: 'jsontext', wide: true, placeholder: '{"productOffering": {"id": "<offering id>"}, "productCharacteristic": [{"name": "boxColour", "value": "Icy Blue"}]}', hint: 'TMF687 stockedProduct: count this row per configured variant (a colour, a storage size). Blank = the offering as a whole. The configurator marks a variant with no stock as not selectable.' },
    ],
    columns: ['name', 'productOffering', 'stockedQuantity', 'reservedQuantity', 'availableQuantity', 'lastUpdate'],
  },
];

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
    if (!cfsRef || !cfsRef.id) { line('This specification names no customer-facing service — the orchestrator falls back to the offering\'s category.', 'muted'); return; }
    const cfs = await fetchJson(`/tmf-api/serviceCatalogManagement/v4/serviceSpecification/${cfsRef.id}`);
    if (!cfs) { line(`Customer-facing service ${cfsRef.name || ''} could not be read.`, 'muted'); return; }
    line(`Customer-facing service: ${cfs.name} — fulfilled as ${charValue(cfs, 'serviceSpecCharacteristic', 'fulfilmentFamily') || 'the category decides'}`);
    const edges = (cfs.serviceSpecRelationship || []).filter((e) => e.relationshipType === 'reliesOn');
    if (!edges.length) { line('Needs no resource-facing service: realised inside this BSS.', 'muted'); return; }
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
  }
  controls[field.name] = { get: () => undefined, set: (item) => { render(item); } };
  return [box];
}
