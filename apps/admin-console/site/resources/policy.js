/* Resources 8/9 — audiences, visitor consent, policy rules, number porting. */
'use strict';

RESOURCES.push(
  {
    path: 'audience',
    base: '/insight/v1',
    title: 'Saved audiences',
    // The saved BSS-native audiences (built in Audience builder): overview with
    // population + member count. View expands the actual members (who's in it);
    // Delete keeps the list from cluttering. (The GA4/analytics catalog is a
    // separate seam, per-tenant and only when a provider is bound.)
    readOnly: true,
    allowDelete: true,
    fields: [],
    columns: ['name', 'population', 'memberCount'],
    detail: async (item) => {
      const res = await authFetch(`/insight/v1/audience/${item.id}/members?limit=200`);
      const members = res.ok ? await res.json() : [];
      if (!members.length) return [{ member: 'no members yet' }];
      const rows = members.slice(0, 200).map((m) => ({
        who: m.email || m.name || m.partyId || m.visitorId || m.id || '—',
        id: m.partyId || m.visitorId || '',
      }));
      if (members.length > rows.length) rows.push({ who: `…and ${members.length - rows.length} more`, id: '' });
      return rows;
    },
  },
  {
    path: 'profile',
    base: '/insight/v1',
    title: 'Visitor consent',
    // The consent ledger: WHO the shop is watching, under WHICH consent —
    // and what it learned. Read-only by design; the visitor owns the data,
    // the operator only gets to SEE what it holds. NB: these are first-party
    // TRACKING/personalization consents (cookie family), NOT contact consent
    // (that lives in prospect consent/lawfulBasis + the DNC suppression ledger).
    readOnly: true,
    serverSearch: true, // the box searches ALL visitors (by id) server-side, not just this page
    fields: [],
    columns: ['visitorId', 'partyId', 'analyticsConsent', 'personalizationConsent', 'utmSource', 'lastUpdate'],
    detail: async (item) => {
      const res = await authFetch(`/insight/v1/profile?visitorId=${item.visitorId}`);
      const full = await res.json();
      const yn = (v) => (v ? 'granted' : 'declined');
      return [{
        consent: `Analytics: ${yn(full.analyticsConsent)} · Personalization: ${yn(full.personalizationConsent)}`,
        events: full.eventCount,
        interests: (full.interests || []).map((i) => `${i.category} (${i.views})`).join(', ') || '—',
        campaign: full.utmSource || '—',
      }];
    },
  },
  {
    path: 'policyRule',
    base: POLICY_BASE,
    title: 'Rules',
    // Business rules authored as DATA: create/enable one here and the next
    // order is checked against it — no redeploy. The builder turns plain
    // choices into a JSON-logic condition; "Advanced" lets you write one.
    fields: [
      { name: 'name', label: 'Rule name', required: true },
      { name: 'ruleKind', label: 'What kind of rule', kind: 'select', required: true, options: [
        { value: 'quantity-cap', label: 'Block: limit how many of an item can be ordered' },
        { value: 'incompatibility', label: 'Block: two items cannot be bought together' },
        { value: 'requires-verified-id', label: 'Block: item requires a verified identity (BankID)' },
        { value: 'advanced', label: 'Block: advanced — write raw JSON-logic' },
        { value: 'price-verified', label: 'Price: discount / surcharge for verified customers' },
        { value: 'price-when-item', label: 'Price: discount / surcharge when the cart has an item' },
        { value: 'price-always', label: 'Price: discount / surcharge for everyone' },
        { value: 'price-loyalty-tier', label: 'Loyalty tier benefit (e.g. gold pays less)' },
  { value: 'price-company', label: 'Price: negotiated deal for one company (B2B)' },
        { value: 'price-characteristic', label: 'Price: campaign on a configured choice (e.g. a colour)' },
        { value: 'price-volume', label: 'Price: volume deal — any company with enough people (B2B)' },
        { value: 'price-advanced', label: 'Price: advanced — raw JSON-logic condition' },
        { value: 'perso-interest', label: 'Personalize: visitors interested in a category see a banner + pinned offer' },
        { value: 'perso-segment', label: 'Personalize: an analytics segment (audience) sees a banner + pinned offer' },
      ] },
      { name: 'organization', label: 'Company (the deal applies to this organization only)', kind: 'ref',
        base: '/tmf-api/party/v4', resource: 'organization', referredType: 'Organization',
        showWhen: { field: 'ruleKind', in: ['price-company'] } },
      { name: 'minMembers', label: 'Minimum people billing together', kind: 'number', placeholder: '10',
        showWhen: { field: 'ruleKind', in: ['price-volume'] } },
      { name: 'characteristicName', label: 'Characteristic (as on the spec, e.g. color)', placeholder: 'color',
        showWhen: { field: 'ruleKind', in: ['price-characteristic'] } },
      { name: 'characteristicValue', label: 'Value the campaign applies to (e.g. Icy Blue)',
        showWhen: { field: 'ruleKind', in: ['price-characteristic'] } },
      { name: 'interestCategory', label: 'Interest — the category they have been browsing (e.g. Devices)',
        showWhen: { field: 'ruleKind', in: ['perso-interest'] } },
      { name: 'segmentName', label: 'Segment — audience name from your analytics (e.g. high-value-browsers)',
        showWhen: { field: 'ruleKind', in: ['perso-segment'] } },
      { name: 'pinnedOffering', label: 'Offering to pin on top of the shop', kind: 'ref',
        resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['perso-interest', 'perso-segment'] } },
      { name: 'offeringA', label: 'Item', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['quantity-cap', 'incompatibility', 'requires-verified-id', 'price-when-item'] } },
      { name: 'maxQuantity', label: 'Max quantity (blank = 1)', kind: 'number',
        showWhen: { field: 'ruleKind', in: ['quantity-cap'] } },
      { name: 'offeringB', label: 'Second item (cannot be bought with the first)', kind: 'ref', resource: 'productOffering', referredType: 'ProductOffering',
        showWhen: { field: 'ruleKind', in: ['incompatibility'] } },
      { name: 'adjustmentType', label: 'Adjustment type', kind: 'select', options: [
        { value: '', label: '—' },
        { value: 'percent', label: 'Percent of subtotal' },
        { value: 'amount', label: 'Fixed amount' },
      ], showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-company', 'price-volume', 'price-advanced'] } },
      { name: 'adjustmentValue', label: 'Adjustment value — negative = discount, positive = surcharge', kind: 'number',
        showWhen: { field: 'ruleKind', in: ['price-verified', 'price-when-item', 'price-always', 'price-company', 'price-volume', 'price-advanced'] } },
      { name: 'condition', label: 'JSON-logic condition', kind: 'longtext',
        showWhen: { field: 'ruleKind', in: ['advanced', 'price-advanced'] } },
      { name: 'message', label: 'Message / label shown to the customer', required: true },
      { name: 'priority', label: 'Priority (lower runs first)', kind: 'number', placeholder: '100' },
      { name: 'enabled', label: 'Enabled', kind: 'checkbox' },
    ],
    assemble: (body) => {
      const idOf = (r) => (r && typeof r === 'object' ? r.id : r);
      const a = idOf(body.offeringA);
      const b = idOf(body.offeringB);
      const max = Number(body.maxQuantity);
      const kind = body.ruleKind || '';
      const isPricing = kind.startsWith('price-');
      const isPerso = kind.startsWith('perso-');
      let condition = body.condition;
      switch (kind) {
        case 'perso-interest':
          condition = JSON.stringify({ in: [(body.interestCategory || '').trim(), { var: 'interests' }] });
          break;
        case 'perso-segment':
          condition = JSON.stringify({ in: [(body.segmentName || '').trim(), { var: 'segments' }] });
          break;
        case 'quantity-cap':
          condition = JSON.stringify({ '>': [{ var: a ? `quantityByOffering.${a}` : 'maxLineQuantity' }, Number.isFinite(max) && max > 0 ? max : 1] });
          break;
        case 'incompatibility':
          condition = JSON.stringify({ and: [{ in: [a, { var: 'offeringIds' }] }, { in: [b, { var: 'offeringIds' }] }] });
          break;
        case 'requires-verified-id':
          condition = JSON.stringify({ and: [{ in: [a, { var: 'offeringIds' }] }, { '!': { var: 'verifiedIdentity' } }] });
          break;
        case 'price-verified':
          condition = JSON.stringify({ var: 'verifiedIdentity' });
          break;
        case 'price-when-item':
          condition = JSON.stringify({ in: [a, { var: 'offeringIds' }] });
          break;
        case 'price-always':
          condition = JSON.stringify({ '==': [1, 1] });
          break;
        case 'price-characteristic':
          // the cart preview and the billing run both put the configured
          // picks into the context as "name:value" strings
          condition = JSON.stringify({ in: [
            `${(body.characteristicName || 'color').trim()}:${(body.characteristicValue || '').trim()}`,
            { var: 'characteristicValues' }] });
          break;
        case 'price-loyalty-tier':
      condition = { '==': [{ var: 'loyaltyTier' }, body.loyaltyTier || 'gold'] };
      break;
    case 'price-company':
          // organizationId only exists in the context when the payer IS a
          // company, so this can never touch a consumer.
          condition = JSON.stringify({ '==': [{ var: 'organizationId' }, idOf(body.organization)] });
          break;
        case 'price-volume':
          condition = JSON.stringify({ '>=': [{ var: 'memberCount' },
            Number.isFinite(Number(body.minMembers)) && Number(body.minMembers) > 0 ? Number(body.minMembers) : 2] });
          break;
        default:
          break;
      }
      return {
        name: body.name,
        description: body.description,
        domain: isPerso ? 'personalization' : isPricing ? 'pricing' : 'order',
        effect: isPerso ? 'experience' : isPricing ? 'adjust' : 'deny',
        priority: body.priority,
        enabled: body.enabled,
        condition,
        message: body.message,
        adjustmentType: isPricing ? (body.adjustmentType || 'percent') : undefined,
        adjustmentValue: isPricing ? body.adjustmentValue : undefined,
        experience: isPerso && body.pinnedOffering
          ? { teaserOfferingId: idOf(body.pinnedOffering) } : undefined,
      };
    },
    columns: ['name', 'domain', 'effect', 'enabled', 'priority', 'adjustmentValue', 'condition', 'lastUpdate'],
    rowAction: {
      label: (item) => (item.enabled ? 'Disable' : 'Enable'),
      apply: (item) => authFetch(`${POLICY_BASE}/policyRule/${item.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: !item.enabled }),
      }),
    },
    // Dry-run: test the ENABLED rules against a sample order/cart without
    // placing anything — same engine, same endpoints the pipeline uses.
    tester: true,
  },
  {
    path: 'numberPortingOrder',
    base: PORTING_BASE,
    title: 'Porting',
    // Back office view of MNP: every port-in/out in the tenant, with the
    // cutover action for scheduled orders. Creating one here is the assisted
    // path (e.g. a customer phoning in a port-out).
    noEdit: true,
    noDelete: true,
    fields: [
      { name: 'phoneNumber', label: 'Phone number (E.164, e.g. +4791234567)', required: true },
      { name: 'direction', label: 'Direction', kind: 'select', required: true, options: [
        { value: 'portIn', label: 'Port-in — customer joins us and keeps their number' },
        { value: 'portOut', label: 'Port-out — customer leaves with their number' },
      ] },
      { name: 'country', label: 'Country (ISO alpha-2)', placeholder: 'NO', required: true },
      { name: 'otherOperator', label: 'Other operator', placeholder: 'Telenor' },
      { name: 'customerPartyId', label: 'Customer party id', required: true },
    ],
    assemble: (body) => ({
      phoneNumber: body.phoneNumber,
      direction: body.direction,
      country: body.country,
      otherOperator: body.otherOperator,
      relatedParty: [{ id: body.customerPartyId, role: 'customer' }],
    }),
    columns: ['phoneNumber', 'direction', 'status', 'country', 'otherOperator', 'clearinghouse', 'scheduledCutover'],
    rowAction: {
      label: (item) => (item.status === 'scheduled' ? 'Complete cutover' : ''),
      apply: (item) => authFetch(`${PORTING_BASE}/numberPortingOrder/${item.id}/complete`, { method: 'POST' }),
    },
  },
);
