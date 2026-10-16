// holdings: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { AGREEMENT, CART, CATALOG, PORTING, PROMO, RECOMMEND, SERVICE_INV, STOCK, USAGE, VAULT, authFetch, json } from './_http.js';

export async function cartsOf(customerId) {
  return json(await authFetch(`${CART}/shoppingCart?limit=10&status=active&relatedPartyId=${customerId}`));
}

/* CSR 360 catch-up reads. They THROW when the component does not answer: the
 * page keeps them fail-soft (pages/customer/reachable.jsx) and says "could not
 * reach", because a bare [] here read as "this customer has none" (#289). */
export async function usageOf(customerId) {
  const report = await json(await authFetch(`${USAGE}/queryUsageConsumption?relatedPartyId=${customerId}`));
  return report.bucket || [];
}

export async function agreementsOf(customerId) {
  return json(await authFetch(`${AGREEMENT}/agreement?relatedPartyId=${customerId}&limit=50`));
}

/**
 * THE DECLARED ANSWER (#143 step 1). Each customer-facing service spec says
 * what it is — `fulfilmentFamily` — and the desk used to ignore it and
 * pattern-match names instead, falling through to the word "Service". The
 * catalog already knows; this is the desk asking. Read once per customer view:
 * there are fewer than a dozen specs, and they do not change inside a session.
 */
export async function fulfilmentFamilies() {
  const rows = await json(await authFetch(
    '/tmf-api/serviceCatalogManagement/v4/serviceSpecification?limit=100'));
  const byId = {};
  for (const spec of Array.isArray(rows) ? rows : []) {
    for (const ch of spec.serviceSpecCharacteristic || []) {
      if (ch.name !== 'fulfilmentFamily') continue;
      const value = (ch.serviceSpecCharacteristicValue || [])[0];
      if (value && value.value) byId[spec.id] = String(value.value);
    }
  }
  return byId;
}

export async function activeServicesOf(customerId) {
  return json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${customerId}`));
}

export async function portingOrdersOf(customerId) {
  return json(await authFetch(`${PORTING}/numberPortingOrder?relatedPartyId=${customerId}`));
}

export async function completeCutover(portingOrderId) {
  return json(await authFetch(`${PORTING}/numberPortingOrder/${portingOrderId}/complete`, {
    method: 'POST',
  }));
}

export async function ceaseService(serviceId, reason) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/terminate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason || 'ceased by agent' }),
  }));
}

export async function redemptionsOf(customerId) {
  return json(await authFetch(`${PROMO}/redemption?relatedPartyId=${customerId}`));
}

export async function paymentMethodsOf(customerId) {
  return json(await authFetch(`${VAULT}/paymentMethod?relatedPartyId=${customerId}`));
}

export async function revokePaymentMethod(id) {
  const res = await authFetch(`${VAULT}/paymentMethod/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function recommendationsOf(customerId) {
  const recs = await json(await authFetch(`${RECOMMEND}/recommendation?relatedPartyId=${customerId}`));
  return recs[0]?.recommendationItem || [];
}

export async function stockLevels() {
  return json(await authFetch(`${STOCK}/productStock?limit=100`));
}

export async function offeringNames() {
  const offerings = await json(await authFetch(`${CATALOG}/productOffering?limit=100`));
  return Object.fromEntries(offerings.map((o) => [o.id, o.name]));
}
