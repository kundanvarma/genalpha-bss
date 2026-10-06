// catalog: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { CATALOG, STOCK, authFetch, json, publicFetch } from './_http.js';
import { imageUrl } from '../media.js';

/*
 * EVERY IMAGE URL IS CHECKED WHERE IT ENTERS, not at each <img> that renders it.
 *
 * Product imagery comes from the catalogue's attachments and, for a tenant with
 * the PIM seam on, from an operator's external product-content system —
 * somebody else's server. Guarding the boundary rather than the six call sites
 * means a seventh <img> cannot forget, and it costs one pass over a list the
 * client already holds. A URL that does not survive the check is removed, so the
 * page shows a missing picture rather than whatever the URL asked the browser
 * to do. See media.js for what survives.
 */
function safeImages(offering) {
  if (!offering || !Array.isArray(offering.attachment)) {
    return offering;
  }
  offering.attachment = offering.attachment
    .map((a) => (a && a.url ? { ...a, url: imageUrl(a.url) } : a))
    .filter((a) => !a || !('url' in a) || a.url);
  return offering;
}

export async function listOfferings() {
  // L2 preview: staff append ?preview=1 to walk the unlaunched shelf —
  // the SERVER decides what a token may see; guests get Active regardless.
  //
  // PAGE BY X-Total-Count, NOT BY A SHORT PAGE. The API caps a page at 100, and
  // it filters what it fetched — a row the caller may not be sold is dropped
  // AFTER the page is taken — so a full page routinely comes back short and
  // "short means the end" silently truncates the shelf. The server-side
  // projection had the identical bug and lost 89 of 288 products from the
  // discovery feed before anyone noticed (#180). TMF630's own total is the
  // honest stopping condition, and the gateway sends it.
  const preview = new URLSearchParams(window.location.search).get('preview') === '1';
  const fetcher = preview ? authFetch : publicFetch;
  const filter = preview ? '' : '&lifecycleStatus=Active';
  const all = [];
  let total = Infinity;
  for (let offset = 0; offset < total; offset += 100) {
    const res = await fetcher(`${CATALOG}/productOffering?limit=100&offset=${offset}${filter}`);
    const page = await json(res);
    if (!Array.isArray(page)) break;
    all.push(...page.map(safeImages));
    const counted = Number(res.headers.get('X-Total-Count'));
    // no header (an older gateway, a stand-in) → fall back to the old rule
    // rather than loop forever
    total = Number.isFinite(counted) && counted >= 0
      ? counted
      : (page.length < 100 ? 0 : Infinity);
  }
  return all;
}

export async function getOffering(id) {
  return safeImages(await json(await publicFetch(`${CATALOG}/productOffering/${id}`)));
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
