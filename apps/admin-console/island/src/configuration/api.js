/* Everything the Configuration page reads and writes.
 *
 * Three setup surfaces that used to sit among the daily pages — bill formats,
 * bill deliveries and shadow billing — plus the ladder that governs the chart
 * of accounts. All of it is setup: nobody opens these to do today's work, and
 * having them in the daily path was what made the daily path long.
 */

const BILLING = '/tmf-api/customerBillManagement/v4';
const REVENUE = '/revenue/v1';

export function configReader(authFetch) {
  const json = async (path) => {
    try {
      const res = await authFetch(path, { headers: { 'Cache-Control': 'no-cache' } });
      return res.ok ? await res.json() : null;
    } catch {
      return null; // a page that cannot reach one API still shows the rest
    }
  };
  const post = (path, body) => authFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });

  return {
    /* ---- the ladder over the chart of accounts ---- */
    changes: () => json(`${REVENUE}/configChange`),
    step: (id, name) => post(`${REVENUE}/configChange/${id}/${name}`),

    /* ---- bill formats: what a country's e-invoice profile IS ---- */
    formats: () => json(`${BILLING}/billFormatProfile`),
    saveFormat: (profile, existing) => authFetch(
      existing ? `${BILLING}/billFormatProfile/${encodeURIComponent(profile.code)}`
        : `${BILLING}/billFormatProfile`,
      {
        method: existing ? 'PATCH' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(profile),
      }),

    /* ---- deliveries: every bill's trip to the distribution partner ---- */
    deliveries: () => json(`${BILLING}/billDistribution`),
    retry: (id) => post(`${BILLING}/billDistribution/${id}/retry`),

    /* ---- shadow billing: what will bill differently next cycle ---- */
    drift: () => json(`${BILLING}/shadowDrift`),
    sweep: () => post(`${BILLING}/shadowDrift/sweep`),
  };
}

/** The syntaxes an e-invoice profile can declare, in the words the standard uses. */
export const SYNTAXES = [
  { value: 'ubl', label: 'UBL 2.1 (Peppol BIS, EHF, A-NZ…)' },
  { value: 'cii', label: 'UN/CEFACT CII (DACH / France)' },
];

export const syntaxLabel = (value) => (SYNTAXES.find((s) => s.value === value) || {}).label || value || '—';
