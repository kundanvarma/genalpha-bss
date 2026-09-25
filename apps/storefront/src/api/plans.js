// plans: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { ADDRESS, ORDERING, PAYMENT, PAYMENT_METHODS, PROMOTION, authFetch, json, publicFetch } from './_http.js';

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
