// growth: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { CONSENT_KEY, INSIGHT, VISITOR_KEY, authFetch, json, publicFetch } from './_http.js';

/**
 * Dynamic pricing preview: ask the policy component what the enabled pricing
 * rules do to this subtotal. Fail-soft — an outage or a guest session simply
 * means no preview, and the bill remains the source of truth.
 */
export async function previewPrice(subtotal, offeringIds, characteristicValues = []) {
  try {
    const res = await authFetch('/tmf-api/policyManagement/v4/price', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      // characteristicValues ("color:Icy Blue") let marketing run a
      // campaign on a colour — a pricing rule conditioned on the pick
      body: JSON.stringify({ context: { subtotal, offeringIds, characteristicValues, channel: 'shop' } }),
    });
    if (!res.ok) return null;
    const result = await res.json();
    return (result.adjustments || []).length ? result : null;
  } catch { return null; }
}

// ---------------- customer insight (first-party, consent first) ----------------

/** TMF699: a prospect knocks — no account, no token, just interest. */
export async function submitSalesLead(lead) {
  const res = await publicFetch('/tmf-api/salesManagement/v4/salesLead', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(lead),
  });
  if (!res.ok) throw new Error('Could not reach sales right now — please try again.');
  return res.json();
}

export function visitorId() {
  let id = localStorage.getItem(VISITOR_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(VISITOR_KEY, id);
  }
  return id;
}

export function consentChoice() {
  try { return JSON.parse(localStorage.getItem(CONSENT_KEY)); } catch { return null; }
}

export async function saveConsent(analytics, personalization) {
  localStorage.setItem(CONSENT_KEY, JSON.stringify({ analytics, personalization }));
  // the choice MUST land server-side (it gates every write there) — retry
  // through a cold start rather than silently losing it
  const send = () => publicFetch(`${INSIGHT}/consent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), analytics, personalization }),
  }).then((r) => { if (!r.ok) throw new Error(String(r.status)); });
  for (let attempt = 0; attempt < 3; attempt++) {
    try { await send(); return; } catch {
      await new Promise((r) => setTimeout(r, 1200 * (attempt + 1)));
    }
  }
}

/** Fire-and-forget breadcrumb — the server drops it without consent anyway;
 * the client also holds back, out of politeness. */
export function beacon(type, category, offeringId) {
  if (!consentChoice()?.analytics) return;
  publicFetch(`${INSIGHT}/event`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), type, category, offeringId,
      utmSource: new URLSearchParams(location.search).get('utm_source') || undefined }),
  }).catch(() => {});
}
