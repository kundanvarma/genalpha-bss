// care: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { COMMUNICATION, TICKET, authFetch, json } from './_http.js';

export async function myTickets() {
  return json(await authFetch(`${TICKET}/troubleTicket?limit=100`));
}

export async function raiseTicket(name, description) {
  return json(await authFetch(`${TICKET}/troubleTicket`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, description, severity: 'minor', ticketType: 'support' }),
  }));
}

/** Customers may close a ticket once an agent has resolved it. */
export async function closeTicket(id) {
  return json(await authFetch(`${TICKET}/troubleTicket/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'closed' }),
  }));
}

export async function myNotifications() {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage?limit=100`));
}

export async function markNotificationRead(id) {
  return json(await authFetch(`${COMMUNICATION}/communicationMessage/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 'read' }),
  }));
}
