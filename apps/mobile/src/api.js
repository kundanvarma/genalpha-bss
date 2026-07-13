/*
 * TMF Open API client — same-origin through the gateway, party scoping
 * enforced server-side, every optional component fail-soft so the app
 * composes to whatever the operator deployed.
 */
import { getToken, tokenClaims } from './auth.js';

async function call(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      Authorization: 'Bearer ' + getToken(),
      ...(options.headers || {}),
    },
  });
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

const soft = (promise, fallback) => promise.catch(() => fallback);

export function ensureParty() {
  const claims = tokenClaims();
  return call('/tmf-api/party/v4/individual', {
    method: 'POST',
    body: JSON.stringify({
      givenName: claims.given_name || claims.preferred_username || 'Customer',
      familyName: claims.family_name || '—',
    }),
  });
}

export const myProducts = () => soft(call('/tmf-api/productInventory/v4/product?limit=100'), []);
export const myServices = () => soft(call('/tmf-api/serviceInventory/v4/service?relatedPartyId='
  + tokenClaims().sub), []);
export const myUsage = () => soft(call('/tmf-api/usageConsumption/v4/queryUsageConsumption')
  .then((r) => r.bucket || []), []);
export const myBills = () => soft(call('/tmf-api/customerBillManagement/v4/customerBill?limit=20'), []);
export const billRates = (id) => soft(call(`/tmf-api/customerBillManagement/v4/customerBill/${id}/appliedCustomerBillingRate`), []);
export const myAgreements = () => soft(call('/tmf-api/agreementManagement/v4/agreement?limit=20'), []);
export const myMethods = () => soft(call('/tmf-api/paymentMethods/v4/paymentMethod'), []);
export const myNotifications = () => soft(call('/tmf-api/communicationManagement/v4/communicationMessage?limit=30'), []);
export const myTickets = () => soft(call('/tmf-api/troubleTicket/v4/troubleTicket?limit=30'), []);
export const myRecommendations = () => soft(call('/tmf-api/recommendationManagement/v4/recommendation')
  .then((r) => r[0]?.recommendationItem || []), []);
export const openProblems = () => soft(call('/tmf-api/serviceProblemManagement/v4/serviceProblem?status=open'), []);
export const listOfferings = () => soft(call('/tmf-api/productCatalogManagement/v4/productOffering?limit=100'), []);
export const priceIndex = () => soft(call('/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=100')
  .then((ps) => Object.fromEntries(ps.map((p) => [p.id, p]))), {});
export const mySim = (serviceId, reveal = false) =>
  soft(call(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`), null);
export const resetSimPin = (serviceId, newPin) =>
  call(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim/resetPin`, {
    method: 'POST', body: JSON.stringify({ newPin }) });
/** One-tap purchase for simple digital items (data top-ups). */
export const quickOrder = (offering) =>
  call('/tmf-api/productOrderingManagement/v4/productOrder', {
    method: 'POST',
    body: JSON.stringify({ productOrderItem: [{ action: 'add',
      productOffering: { id: offering.id, name: offering.name } }] }),
  });

export async function offerings() {
  const res = await fetch('/tmf-api/productCatalogManagement/v4/productOffering?limit=100&lifecycleStatus=Active');
  return res.ok ? res.json() : [];
}

export async function prices() {
  const res = await fetch('/tmf-api/productCatalogManagement/v4/productOfferingPrice?limit=100');
  if (!res.ok) return {};
  const list = await res.json();
  return Object.fromEntries(list.map((p) => [p.id, p]));
}

export function orderOffering(offering) {
  return call('/tmf-api/productOrderingManagement/v4/productOrder', {
    method: 'POST',
    body: JSON.stringify({
      description: offering.name,
      productOrderItem: [{
        id: '1', action: 'add', quantity: 1,
        productOffering: { id: offering.id, name: offering.name, '@referredType': 'ProductOffering' },
      }],
    }),
  });
}

export const myOrders = () => soft(call('/tmf-api/productOrderingManagement/v4/productOrder?limit=20'), []);

export function payBill(bill, methodId) {
  return call('/tmf-api/paymentManagement/v4/payment', {
    method: 'POST',
    body: JSON.stringify({
      description: `Bill ${bill.billNo}`,
      amount: bill.amountDue,
      paymentMethod: { '@type': 'savedPaymentMethod', id: methodId },
    }),
  }).then((payment) => call(`/tmf-api/customerBillManagement/v4/customerBill/${bill.id}`, {
    method: 'PATCH',
    body: JSON.stringify({
      state: 'settled',
      payment: [{ id: payment.id, href: payment.href, '@referredType': 'Payment' }],
    }),
  }));
}

export function createTicket(name, description) {
  return call('/tmf-api/troubleTicket/v4/troubleTicket', {
    method: 'POST',
    body: JSON.stringify({ name, description, severity: 'minor',
      channel: { name: 'app' } }),
  });
}
