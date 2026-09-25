// catalog: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { CATALOG, STOCK, authFetch, json, publicFetch } from './_http.js';

export async function listOfferings() {
  // L2 preview: staff append ?preview=1 to walk the unlaunched shelf —
  // the SERVER decides what a token may see; guests get Active regardless.
  // The API caps a page at 100 and the shelf outgrew that — page through
  // with offset until a short page (same lesson as priceIndex).
  const preview = new URLSearchParams(window.location.search).get('preview') === '1';
  const fetcher = preview ? authFetch : publicFetch;
  const filter = preview ? '' : '&lifecycleStatus=Active';
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const page = await json(await fetcher(
      `${CATALOG}/productOffering?limit=100&offset=${offset}${filter}`));
    if (!Array.isArray(page)) break;
    all.push(...page);
    if (page.length < 100) break;
  }
  return all;
}

export async function getOffering(id) {
  return json(await publicFetch(`${CATALOG}/productOffering/${id}`));
}

export async function getSpec(id) {
  return json(await publicFetch(`${CATALOG}/productSpecification/${id}`));
}

/**
 * Units available for an offering, or null when it is not stock-managed
 * (services and subscriptions have no shelf).
 */
/** TMF760 — the ONE oracle for a configurable product: the choices with allowed values, ranges,
 * defaults and what is in stock; whether these picks are orderable; and the exact price. Channels
 * render this, they never price or validate on their own. */
export async function queryConfiguration(offeringId) {
  const res = await publicFetch('/tmf-api/productConfigurationManagement/v5/queryProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ productConfiguration: { productOffering: { id: offeringId } } }),
  });
  if (!res.ok) throw new Error('configuration unavailable (HTTP ' + res.status + ')');
  const out = await res.json();
  return (out.computedProductConfigurationItem || [])[0] || null;
}

export async function checkConfiguration(offeringId, characteristics = {}, quantity = 1, selectedOptionIds = []) {
  const res = await publicFetch('/tmf-api/productConfigurationManagement/v5/checkProductConfiguration', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ checkProductConfigurationItem: [{ id: '1', productConfiguration: {
      productOffering: { id: offeringId }, quantity,
      selectedOption: selectedOptionIds.map((oid) => ({ id: oid })),
      configurationCharacteristic: Object.entries(characteristics).filter(([, v]) => v != null && v !== '').map(([name, value]) => ({ name, value: String(value) })),
    } }] }),
  });
  if (!res.ok) throw new Error('configuration check failed (HTTP ' + res.status + ')');
  const out = await res.json();
  return (out.checkProductConfigurationItem || [])[0] || null;
}

export async function availabilityFor(offeringId) {
  // Composable deployment: no stock component means nothing is
  // stock-managed — same as an offering without a stock row.
  try {
    const rows = await json(await publicFetch(`${STOCK}/productStock?productOfferingId=${offeringId}`));
    if (!rows.length) return null;
    return rows.reduce((sum, r) => sum + (r.availableQuantity?.amount ?? 0), 0);
  } catch {
    return null;
  }
}

/** All active prices indexed by id, so offering price refs resolve locally.
 * The API caps a page at 100 and the price book outgrew that — page through
 * with offset until a short page, or offerings silently lose their prices. */
export async function priceIndex() {
  const index = {};
  for (let offset = 0; ; offset += 100) {
    const page = await json(await publicFetch(
      `${CATALOG}/productOfferingPrice?limit=100&offset=${offset}`));
    for (const p of page) index[p.id] = p;
    if (!Array.isArray(page) || page.length < 100) break;
  }
  return index;
}
