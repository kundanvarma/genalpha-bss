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
  const email = claims.email || claims.preferred_username;
  return json(await authFetch(`${PARTY}/individual`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      givenName: claims.given_name || claims.preferred_username || 'Customer',
      familyName: claims.family_name || '—',
      // The email rides along so assisted channels can identify the customer
      // by something a human recognizes, not a UUID.
      ...(email && email.includes('@') ? {
        contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }],
      } : {}),
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
export async function checkoutCart(lines, shippingPlace = null, paymentRefs = null, promotionCode = null) {
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
      ...(promotionCode ? { promotionCode } : {}),
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

/** The SIM behind a line; null when none is on file (pre-SIM activations). */
export async function mySim(serviceId, reveal = false) {
  const res = await authFetch(`${SERVICE_INV}/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`);
  if (!res.ok) return null;
  return res.json();
}

/** OTA PIN change — pushed to the card via the operator's SIM platform. */
export async function resetSimPin(serviceId, newPin) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/sim/resetPin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPin }),
  }));
}

/** One-tap purchase for simple digital items (data top-ups): a bare add order. */
export async function quickOrder(offering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{ action: 'add', productOffering: { id: offering.id, name: offering.name } }],
    }),
  }));
}

/**
 * Plan change (TMF622 action=modify): same service, same number — only the
 * plan swaps. The realizing service rides along so the SOM renames the right
 * line. Completes instantly: no fulfilment, no new MSISDN.
 */
export async function changePlan(productId, serviceId, newOffering) {
  return json(await authFetch(`${ORDERING}/productOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      productOrderItem: [{
        action: 'modify',
        product: { id: productId, ...(serviceId ? { realizingService: [{ id: serviceId }] } : {}) },
        productOffering: { id: newOffering.id, name: newOffering.name },
      }],
    }),
  }));
}

const CONSUMPTION = '/tmf-api/usageConsumption/v4';
const AGREEMENT = '/tmf-api/agreementManagement/v4';

const PROMOTION = '/tmf-api/promotionManagement/v4';
const ADDRESS = '/tmf-api/geographicAddressManagement/v4';

/** TMF673 anonymous validation: normalized address or the reason it fails. */
export async function validateAddress(address) {
  return json(await publicFetch(`${ADDRESS}/geographicAddressValidation`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address }),
  }));
}

/** Anonymous promo-code check — the shop window prices the discount. */
export async function checkPromotion(code) {
  return json(await publicFetch(`${PROMOTION}/checkPromotion`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
  }));
}

const RECOMMENDATION = '/tmf-api/recommendationManagement/v4';
const PAYMENT_METHODS = '/tmf-api/paymentMethods/v4';

export async function myPaymentMethods() {
  return json(await authFetch(`${PAYMENT_METHODS}/paymentMethod`));
}

/** Vault a card's PRESENTATION data (brand guess, last4, expiry) — never the PAN. */
export async function savePaymentMethod(cardNumber, expiry) {
  const digits = cardNumber.replace(/\s/g, '');
  return json(await authFetch(`${PAYMENT_METHODS}/paymentMethod`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      '@type': 'bankCard',
      details: {
        brand: digits.startsWith('4') ? 'visa' : digits.startsWith('5') ? 'mastercard' : 'card',
        lastFourDigits: digits.slice(-4),
        expiry,
      },
    }),
  }));
}

/** Pay with a vaulted method: the API resolves the token server-side. */
export async function paymentWithSavedMethod(amount, methodId, description) {
  return json(await authFetch(`${PAYMENT}/payment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      description,
      amount: { unit: amount.unit, value: amount.value },
      paymentMethod: { '@type': 'savedPaymentMethod', id: methodId },
    }),
  }));
}


export async function myRecommendations() {
  return json(await authFetch(`${RECOMMENDATION}/recommendation`));
}

export async function myAgreements() {
  return json(await authFetch(`${AGREEMENT}/agreement?limit=100`));
}

export async function myUsage() {
  return json(await authFetch(`${CONSUMPTION}/queryUsageConsumption`));
}

const BILLING = '/tmf-api/customerBillManagement/v4';

export async function myBills() {
  return json(await authFetch(`${BILLING}/customerBill?limit=100`));
}

export async function billRates(billId) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/appliedCustomerBillingRate`));
}

const QUALIFICATION = '/tmf-api/productOfferingQualification/v4';
const APPOINTMENT = '/tmf-api/appointment/v4';

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

/** TMF646 free installer slots — also anonymous. */
export async function searchTimeSlots() {
  try {
    return await json(await publicFetch(`${APPOINTMENT}/searchTimeSlot`, { method: 'POST' }));
  } catch {
    return [];
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

/** Settle a bill with an authorized payment; billing captures it. */
export async function settleBill(billId, paymentRef) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'settled', payment: [paymentRef] }),
  }));
}

const TICKET = '/tmf-api/troubleTicket/v4';

export async function myTickets() {
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100`));
}

export async function raiseTicket(name, description) {
  return json(await authFetch(`${TICKET}/troubleTicket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description, severity: 'minor' }),
  }));
}

/** Customers may close a ticket once an agent has resolved it. */
export async function closeTicket(id) {
  return json(await authFetch(`${TICKET}/troubleTicket/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'closed' }),
  }));
}

const COMMUNICATION = '/tmf-api/communicationManagement/v4';

export async function myNotifications() {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage?limit=100`));
}

export async function markNotificationRead(id) {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'read' }),
  }));
}

// Number porting (keep your number). Fail-soft: if the porting component
// is not deployed, checkout proceeds and the customer gets a new number.
const PORTING = '/tmf-api/numberPortingManagement/v1';

function countryOf(number) {
  const n = (number || '').replace(/\s/g, '');
  if (n.startsWith('+47')) return 'NO';
  if (n.startsWith('+46')) return 'SE';
  if (n.startsWith('+44')) return 'GB';
  if (n.startsWith('+1')) return 'US';
  return 'NO';
}

export async function requestPortIn(partyId, number, currentProvider) {
  const created = await json(await authFetch(`${PORTING}/numberPortingOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      direction: 'portIn', phoneNumber: number, country: countryOf(number),
      otherOperator: currentProvider, relatedParty: [{ id: partyId, role: 'customer' }],
    }),
  }));
  // Dev: the cutover is compressed to the checkout; production waits for the
  // clearinghouse's agreed window and provisioning defers until then.
  if (created.status === 'scheduled') {
    await authFetch(`${PORTING}/numberPortingOrder/${created.id}/complete`, { method: 'POST' })
      .catch(() => {});
  }
  return created;
}

// The running services from the orchestrator's inventory — carries the
// active number (drawn from the pool, or the one the customer ported in).
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
export async function myActiveServices() {
  try {
    return await json(await authFetch(`${SERVICE_INV}/service`));
  } catch { return []; }
}

/**
 * Dynamic pricing preview: ask the policy component what the enabled pricing
 * rules do to this subtotal. Fail-soft — an outage or a guest session simply
 * means no preview, and the bill remains the source of truth.
 */
export async function previewPrice(subtotal, offeringIds) {
  try {
    const res = await authFetch('/tmf-api/policyManagement/v4/price', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ context: { subtotal, offeringIds } }),
    });
    if (!res.ok) return null;
    const result = await res.json();
    return (result.adjustments || []).length ? result : null;
  } catch { return null; }
}
