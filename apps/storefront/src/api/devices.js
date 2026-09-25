// devices: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { DEVICE, INSIGHT, authFetch, json, publicFetch } from './_http.js';
import { consentChoice, visitorId } from './growth.js';

export async function myExperience() {
  if (!consentChoice()?.personalization) return { personalized: false };
  const res = await publicFetch(`${INSIGHT}/experience?visitorId=${visitorId()}`);
  return res.ok ? res.json() : { personalized: false };
}

// ---------------- device commerce (trade-in, financing, withdrawal) ----------------

/** IMEI + guided condition answers → a live estimate off the residual table. */
export async function quoteTradeIn(dto) {
  return json(await authFetch(`${DEVICE}/tradeInValuation`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...dto, channel: 'shop' }),
  }));
}

export async function acceptTradeIn(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/accept`, { method: 'POST' }));
}

/** My trade-ins — quoted, mailed in, graded, settled. Party-scoped server-side. */
export async function myTradeIns() {
  return json(await authFetch(`${DEVICE}/tradeInValuation`));
}

/** The grading came back LOWER — take the revised value, or refuse and get
 * the device back. Money never moves without the customer's word. */
export async function acceptTradeInRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/acceptRevaluation`, { method: 'POST' }));
}

export async function rejectTradeInRevaluation(id) {
  return json(await authFetch(`${DEVICE}/tradeInValuation/${id}/rejectRevaluation`, { method: 'POST' }));
}

/** The checkout chooser face: per-model monthly + TOTAL cost, before signing. */
export async function financingQuote(principal, termMonths, financingModel) {
  return json(await authFetch(`${DEVICE}/financingQuote`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ principal, termMonths, financingModel }),
  }));
}

/** Sign the device financing agreement (instalments / pay-later) post-order. */
export async function createDeviceAgreement(dto) {
  return json(await authFetch(`${DEVICE}/deviceAgreement`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(dto),
  }));
}

export async function myDeviceAgreements() {
  return json(await authFetch(`${DEVICE}/deviceAgreement`));
}

export async function deviceUpgradeEligibility(agreementId) {
  return json(await authFetch(`${DEVICE}/deviceAgreement/${agreementId}/upgradeEligibility`));
}

/** The 14-day withdrawal (angrerett): unconditional inside the window. */
export async function openDeviceWithdrawal(agreementId) {
  return json(await authFetch(`${DEVICE}/withdrawalCase`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ agreementId }),
  }));
}

// ---------------- usage policy (pool, spend meters, auto top-up) ----------------
