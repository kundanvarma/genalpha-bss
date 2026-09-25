// lines: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { authFetch, json } from './_http.js';

/** Next best offer with the WHY — TMF680 candidates, the model reasons. */
/** The SIM behind a service — the PUK only with reveal (read it to the
 * caller AFTER verifying identity; the disclosure is logged). */
export async function simOf(serviceId, reveal = false) {
  const res = await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim${reveal ? '?reveal=true' : ''}`);
  if (!res.ok) return null;
  return res.json();
}

/** OTA PIN reset through the SIM-platform seam; the owner is notified. */
export async function resetSimPin(serviceId, newPin) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim/resetPin`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ newPin }),
  }));
}

/** Lost/stolen/damaged/upgrade: block the old card at the network and
 * mint a fresh one against the same service — the number never moves. */
export async function replaceSim(serviceId, reason) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/sim/replace`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  }));
}

/** MSISDN change: old number quarantined, new one drawn onto the SAME
 * service — SIM, usage and billing untouched; the customer is warned. */
export async function changeNumber(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/changeNumber`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Vacation hold: pause the line (charging pauses, number and SIM stay);
 * the hold lifts itself at the agreed date, or on request. */
/** The router on a broadband line, as the ACS sees it (null = none / not answering). */
export async function routerOf(serviceId) {
  const res = await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/cpe`);
  return res.ok ? res.json() : null;
}

export async function restartRouter(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/cpe/restart`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function suspendService(serviceId, days) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/suspend`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(days ? { days } : {}),
  }));
}

export async function resumeService(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/resume`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** Triage before ticket: outage on their path? out of data? paused? */
export async function diagnoseService(serviceId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/diagnose`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

/** TRANSFER a line to another person — number, SIM and usage move with
 * it; the payer stamp stays (a company-paid line keeps its payer). */
export async function transferService(serviceId, toPartyId) {
  return json(await authFetch(`/tmf-api/serviceInventory/v4/service/${serviceId}/transfer`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toPartyId }),
  }));
}
