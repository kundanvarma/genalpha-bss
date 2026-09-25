// holdings: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { AGREEMENT, CART, CATALOG, PORTING, PROMO, RECOMMEND, SERVICE_INV, STOCK, USAGE, VAULT, authFetch, json } from './_http.js';

export async function cartsOf(customerId) {
  return json(await authFetch(`${CART}/shoppingCart?limit=10&status=active&relatedPartyId=${customerId}`));
}

/* CSR 360 catch-up reads — all fail-soft: a deployment without the
 * component simply shows an empty card. */
export async function usageOf(customerId) {
  try {
    const report = await json(await authFetch(`${USAGE}/queryUsageConsumption?relatedPartyId=${customerId}`));
    return report.bucket || [];
  } catch { return []; }
}

export async function agreementsOf(customerId) {
  try {
    return await json(await authFetch(`${AGREEMENT}/agreement?relatedPartyId=${customerId}&limit=50`));
  } catch { return []; }
}

export async function activeServicesOf(customerId) {
  try {
    return await json(await authFetch(`${SERVICE_INV}/service?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function portingOrdersOf(customerId) {
  try {
    return await json(await authFetch(`${PORTING}/numberPortingOrder?relatedPartyId=${customerId}`));
  } catch { return []; }
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
  try {
    return await json(await authFetch(`${PROMO}/redemption?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function paymentMethodsOf(customerId) {
  try {
    return await json(await authFetch(`${VAULT}/paymentMethod?relatedPartyId=${customerId}`));
  } catch { return []; }
}

export async function revokePaymentMethod(id) {
  const res = await authFetch(`${VAULT}/paymentMethod/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function recommendationsOf(customerId) {
  try {
    const recs = await json(await authFetch(`${RECOMMEND}/recommendation?relatedPartyId=${customerId}`));
    return recs[0]?.recommendationItem || [];
  } catch { return []; }
}

export async function stockLevels() {
  return json(await authFetch(`${STOCK}/productStock?limit=100`));
}

export async function offeringNames() {
  const offerings = await json(await authFetch(`${CATALOG}/productOffering?limit=100`));
  return Object.fromEntries(offerings.map((o) => [o.id, o.name]));
}
