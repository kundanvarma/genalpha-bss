// copilots: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { INVENTORY, ORDERING, PARTY, TICKET, authFetch, json } from './_http.js';

export async function aiNextBestOffer(partyId) {
  const res = await authFetch('/ai/v1/nextBestOffer', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ partyId }),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}

/* ---- the workspace: universal search lookups, a message to the customer, a ticket by id ---- */

/** A free-text message to the customer's inbox (and email where the tenant sends it) — lands on the timeline too. */
export async function sendMessage(customerId, subject, content) {
  return json(await authFetch('/tmf-api/communicationManagement/v4/communicationMessage', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      subject, content,
      relatedParty: [{ id: customerId, role: 'customer', '@referredType': 'Individual' }],
    }),
  }));
}

export async function getTicket(id) {
  const res = await authFetch(`${TICKET}/troubleTicket/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

export async function orderById(id) {
  const res = await authFetch(`${ORDERING}/productOrder/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

export async function productById(id) {
  const res = await authFetch(`${INVENTORY}/product/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

/** Any identifier the caller may read out — a party id, an order, a ticket, a product — resolved to the customer behind it. */
export async function customerByIdentifier(idLike) {
  const id = idLike.trim();
  const partyOf = (obj) => (obj?.relatedParty || []).find((p) => p.role === 'customer')?.id || (obj?.relatedParty || [])[0]?.id;
  const direct = await authFetch(`${PARTY}/individual/${encodeURIComponent(id)}`);
  if (direct.ok) return { customer: await direct.json(), via: 'customer id' };
  for (const [via, fn] of [['order', orderById], ['ticket', getTicket], ['product', productById]]) {
    const obj = await fn(id).catch(() => null);
    const pid = partyOf(obj);
    if (pid) {
      const c = await authFetch(`${PARTY}/individual/${encodeURIComponent(pid)}`);
      if (c.ok) return { customer: await c.json(), via: `${via} ${id.slice(0, 8)}…` };
    }
  }
  return null;
}

/* ---- the loop: recommendation outcomes, live intent on chat, the after-call note ---- */

/** Tell the ontology what became of a recommendation it made (accepted, dismissed, helpful, unhelpful). */
export async function recommendationOutcome(decisionId, outcome, reason) {
  return json(await authFetch(`/ontology/v1/context/recommendations/${encodeURIComponent(decisionId)}/outcome`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ outcome, ...(reason ? { reason } : {}) }),
  }));
}

export async function aiChatIntent(messages) {
  return json(await authFetch('/ai/v1/chatIntent', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages: messages.slice(-12).map((m) => ({ author: m.author, body: m.body })) }),
  }));
}

export async function aiWrapUp(record) {
  return json(await authFetch('/ai/v1/wrapUp', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(record),
  }));
}
