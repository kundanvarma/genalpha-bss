// controls: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { INSIGHT, PARTY, USAGE_MGMT, authFetch, json, tokenClaims } from './_http.js';
import { consentChoice, visitorId } from './growth.js';

/** The household data pools I own or draw — members ride along for managers. */
export async function myAllowancePools() {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool`));
}

export async function createAllowancePool(poolGB, name = null) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ poolGB, ...(name ? { name } : {}) }),
  }));
}

export async function addPoolMember(poolId, partyId, softLimitGB = null, hardLimitGB = null) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ partyId,
      ...(softLimitGB != null ? { softLimitGB } : {}),
      ...(hardLimitGB != null ? { hardLimitGB } : {}) }),
  }));
}

export async function patchPoolMember(poolId, partyId, patch) {
  return json(await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member/${partyId}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  }));
}

export async function removePoolMember(poolId, partyId) {
  const res = await authFetch(`${USAGE_MGMT}/allowancePool/${poolId}/member/${partyId}`, {
    method: 'DELETE' });
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}));
    throw new Error(problem.message || `HTTP ${res.status}`);
  }
}

/** My three spend-meter faces: spend cap, content services, roaming limit. */
export async function mySpendPolicy() {
  return json(await authFetch(`${USAGE_MGMT}/spendPolicy`));
}

export async function patchSpendMeter(meterType, dto) {
  return json(await authFetch(`${USAGE_MGMT}/spendPolicy/${meterType}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

/** The audited "keep me roaming" election — service past the limit only on
 * the customer's explicit request (EU 2022/612). */
export async function roamingContinue() {
  return json(await authFetch(`${USAGE_MGMT}/roamingLimit/continue`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  }));
}

export async function myAutoTopup() {
  return json(await authFetch(`${USAGE_MGMT}/autoTopupPolicy`));
}

/** PUT semantics — enabling REQUIRES consent:true in the same request. */
export async function setAutoTopup(dto) {
  return json(await authFetch(`${USAGE_MGMT}/autoTopupPolicy`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

// ---------------- directory privacy (number-directory exposure) ----------------

/** My directory exposure choices (full / partial / reserved + secret number). */
export async function myDirectorySettings() {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/directorySetting`));
}

export async function setDirectorySetting(dto) {
  return json(await authFetch(`${PARTY}/individual/${tokenClaims().sub}/directorySetting`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

/** On login: this browser's profile belongs to this customer now. */
export function stitchVisitor() {
  if (!consentChoice()?.personalization) return;
  authFetch(`${INSIGHT}/stitch`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId() }),
  }).catch(() => {});
}

/** The customer's own reading of their situation from the operational ontology — the same context the care
 * desk's Assist reads, with the customer's token, signed as the registered shop-home agent. Fail-soft. */
/** The customer's own verdict on a recommendation — accepted, deferred ("maybe later") or rejected ("not
 * interested") — into the decision log the ranking learns from. Back/close is never sent: it is not a verdict. */
export async function recommendationOutcome(decisionId, outcome, reason) {
  if (!decisionId) return null;
  const res = await authFetch(`/ontology/v1/context/recommendations/${encodeURIComponent(decisionId)}/outcome`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-GenAlpha-Agent': 'shop-home' },
    body: JSON.stringify(reason ? { outcome, reason } : { outcome }),
  });
  return res.ok ? res.json() : null;
}

export async function myHomeContext(partyId) {
  const res = await authFetch(`/ontology/v1/context/customer/${encodeURIComponent(partyId)}/recommendations`, {
    headers: { 'X-GenAlpha-Agent': 'shop-home' },
  });
  return res.ok ? res.json() : null;
}
