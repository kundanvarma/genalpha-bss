// bills: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { BILLING, ORDERING, PARTY, PAY, authFetch, json, tokenClaims } from './_http.js';

/** The FAQ library — the customer shelf of the knowledge base. The audience
 *  filter is what keeps the CSR/sales/product-owner cheat-sheets off the
 *  customer's Support page: the shop only ever asks for customer-facing articles. */
export async function searchFaq(q) {
  const params = new URLSearchParams({ audience: 'customer' });
  if (q) params.set('q', q);
  return json(await authFetch(`/tmf-api/knowledgeManagement/v4/article?${params}`));
}

export async function decideApproval(orderId, approve) {
  return json(await authFetch(`${ORDERING}/productOrder/${orderId}/approval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approve }),
  }));
}

/** Credit notes: the numbered documents that reversed (part of) a bill. */
export async function myCreditNotes() {
  const res = await authFetch(`${BILLING}/creditNote`);
  if (!res.ok) return [];
  return res.json();
}

export async function myBills() {
  return json(await authFetch(`${BILLING}/customerBill?limit=100`));
}

export async function billRates(billId) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/appliedCustomerBillingRate`));
}

/** Collections: the customer's OWN case (party-scoped by the backend) —
 * null when the account stands current, or the collections module is absent
 * (fail-soft, like every optional component). */
export async function myCollectionCase() {
  try {
    const cases = await json(await authFetch(`${BILLING}/collectionCase`));
    return Array.isArray(cases) && cases.length ? cases[0] : null;
  } catch { return null; }
}

/** "I will pay by then" — the customer's own promise-to-pay pauses the
 * dunning ladder within the policy allowance. Amount defaults to the full
 * overdue balance on the backend. */
export async function promiseToPay(caseId, days) {
  return json(await authFetch(`${BILLING}/collectionCase/${caseId}/promiseToPay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days: Number(days) } : {}),
  }));
}

/** Settle a bill with an authorized payment; billing captures it. */
/** PAY IN PARTS: split an unpaid bill into monthly installments. */
export async function splitBill(billId, installments) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/installmentPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ installments }),
  }));
}

export async function payInstallment(billId, paymentRef) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/installmentPlan/pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payment: [paymentRef] }),
  }));
}

/** Payday alignment: pick the day (1-28) your billing cycle starts. */
export async function setBillingDay(anchorDay) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/billingCycle`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ anchorDay }),
  }));
}

/** How your bill arrives: paper, einvoice, digital — or default. */
export async function setBillDelivery(preference) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/billDelivery`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ preference }),
  }));
}

/** "This charge is wrong": open a dispute — collection pauses while we look. */
export async function disputeBill(billId, reason) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/dispute`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

export async function settleBill(billId, paymentRef) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'settled', payment: [paymentRef] }),
  }));
}
