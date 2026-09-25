// services: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { INVENTORY, PARTY, SERVICE_INV, authFetch, json } from './_http.js';

/** A family member's products, through the household link: inventory verifies
 * the caller is their payer or a family admin, live at the party source. */
export async function memberProducts(memberId) {
  return json(await authFetch(`${INVENTORY}/product?relatedPartyId=${encodeURIComponent(memberId)}&limit=100`));
}

/** Owner-only: promote a member to family admin, or demote back. */
export async function setFamilyRole(memberId, role) {
  return json(await authFetch(`${PARTY}/individual/${memberId}/householdRole`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  }));
}

/** The SIM behind a line; null when none is on file (pre-SIM activations). */
export async function mySim(serviceId, reveal = false) {
  const res = await authFetch(`${SERVICE_INV}/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`);
  if (!res.ok) return null;
  return res.json();
}

/** "It feels slow": triage before ticket — outage? out of data? paused? */
export async function diagnoseMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/diagnose`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Cancel the subscription — the number is released. Keeping it means
 * your NEW operator ports it first; cancel after, never before. */
/** The router on my broadband line, as the operator's equipment system sees it (null = none / not answering). */
export async function myRouter(serviceId) {
  const res = await authFetch(`${SERVICE_INV}/service/${serviceId}/cpe`);
  return res.ok ? res.json() : null;
}

export async function restartMyRouter(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/cpe/restart`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function cancelMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/terminate`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: 'cancelled by customer' }),
  }));
}

/** Going away? Pause the line — your number and SIM stay yours, and the
 * hold lifts itself on the day you pick. */
export async function pauseMyService(serviceId, days) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/suspend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days } : {}),
  }));
}

export async function resumeMyService(serviceId) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/resume`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Lost your SIM? Block the old card and get a fresh one on the same
 * number — you will be notified, and the new PUK is revealable as usual. */
export async function replaceMySim(serviceId, reason) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/sim/replace`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** OTA PIN change — pushed to the card via the operator's SIM platform. */
export async function resetSimPin(serviceId, newPin) {
  return json(await authFetch(`${SERVICE_INV}/service/${serviceId}/sim/resetPin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPin }),
  }));
}
