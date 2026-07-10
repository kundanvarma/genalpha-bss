/*
 * TMF Open API client, same-origin through the gateway. The backends enforce
 * party scoping ("my orders", "my products"); this client never filters by
 * party itself.
 */
import { authFetch, tokenClaims } from './auth.js';

const CATALOG = '/tmf-api/productCatalogManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const INVENTORY = '/tmf-api/productInventory/v4';
const PARTY = '/tmf-api/party/v4';

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function listOfferings() {
  return json(await authFetch(`${CATALOG}/productOffering?limit=100&lifecycleStatus=Active`));
}

export async function getOffering(id) {
  return json(await authFetch(`${CATALOG}/productOffering/${id}`));
}

/** All active prices indexed by id, so offering price refs resolve locally. */
export async function priceIndex() {
  const prices = await json(await authFetch(`${CATALOG}/productOfferingPrice?limit=100`));
  return Object.fromEntries(prices.map((p) => [p.id, p]));
}

/**
 * First sign-in provisions the TMF632 individual; the backend keys it to the
 * token subject, so repeating this is a no-op.
 */
export async function ensureParty() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      givenName: claims.given_name || claims.preferred_username || 'Customer',
      familyName: claims.family_name || '—',
    }),
  }));
}

export async function myParty() {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`));
}

export async function placeOrder(offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description: offering.name,
      productOfferingId: offering.id,
      productOrderItem: [{
        id: '1',
        action: 'add',
        productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' },
      }],
    }),
  }));
}

export async function myOrders() {
  return json(await authFetch(`${ORDERING}/productOrder?limit=100`));
}

export async function cancelOrder(id) {
  return json(await authFetch(`${ORDERING}/productOrder/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'cancelled' }),
  }));
}

export async function myProducts() {
  return json(await authFetch(`${INVENTORY}/product?limit=100`));
}
