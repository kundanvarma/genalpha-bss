/*
 * TMF Open API client, same-origin through the gateway. The backends enforce
 * party scoping ("my orders", "my products"); this client never filters by
 * party itself.
 */
import { authFetch, publicFetch, tokenClaims } from './auth.js';

const CATALOG = '/tmf-api/productCatalogManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const INVENTORY = '/tmf-api/productInventory/v4';
const PARTY = '/tmf-api/party/v4';
const STOCK = '/tmf-api/productStockManagement/v4';
const PAYMENT = '/tmf-api/paymentManagement/v4';

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function listOfferings() {
  return json(await publicFetch(`${CATALOG}/productOffering?limit=100&lifecycleStatus=Active`));
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
export async function availabilityFor(offeringId) {
  const rows = await json(await publicFetch(`${STOCK}/productStock?productOfferingId=${offeringId}`));
  if (!rows.length) return null;
  return rows.reduce((sum, r) => sum + (r.availableQuantity?.amount ?? 0), 0);
}

/** All active prices indexed by id, so offering price refs resolve locally. */
export async function priceIndex() {
  const prices = await json(await publicFetch(`${CATALOG}/productOfferingPrice?limit=100`));
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

export async function updateMyParty(patch) {
  const claims = tokenClaims();
  return json(await authFetch(`${PARTY}/individual/${claims.sub}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

/**
 * Authorizes the one-time charges with the payment service (mock PSP in dev).
 * Card details go straight to the API and are never stored client-side.
 * A decline surfaces as an Error with the PSP's reason.
 */
export async function createPayment(amount, card, description) {
  return json(await authFetch(`${PAYMENT}/payment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      amount: { unit: amount.unit, value: amount.value },
      paymentMethod: { '@type': 'bankCard', ...card },
    }),
  }));
}

/**
 * One TMF622 order for the whole cart: a top-level item per cart line with
 * its quantity, and a configured bundle's choices (phone, color, storage) as
 * nested productOrderItem children carrying product.productCharacteristic.
 * `shippingPlace` (a GeographicAddress) rides every physical item — callers
 * mark lines/selections physical via the stock service.
 */
export async function checkoutCart(lines, shippingPlace = null, paymentRefs = null) {
  const productFor = (characteristics, physical) => {
    const product = {};
    if (characteristics && Object.keys(characteristics).length) {
      product.productCharacteristic = Object.entries(characteristics)
        .map(([name, value]) => ({ name, value }));
    }
    if (physical && shippingPlace) {
      product.place = [shippingPlace];
    }
    return Object.keys(product).length ? { product } : {};
  };
  const items = lines.map((line, i) => ({
    id: String(i + 1),
    action: 'add',
    quantity: line.quantity,
    productOffering: { id: line.offeringId, name: line.name, '@referredType': 'ProductOffering' },
    ...productFor(null, line.physical),
    ...(line.selections?.length ? {
      productOrderItem: line.selections.map((s, j) => ({
        id: `${i + 1}.${j + 1}`,
        action: 'add',
        quantity: line.quantity,
        productOffering: { id: s.offeringId, name: s.name, '@referredType': 'ProductOffering' },
        ...productFor(s.characteristics, s.physical),
      })),
    } : {}),
  }));
  const description = lines
    .map((l) => l.quantity > 1 ? `${l.name} ×${l.quantity}` : l.name)
    .join(', ');
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      productOrderItem: items,
      ...(paymentRefs?.length ? { payment: paymentRefs } : {}),
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
