// numbers: part of the storefront's TMF client (split from api.js; the barrel re-exports).
import { PORTING, SERVICE_INV, authFetch, json, publicFetch } from './_http.js';

function countryOf(number) {
  const n = (number || '').replace(/\s/g, '');
  if (n.startsWith('+47')) return 'NO';
  if (n.startsWith('+46')) return 'SE';
  if (n.startsWith('+45')) return 'DK';
  if (n.startsWith('+358')) return 'FI';
  if (n.startsWith('+592')) return 'GY';
  if (n.startsWith('+44')) return 'GB';
  if (n.startsWith('+1')) return 'US';
  // no prefix given: the shopper is porting within the operator's own country
  return (window.BSS_STOREFRONT_CONFIG || {}).country || 'NO';
}

/** Choose-your-number: a shortlist of available numbers from the pool —
 * previewed, never consumed; a new shuffle deals a fresh hand. Fail-soft []. */
export async function numberOffers(shuffle = null) {
  try {
    const q = shuffle ? `?shuffle=${encodeURIComponent(shuffle)}` : '';
    return await json(await publicFetch(`/tmf-api/resourcePoolManagement/v4/numberOffer${q}`));
  } catch { return []; }
}

export async function requestPortIn(partyId, number, currentProvider, portDate = null) {
  const created = await json(await authFetch(`${PORTING}/numberPortingOrder`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      direction: 'portIn', phoneNumber: number, country: countryOf(number),
      otherOperator: currentProvider, relatedParty: [{ id: partyId, role: 'customer' }],
      // the customer's wish date — a morning window on the chosen day
      ...(portDate ? { requestedCutover: `${portDate}T08:00:00Z` } : {}),
    }),
  }));
  // Dev: an AS-SOON-AS-POSSIBLE cutover is compressed to the checkout;
  // a FUTURE-DATED port stays scheduled until its window — the date the
  // customer picked is a promise, not a decoration. Production always waits
  // for the clearinghouse's agreed window.
  if (created.status === 'scheduled' && !portDate) {
    await authFetch(`${PORTING}/numberPortingOrder/${created.id}/complete`, { method: 'POST' })
      .catch(() => {});
  }
  return created;
}

export async function myActiveServices() {
  try {
    return await json(await authFetch(`${SERVICE_INV}/service`));
  } catch { return []; }
}
