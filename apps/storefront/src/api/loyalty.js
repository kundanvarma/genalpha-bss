// loyalty: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { AGREEMENT, CAMPAIGN, CONSUMPTION, LOYALTY, ORDERING, PARTY, RECOMMENDATION, authFetch, json, publicFetch } from './_http.js';

export async function myRecommendations() {
  return json(await authFetch(`${RECOMMENDATION}/recommendation`));
}

/** The individualized shop: MY rail — ranked, captioned, churn-aware.
 * Self-scoped server-side (party = my token), fail-soft to nothing. */
export async function forYou() {
  const res = await authFetch('/ai/v1/forYou');
  return res.ok ? res.json() : null;
}

/** "Customers who bought this also bought" — item-to-item affinity,
 * PUBLIC (guests see it on the product page), fail-soft to []. */
export async function alsoBought(offeringId) {
  const res = await publicFetch(`${RECOMMENDATION}/affinity?forOfferingId=${offeringId}`);
  return res.ok ? res.json() : [];
}

export async function myAgreements() {
  return json(await authFetch(`${AGREEMENT}/agreement?limit=100`));
}

export async function myUsage() {
  return json(await authFetch(`${CONSUMPTION}/queryUsageConsumption`));
}

/** G1 — my referral code (minted on first ask) + its tally. */
export async function myReferral() {
  return json(await authFetch(`${CAMPAIGN}/referral/myCode`));
}

/** Redeem a friend's code — pays both sides on your first completed order. */
export async function redeemReferral(code) {
  return json(await authFetch(`${CAMPAIGN}/referral/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }) }));
}

/** Loyalty: opt-in membership, my balance, and points→GB redemption. */
export async function myLoyalty() {
  const res = await authFetch(`${LOYALTY}/loyaltyProgramMember/me`);
  if (res.status === 404) return null; // not a member (or no program)
  return json(res);
}

export async function enrollLoyalty() {
  return json(await authFetch(`${LOYALTY}/loyaltyProgramMember`, { method: 'POST' }));
}

export async function loyaltyProgram() {
  const res = await authFetch(`${LOYALTY}/loyaltyProgram`);
  if (res.status === 404) return null;
  return json(res);
}

export async function redeemLoyaltyVoucher() {
  return json(await authFetch(`${LOYALTY}/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'voucher' }),
  }));
}

export async function redeemLoyaltyData(gb) {
  return json(await authFetch(`${LOYALTY}/redeem`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'data', gb }),
  }));
}

export async function giftData(receiver, amount) {
  return json(await authFetch('/tmf-api/usageManagement/v4/gift', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...receiver, amount }),
  }));
}

/** Owner or admin: the family's monthly top-up budget for a member (EUR). */
export async function setAllowance(memberId, monthlyValue) {
  return json(await authFetch(`${PARTY}/individual/${memberId}/allowance`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ monthlyValue }),
  }));
}

/** Held ask-to-buy orders across my family — the hub's approvals inbox. */
export async function familyApprovals() {
  return json(await authFetch(`${ORDERING}/productOrder/familyApprovals`));
}

/** A CHILD's usage meters, through the household link (payer/admin only —
 * an adult member's usage stays their own even when the family pays). */
export async function memberUsage(memberId) {
  return json(await authFetch(`${CONSUMPTION}/queryUsageConsumption?relatedPartyId=${encodeURIComponent(memberId)}`));
}
