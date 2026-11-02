// credit: the customer's side of a credit check (#422) — the notice before checkout,
// why an order's check came out as it did, and asking a person to look again.
// Ordering decides all of it; the shop only asks and shows.
import { ORDERING, authFetch, json } from './_http.js';

/** Does this shop check credit, with whom, using what — shown before a credit-checked order. */
export async function creditNotice() {
  return json(await authFetch(`${ORDERING}/creditDecision/notice`));
}

/** Why the credit check on one of MY orders came out as it did (404 for anyone else's). */
export async function orderCreditDecision(orderId) {
  return json(await authFetch(`${ORDERING}/productOrder/${encodeURIComponent(orderId)}/creditDecision`));
}

/** Ask a person to review the decision; one request per order, a second click answers the same receipt. */
export async function askCreditReview(orderId, note) {
  return json(await authFetch(`${ORDERING}/productOrder/${encodeURIComponent(orderId)}/creditDecision/review`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(note && note.trim() ? { note: note.trim() } : {}),
  }));
}
