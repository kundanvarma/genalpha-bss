// controls: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { ORDERING, PARTY, USAGE_POLICY, authFetch, json } from './_http.js';

export async function spendPoliciesOf(partyId) {
  try {
    return await json(await authFetch(`${USAGE_POLICY}/spendPolicy?partyId=${encodeURIComponent(partyId)}`));
  } catch { return []; }
}

export async function patchSpendPolicy(partyId, meterType, body) {
  return json(await authFetch(`${USAGE_POLICY}/spendPolicy/${meterType}?partyId=${encodeURIComponent(partyId)}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

/** Pools the party owns or draws from — staff list is tenant-wide, so
 * filter to this customer here. */
export async function poolsOf(partyId) {
  try {
    const all = await json(await authFetch(`${USAGE_POLICY}/allowancePool`));
    return all.filter((p) => p.ownerPartyId === partyId
      || (p.member || []).some((m) => m.partyId === partyId));
  } catch { return []; }
}

export async function autoTopupOf(partyId) {
  try {
    return await json(await authFetch(`${USAGE_POLICY}/autoTopupPolicy?partyId=${encodeURIComponent(partyId)}`));
  } catch { return null; }
}

/* ---------------- Credit decisions (ordering credit seam) ----------------
 * The stored decisions only — there is never a report to show. */
export async function creditDecisionsOf(partyId) {
  try {
    return await json(await authFetch(`${ORDERING}/creditDecision?relatedPartyId=${encodeURIComponent(partyId)}`));
  } catch { return []; }
}

/* ---------------- Registry & directory tools (party-account) ----------------
 * Back-office: link a party to its registry person, poke the sync worker,
 * curate directory exposure, and run/inspect the directory delta export. */
export async function linkRegistryPerson(partyId, personRef) {
  return json(await authFetch(`${PARTY}/individual/${partyId}/registryLink`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ personRef }),
  }));
}

export async function runRegistrySync() {
  return json(await authFetch(`${PARTY}/registrySync/run`, { method: 'POST' }));
}

export async function directorySettingsOf(partyId) {
  try {
    return await json(await authFetch(`${PARTY}/individual/${partyId}/directorySetting`));
  } catch { return []; }
}

/** Upsert by (party, serviceRef): {serviceRef?, exposure?, secretNumber?}. */
export async function saveDirectorySetting(partyId, body) {
  return json(await authFetch(`${PARTY}/individual/${partyId}/directorySetting`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function runDirectoryExport() {
  return json(await authFetch(`${PARTY}/directoryExport/run`, { method: 'POST' }));
}

export async function directoryExportRun(runId) {
  return json(await authFetch(`${PARTY}/directoryExport/${encodeURIComponent(runId)}`));
}
