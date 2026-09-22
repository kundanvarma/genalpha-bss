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
