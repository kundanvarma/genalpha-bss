// devices: part of the CSR console's TMF client (split from api.js; the barrel re-exports).
import { DEVICE, authFetch, json } from './_http.js';

export async function deviceAgreements(status) {
  return json(await authFetch(`${DEVICE}/deviceAgreement${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

/** One financing agreement by its reference — the search box's device lookup.
 * Null, not an error: the one box probes every object type at once and most
 * probes are meant to miss. */
export async function deviceAgreementById(id) {
  const res = await authFetch(`${DEVICE}/deviceAgreement/${encodeURIComponent(id)}`);
  return res.ok ? res.json() : null;
}

export async function tradeInValuations(status) {
  return json(await authFetch(`${DEVICE}/tradeInValuation${status ? `?status=${encodeURIComponent(status)}` : ''}`));
}

/** The grading verdict: {finalGrade, finalValue, note?} — zero delta settles,
 * anything else moves to `revalued` and waits on the customer. */
export async function gradeTradeIn(id, body) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/grading`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function acceptRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/acceptRevaluation`, { method: 'POST' }));
}

export async function rejectRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/rejectRevaluation`, { method: 'POST' }));
}

export async function residualTable(deviceRef) {
  return json(await authFetch(`${DEVICE}/tradeInResidual${deviceRef ? `?deviceRef=${encodeURIComponent(deviceRef)}` : ''}`));
}

/** Upsert by (deviceRef, ageMonths): {deviceRef, ageMonths, baseValue, currency?}. */
export async function upsertResidual(body) {
  return json(await authFetch(`${DEVICE}/tradeInResidual`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

export async function deleteResidual(id) {
  const res = await authFetch(`${DEVICE}/tradeInResidual/${id}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
}

export async function withdrawalCases() {
  return json(await authFetch(`${DEVICE}/withdrawalCase`));
}
