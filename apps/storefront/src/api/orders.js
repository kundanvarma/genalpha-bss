// orders: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { INVENTORY, ORDERING, PAY, PAYMENT, PROCESS, SHIPPING, authFetch, json, publicFetch } from './_http.js';

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
    ...productFor(line.characteristics || null, line.physical),
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

export async function myShipments() {
  return json(await authFetch(`${SHIPPING}/shippingOrder`));
}

export async function myOrderJourney(orderId) {
  try {
    const flows = await json(await authFetch(`${PROCESS}/processFlow?productOrderId=${orderId}`));
    if (!flows.length) return null;
    return await json(await authFetch(`${PROCESS}/processFlow/${flows[0].id}`));
  } catch { return null; }
}

/** The payment methods this tenant offers (card + redirect/BNPL) — anonymous. */
export async function paymentMethods() {
  try {
    return await json(await publicFetch(`${PAY}/payment/methods`));
  } catch {
    return [{ method: 'card', redirect: false }];
  }
}

/** Open a redirect/BNPL session (Klarna) — returns where to send the customer. */
export async function createPaymentSession(dto) {
  return json(await authFetch(`${PAY}/payment/session`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dto) }));
}

/** Confirm a redirect session on return → the authorized payment (idempotent). */
export async function confirmPayment(provider, sessionId) {
  return json(await authFetch(`${PAY}/payment/confirm`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, sessionId }) }));
}

/** The shopper's delivery menu for a postcode (home + pickup points) — anonymous,
 * so guest checkout can offer it. Fail-soft to [] (outage / no carrier menu). */
export async function deliveryOptions(postCode) {
  if (!postCode) return [];
  try {
    return await json(await publicFetch(`${SHIPPING}/carrier/deliveryOptions?postcode=${encodeURIComponent(postCode)}`));
  } catch {
    return [];
  }
}
