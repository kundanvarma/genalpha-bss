// billing: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { BILLING, PARTY, authFetch, json } from './_http.js';

export async function findCustomerByEmail(q) {
  return json(await authFetch(`/tmf-api/party/v4/individual?q=${encodeURIComponent(q)}&limit=5`));
}

/** "This charge is wrong": open a dispute for the caller. */
/** The reversing document — staff-only (billing:admin); reason REQUIRED. */
export async function issueCreditNote(billId, amount, reason) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/creditNote`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...(amount ? { amount } : {}), reason }),
  }));
}

export async function creditNotesOf(billId) {
  const res = await authFetch(`${BILLING}/creditNote?billId=${billId}`);
  return res.ok ? res.json() : [];
}

export async function disputeBill(billId, reason) {
  return json(await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${billId}/dispute`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** The bill as the customer sees it — a PDF, opened in a new tab. */
export async function openBillPdf(billId) {
  const res = await authFetch(`${BILLING}/customerBill/${billId}/document.pdf`);
  if (!res.ok) throw new Error('could not fetch the bill PDF');
  window.open(URL.createObjectURL(await res.blob()), '_blank');
}

/** "Send me a copy of my invoice" — emails the PDF to the address on
 * file (never one dictated over the phone). */
export async function resendBill(billId) {
  return json(await authFetch(`${BILLING}/customerBill/${billId}/resend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
  }));
}

/** How the customer's bill arrives (paper / einvoice / digital) — set
 * on their behalf, with their say-so on the line. */
export async function setBillDeliveryFor(customerId, preference) {
  return json(await authFetch(`${PARTY}/individual/${customerId}/billDelivery`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ preference }),
  }));
}

/* ---------------- Collections desk (billing) ----------------
 * Cases are made by the sweeper, never POSTed. Reads ride billing:read;
 * holds, release, write-off, the sweep trigger and the policy editor are
 * billing:admin; the promise-to-pay entry is billing:write (staff carry it). */
export async function collectionCases(state) {
  return json(await authFetch(`${BILLING}/collectionCase${state ? `?state=${encodeURIComponent(state)}` : ''}`));
}

export async function collectionCaseById(id) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}`));
}

/** {days?, amount?} — amount defaults to the full overdue balance. */
export async function casePromiseToPay(id, body) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/promiseToPay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body || {}),
  }));
}

/** {type: 'dispute', amount} freezes ONLY the disputed amount;
 *  {type: 'hardship'} is the manual full hold. */
export async function caseHold(id, body) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/hold`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function caseRelease(id, type) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/release`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type }),
  }));
}

/** The ladder's end, human decision: reason REQUIRED (the auditors will ask). */
export async function caseWriteOff(id, reason) {
  return json(await authFetch(`${BILLING}/collectionCase/${id}/writeOff`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** Walk this tenant's ladder now instead of waiting for the scheduled tick. */
export async function runCollectionSweep() {
  return json(await authFetch(`${BILLING}/collectionSweep`, { method: 'POST' }));
}

export async function dunningPolicies() {
  return json(await authFetch(`${BILLING}/dunningPolicy`));
}

export async function patchDunningPolicy(id, patch) {
  return json(await authFetch(`${BILLING}/dunningPolicy/${id}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

/** Hardship/retention: split an unpaid bill into monthly installments. */
export async function splitBill(billId, installments) {
  return json(await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${billId}/installmentPlan`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ installments }),
  }));
}
