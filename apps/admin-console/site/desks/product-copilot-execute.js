/* Product Copilot — applying a proposal in dependency order with the owner's token. */
'use strict';

async function copilotExecute(proposal, context = { offerings: [] }) {
  const created = []; // [{kind, id, base?}] for rollback, reverse order
  const jsonOf = async (res, what) => {
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      throw new Error(`${what}: ${problem.message || 'HTTP ' + res.status}`);
    }
    return res.json();
  };
  const post = (path, body) => authFetch(`${API_BASE}/${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  try {
    const specs = {};    // ref -> {id, name}
    for (const spec of proposal.specs || []) {
      // the proposed fulfilment pattern rides the spec into the catalog (Create is the click — ADR 0012)
      const fp = spec.fulfilmentPattern;
      const made = await jsonOf(await post('productSpecification', {
        name: spec.name, brand: spec.brand, lifecycleStatus: 'Active',
        productSpecCharacteristic: spec.productSpecCharacteristic || [],
        ...(fp && fp.cfsId ? { serviceSpecification: [{ id: fp.cfsId, name: fp.cfsName,
          href: `${SERVICE_CATALOG_BASE}/serviceSpecification/${fp.cfsId}`, '@referredType': 'ServiceSpecification' }] } : {}),
      }), `spec "${spec.name}"`);
      specs[spec.ref] = made;
      created.push({ kind: 'productSpecification', id: made.id });
    }
    const prices = {};
    for (const price of proposal.prices || []) {
      // real models occasionally skip a price name or write "EUR/month" as
      // the unit — normalize deterministically instead of failing the apply
      const priceName = price.name
        || `${(proposal.offerings || []).find((o) => (o.priceRefs || []).includes(price.ref))?.name || 'Copilot'} price`;
      const money = price.price ? { ...price.price,
        unit: String(price.price.unit || 'EUR').split('/')[0].trim() } : price.price;
      const made = await jsonOf(await post('productOfferingPrice', {
        name: priceName, priceType: price.priceType || 'recurring',
        recurringChargePeriodType: price.recurringChargePeriodType,
        price: money, prodSpecCharValueUse: price.prodSpecCharValueUse || undefined,
        unitOfMeasure: price.unitOfMeasure || undefined,
        validFor: price.validFor || undefined,
        pricingLogicAlgorithm: price.pricingLogicAlgorithm || undefined,
        lifecycleStatus: 'Active',
      }), `price "${priceName}"`);
      prices[price.ref] = made;
      created.push({ kind: 'productOfferingPrice', id: made.id });
    }
    const offerings = {};
    const plain = (proposal.offerings || []).filter((o) => !(o.bundledChildren || []).length);
    const bundles = (proposal.offerings || []).filter((o) => (o.bundledChildren || []).length);
    for (const o of [...plain, ...bundles]) {
      const body = {
        name: o.name, description: o.description, lifecycleStatus: 'Active',
        isBundle: Boolean(o.isBundle || (o.bundledChildren || []).length),
        category: o.category, productOfferingTerm: o.productOfferingTerm,
      };
      // launch window + channels ride from the proposal; unknown channel ids are dropped, never sent
      if (o.validFor && (o.validFor.startDateTime || o.validFor.endDateTime)) body.validFor = o.validFor;
      if (Array.isArray(o.channel) && o.channel.length) {
        const known = o.channel.map((c) => (typeof c === 'string' ? c : c && c.id)).filter((id) => CHANNELS.some((k) => k.value === id));
        if (known.length) body.channel = known.map((id) => ({ id, name: CHANNELS.find((k) => k.value === id).label }));
      }
      if (o.specRef && specs[o.specRef]) {
        body.productSpecification = { id: specs[o.specRef].id, name: specs[o.specRef].name,
          '@referredType': 'ProductSpecification' };
      }
      if ((o.priceRefs || []).length) {
        body.productOfferingPrice = o.priceRefs.map((ref) => ({
          id: prices[ref].id, name: prices[ref].name, '@referredType': 'ProductOfferingPrice' }));
      }
      if ((o.relationships || []).length) {
        // requires / excludes: a ref from this proposal or the exact name of an existing offering
        body.productOfferingRelationship = o.relationships.map((rel) => {
          const target = rel.offeringRef ? offerings[rel.offeringRef] : null;
          const existing = !target && rel.existingName ? (context.all || context.offerings).find((x) => String(x.name).toLowerCase() === String(rel.existingName).toLowerCase()) : null;
          return { ...(target ? { id: target.id, name: target.name } : existing ? { id: existing.id, name: existing.name } : { name: rel.existingName }),
            relationshipType: rel.relationshipType, ...(rel.role ? { role: rel.role } : {}) };
        }).filter((r) => r.id);
      }
      if ((o.bundledChildren || []).length) {
        body.bundledProductOffering = o.bundledChildren.map((child) => {
          const target = child.offeringRef ? offerings[child.offeringRef] : null;
          const entry = target
            ? { id: target.id, name: target.name, href: target.href, '@referredType': 'ProductOffering' }
            : { name: child.existingName, '@referredType': 'ProductOffering' };
          if (child.optional) {
            entry.bundledProductOfferingOption = {
              numberRelOfferLowerLimit: 0, numberRelOfferUpperLimit: 1 };
          }
          return entry;
        });
      }
      const made = await jsonOf(await post('productOffering', body), `offering "${o.name}"`);
      offerings[o.ref] = made;
      created.push({ kind: 'productOffering', id: made.id });
      // launch governance: an AI proposal asks like anyone else — the verdict is shown, never assumed
      if (made.lifecycleStatus !== 'Active' && made.lifecycleStatus !== 'Launched') {
        const gv = await authFetch(`${API_BASE}/productOffering/${made.id}/governance/request`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ note: 'copilot proposal', origin: 'ai' }) }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
        if (gv) {
          created.push({ kind: 'governance', id: made.id, verdict: gv.governanceState === 'approved'
            ? `"${made.name}" launches by itself — inside envelope '${gv.envelope ? gv.envelope.name : ''}' (Approvals › Launch now)`
            : `"${made.name}" needs an approver — outside every envelope (see Approvals)` });
        }
      }
    }
    // cross-product discounts land in the pricing-rules engine — the same
    // rules the cart preview and the billing run already apply
    const catalogNames = {}; // existing-offering name -> id, resolved lazily
    for (const rule of proposal.pricingRules || []) {
      const targets = [];
      for (const t of rule.whenCartHas || []) {
        if (offerings[t]) { targets.push(offerings[t].id); continue; }
        if (!(t in catalogNames)) {
          const found = await (await authFetch(
            `${API_BASE}/productOffering?name=${encodeURIComponent(t)}`)).json().catch(() => []);
          catalogNames[t] = found[0]?.id || null;
        }
        if (catalogNames[t]) targets.push(catalogNames[t]);
      }
      const clauses = targets.map((id) => ({ in: [id, { var: 'offeringIds' }] }));
      // consumer-only: the rule requires the ABSENCE of a paying organization,
      // so it can never touch a company order or a consolidated invoice
      if (rule.audience === 'consumer') {
        clauses.push({ '!': { var: 'organizationId' } });
      } else if (rule.audience === 'business') {
        clauses.push({ '!!': { var: 'organizationId' } });
      }
      const madeRule = await jsonOf(await authFetch(`${POLICY_BASE}/policyRule`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: rule.name, domain: 'pricing', effect: 'adjust', priority: 100, enabled: true,
          condition: JSON.stringify(clauses.length > 1 ? { and: clauses } : clauses[0] || { '==': [1, 1] }),
          adjustmentType: rule.adjustmentType || 'percent',
          adjustmentValue: Number(rule.adjustmentValue),
          message: rule.message || rule.name,
        }),
      }), `pricing rule "${rule.name}"`);
      created.push({ kind: 'policyRule', id: madeRule.id, base: POLICY_BASE });
    }
    // chatted personalization: what a consenting guest SEES becomes a
    // policy row (domain 'personalization') — the same rules the insight
    // experience endpoint already reads, disabled/deleted in Rules
    for (const er of proposal.experienceRules || []) {
      let pinId = null;
      if (er.pinOffering) {
        if (offerings[er.pinOffering]) {
          pinId = offerings[er.pinOffering].id;
        } else {
          const found = await (await authFetch(
            `${API_BASE}/productOffering?name=${encodeURIComponent(er.pinOffering)}`)).json().catch(() => []);
          pinId = found[0]?.id || null;
        }
      }
      const madeExp = await jsonOf(await authFetch(`${POLICY_BASE}/policyRule`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: er.name, domain: 'personalization', effect: 'experience',
          priority: 10, enabled: true,
          condition: JSON.stringify({ in: [er.whenInterest, { var: 'interests' }] }),
          message: er.banner,
          experience: pinId ? { teaserOfferingId: pinId } : {},
        }),
      }), `experience rule "${er.name}"`);
      created.push({ kind: 'policyRule', id: madeExp.id, base: POLICY_BASE });
    }
    const out = Object.values(offerings);
    out.verdicts = created.filter((r) => r.kind === 'governance').map((r) => r.verdict);
    return out;
  } catch (e) {
    // roll back what was made, newest first — a half-created product family
    // is worse than none
    for (const row of created.reverse().filter((r) => r.kind !== 'governance')) {
      await authFetch(`${row.base || API_BASE}/${row.kind}/${row.id}`, { method: 'DELETE' }).catch(() => {});
    }
    throw e;
  }
}
