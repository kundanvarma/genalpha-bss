// qualification: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { APPOINTMENT, QUALIFICATION, authFetch, json, publicFetch } from './_http.js';

/**
 * TMF679 serviceability check — anonymous shop-window functionality.
 * items: [{offeringId, name}]; every item is checked against the place.
 */
export async function checkQualification(items, place) {
  // Composable deployment: no qualification component means nothing is
  // serviceability-gated.
  try {
    return await checkQualificationStrict(items, place);
  } catch {
    return { productOfferingQualificationItem: [] };
  }
}

async function checkQualificationStrict(items, place) {
  return json(await publicFetch(`${QUALIFICATION}/checkProductOfferingQualification`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOfferingQualificationItem: items.map((item, i) => ({
        id: String(i + 1),
        productOffering: { id: item.offeringId, name: item.name, '@referredType': 'ProductOffering' },
        place,
      })),
    }),
  }));
}

/** TMF645 technical footprint: what the network can DELIVER at this place —
 * technology and bandwidth, anonymous like the commercial check above.
 * Composable: no qualification component means no footprint to show. */
export async function queryServiceQualification(place) {
  try {
    return json(await publicFetch(
      '/tmf-api/serviceQualificationManagement/v4/queryServiceQualification', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ searchCriteria: { place } }),
      }));
  } catch {
    return { serviceQualificationItem: [] };
  }
}

/** The shop window's promotional creative (TMF667 documents in category 'banner') — anonymous. */
export async function listBanners() {
  try {
    const rows = await json(await publicFetch('/tmf-api/documentManagement/v4/document/banners'));
    return Array.isArray(rows) ? rows : [];
  } catch {
    return [];
  }
}

/** TMF646 free installer slots — also anonymous. The search says WHERE (the
 * install address as relatedPlace) and FOR WHAT (the gated offerings as
 * relatedEntity), so a tenant's own workforce system can answer by zone and
 * skill; the built-in roster ignores what it does not use. */
export async function searchTimeSlots({ place, offerings } = {}) {
  const body = { '@type': 'SearchTimeSlot' };
  if (place) body.relatedPlace = { role: 'installation', ...place, '@type': 'GeographicAddress' };
  if (offerings?.length) {
    body.relatedEntity = offerings.map((o) => ({ id: o.id, name: o.name, '@referredType': 'ProductOffering' }));
  }
  try {
    return await json(await publicFetch(`${APPOINTMENT}/searchTimeSlot`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }));
  } catch {
    return { availableTimeSlot: [] };
  }
}

export async function createAppointment(slot, orderId, place, description) {
  return json(await authFetch(`${APPOINTMENT}/appointment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      validFor: slot,
      description,
      relatedEntity: [{ id: orderId, '@referredType': 'ProductOrder' }],
      place,
    }),
  }));
}

export async function myAppointments() {
  return json(await authFetch(`${APPOINTMENT}/appointment?limit=100`));
}
