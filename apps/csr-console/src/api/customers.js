// customers: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { APPOINTMENT, BILLING, INTERACTION, INVENTORY, ORDERING, PARTY, PROBLEM, TICKET, authFetch, json } from './_http.js';

/** TMF656 open outages — fail-soft when assurance is not deployed. */
export async function openProblems() {
  try {
    return await json(await authFetch(`${PROBLEM}/serviceProblem?status=open`));
  } catch {
    return [];
  }
}

export async function searchCustomers(q) {
  const filter = q ? `&q=${encodeURIComponent(q)}` : '';
  return json(await authFetch(`${PARTY}/individual?limit=50${filter}`));
}

/** A typed MSISDN resolves in the tenant's own number pool (agents are
 * unscoped there); empty when nobody holds it. */
export async function customerByNumber(number) {
  const res = await authFetch(`/tmf-api/serviceInventory/v4/numberOwner?number=${encodeURIComponent(number)}`);
  if (!res.ok) return null;
  const owner = await res.json();
  const customer = await authFetch(`${PARTY}/individual/${owner.partyId}`);
  return customer.ok ? customer.json() : null;
}

export async function getCustomer(id) {
  return json(await authFetch(`${PARTY}/individual/${id}`));
}

/** Re-verify a customer's address against the national registry (freg F-P4).
 * Back-office callers may vouch for the customer by id — the match event is
 * attributed to THEM, so the CDP re-homes movers. Returns the registryMatch
 * part, or null when the address fails validation outright. */
export async function verifyPartyAddress(customer, address) {
  const name = [customer.givenName, customer.familyName].filter(Boolean).join(' ');
  const result = await json(await authFetch('/tmf-api/geographicAddressManagement/v4/geographicAddressValidation', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ submittedGeographicAddress: address, relatedParty: { id: customer.id, name } }),
  }));
  return result.registryMatch || null;
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

/** One PAGE of the timeline (newest first) + the total, so the 360 shows
 * a handful and fetches more on demand instead of hauling the history. */
export async function interactionsPage(customerId, offset = 0, limit = 5) {
  const res = await authFetch(
    `${INTERACTION}/partyInteraction?offset=${offset}&limit=${limit}&relatedPartyId=${customerId}`);
  if (!res.ok) throw new Error(`interactions: ${res.status}`);
  return {
    items: await res.json(),
    total: Number(res.headers.get('X-Total-Count') || 0),
  };
}

export async function logInteraction(body) {
  return json(await authFetch(`${INTERACTION}/partyInteraction`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}
