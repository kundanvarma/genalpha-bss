/*
 * TMF Open API client — same-origin through the gateway, party scoping
 * enforced server-side, every optional component fail-soft so the app
 * composes to whatever the operator deployed.
 */
import { getToken, tokenClaims } from './auth.js';
import { API_BASE } from './config.js';

async function call(path, options = {}) {
  const res = await fetch(API_BASE + path, {
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
// the bill as a document — fetched with the token, opened as a blob (web)
// or handed to the OS (native)
export async function openBillPdf(billId) {
  const res = await fetch(API_BASE + `/tmf-api/customerBillManagement/v4/customerBill/${billId}/document.pdf`, {
    headers: { Authorization: 'Bearer ' + getToken() },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  if (typeof window !== 'undefined' && window.open) {
    window.open(URL.createObjectURL(await res.blob()), '_blank');
  }
}
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
/** Plan change (TMF622 modify): same line, same number — only the plan swaps. */
export const changePlan = (productId, serviceId, offering) =>
  call('/tmf-api/productOrderingManagement/v4/productOrder', {
    method: 'POST',
    body: JSON.stringify({ productOrderItem: [{ action: 'modify',
      product: { id: productId, ...(serviceId ? { realizingService: [{ id: serviceId }] } : {}) },
      productOffering: { id: offering.id, name: offering.name } }] }),
  });

/** The signed-in customer's party — carries the organization for B2B members. */
export const myParty = () => soft(call('/tmf-api/party/v4/individual/' + tokenClaims().sub), null);
export const orgName = (id) => soft(call('/tmf-api/party/v4/organization/' + id)
  .then((o) => o.name), null);

// ---------------- household billing (one person, many payers) ----------------

export const myHousehold = () => soft(
  call('/tmf-api/party/v4/individual/' + tokenClaims().sub + '/household'), null);

export const acceptDependent = (dependentId) =>
  call(`/tmf-api/party/v4/individual/${dependentId}/householdPayer/accept`, {
    method: 'POST', body: '{}' });

export const endHouseholdLink = (dependentId) =>
  call(`/tmf-api/party/v4/individual/${dependentId}/householdPayer`, { method: 'DELETE' });

/** A family member's household-funded products — allowed for the payer and
 * for family admins; inventory verifies the link live. */
export const memberProducts = (memberId) => soft(
  call('/tmf-api/productInventory/v4/product?relatedPartyId=' + encodeURIComponent(memberId) + '&limit=50'), []);

export const orderForDependent = (offering, dependentId) =>
  call('/tmf-api/productOrderingManagement/v4/productOrder', {
    method: 'POST',
    body: JSON.stringify({ productOrderItem: [{ action: 'add',
      productOffering: { id: offering.id, name: offering.name } }],
      relatedParty: [{ id: dependentId, role: 'customer' }] }),
  });

/** Child account: mint the kid's login + party, household link born active. */
export const addFamilyMember = async (givenName, familyName, email) => {
  const login = await call('/tmf-api/rolesAndPermissionsManagement/v4/user', {
    method: 'POST',
    body: JSON.stringify({ email, givenName, familyName }),
  });
  await call(`/tmf-api/party/v4/individual/${tokenClaims().sub}/dependents`, {
    method: 'POST',
    body: JSON.stringify({ id: login.id, givenName, familyName,
      contactMedium: [{ mediumType: 'email', characteristic: { emailAddress: email } }] }),
  });
  return { email, temporaryPassword: login.temporaryPassword };
};

/** Owner or admin: the family's monthly top-up budget for a member (EUR). */
export const setAllowance = (memberId, monthlyValue) =>
  call(`/tmf-api/party/v4/individual/${memberId}/allowance`, {
    method: 'POST', body: JSON.stringify({ monthlyValue }) });

/** Held ask-to-buy orders across my family — the approvals inbox. */
export const familyApprovals = () => soft(
  call('/tmf-api/productOrderingManagement/v4/productOrder/familyApprovals'), []);

export const decideApproval = (orderId, approve) =>
  call(`/tmf-api/productOrderingManagement/v4/productOrder/${orderId}/approval`, {
    method: 'POST', body: JSON.stringify({ approve }) });

/** Gift remaining GB — to a family member by id or any number in reach. */
export const giftData = (receiver, amount) =>
  call('/tmf-api/usageManagement/v4/gift', {
    method: 'POST', body: JSON.stringify({ ...receiver, amount }) });

/** One-tap purchase for simple digital items (data top-ups). */
export const quickOrder = (offering) =>
  call('/tmf-api/productOrderingManagement/v4/productOrder', {
    method: 'POST',
    body: JSON.stringify({ productOrderItem: [{ action: 'add',
      productOffering: { id: offering.id, name: offering.name } }] }),
  });

export async function offerings() {
  const res = await fetch(API_BASE + '/tmf-api/productCatalogManagement/v4/productOffering?limit=100&lifecycleStatus=Active');
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
