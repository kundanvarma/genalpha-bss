/*
 * Agent-side TMF client, same-origin through the gateway. Agents are
 * org-scoped on tickets and interactions by the backends; customer lookups
 * use the relatedPartyId filters the services expose to staff roles.
 */
import { authFetch } from './auth.js';

const CATALOG = '/tmf-api/productCatalogManagement/v4';
const ORDERING = '/tmf-api/productOrderingManagement/v4';
const INVENTORY = '/tmf-api/productInventory/v4';
const PARTY = '/tmf-api/party/v4';
const STOCK = '/tmf-api/productStockManagement/v4';
const BILLING = '/tmf-api/customerBillManagement/v4';
const APPOINTMENT = '/tmf-api/appointment/v4';
const TICKET = '/tmf-api/troubleTicket/v4';
const PROBLEM = '/tmf-api/serviceProblemManagement/v4';

/** TMF656 open outages — fail-soft when assurance is not deployed. */
export async function openProblems() {
  try {
    return await json(await authFetch(`${PROBLEM}/serviceProblem?status=open`));
  } catch {
    return [];
  }
}
const CART = '/tmf-api/shoppingCart/v4';
const INTERACTION = '/tmf-api/partyInteraction/v4';

async function json(res) {
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function searchCustomers(familyName) {
  const filter = familyName ? `&familyName=${encodeURIComponent(familyName)}` : '';
  return json(await authFetch(`${PARTY}/individual?limit=50${filter}`));
}

export async function getCustomer(id) {
  return json(await authFetch(`${PARTY}/individual/${id}`));
}

export async function ordersOf(customerId) {
  return json(await authFetch(`${ORDERING}/productOrder?limit=100&relatedPartyId=${customerId}`));
}

export async function patchOrder(id, patch) {
  return json(await authFetch(`${ORDERING}/productOrder/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

export async function productsOf(customerId) {
  return json(await authFetch(`${INVENTORY}/product?limit=100&relatedPartyId=${customerId}`));
}

export async function billsOf(customerId) {
  return json(await authFetch(`${BILLING}/customerBill?limit=100&relatedPartyId=${customerId}`));
}

export async function appointmentsOf(customerId) {
  return json(await authFetch(`${APPOINTMENT}/appointment?limit=100&relatedPartyId=${customerId}`));
}

export async function ticketsOf(customerId) {
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100&relatedPartyId=${customerId}`));
}

export async function orgTickets(status) {
  const filter = status ? `&status=${status}` : '';
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100${filter}`));
}

export async function createTicket(body) {
  return json(await authFetch(`${TICKET}/troubleTicket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function workTicket(id, patch) {
  return json(await authFetch(`${TICKET}/troubleTicket/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

export async function interactionsOf(customerId) {
  return json(await authFetch(`${INTERACTION}/partyInteraction?limit=100&relatedPartyId=${customerId}`));
}

export async function logInteraction(body) {
  return json(await authFetch(`${INTERACTION}/partyInteraction`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function cartsOf(customerId) {
  return json(await authFetch(`${CART}/shoppingCart?limit=10&status=active&relatedPartyId=${customerId}`));
}

const USAGE = '/tmf-api/usageConsumption/v4';
const AGREEMENT = '/tmf-api/agreementManagement/v4';
const SERVICE_INV = '/tmf-api/serviceInventory/v4';
const PROMO = '/tmf-api/promotionManagement/v4';
const VAULT = '/tmf-api/paymentMethods/v4';
const RECOMMEND = '/tmf-api/recommendationManagement/v4';

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
