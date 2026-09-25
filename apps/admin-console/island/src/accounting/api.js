/* Everything the Accounting page reads, and the one file that builds a
 * journal query.
 *
 * The filter is built ONCE here, so the list on screen and the file an
 * operator downloads ask the subledger exactly the same question. A
 * reconciliation that downloads something other than what it looked at is
 * worse than no export at all.
 */

const REVENUE = '/revenue/v1';

/** The query string for one filter — shared by the list and the export. */
export function query(filter) {
  const q = new URLSearchParams();
  if (filter.fromDate) q.set('fromDate', filter.fromDate);
  if (filter.toDate) q.set('toDate', filter.toDate);
  if (filter.sourceType) q.set('sourceType', filter.sourceType);
  if (filter.account) q.set('account', filter.account);
  return q;
}

export const EMPTY = { fromDate: '', toDate: '', sourceType: '', account: '' };

/** Is anything actually narrowed down? */
export const narrowed = (filter) => Boolean(filter.fromDate || filter.toDate
  || filter.sourceType || filter.account);

export function reader(authFetch) {
  const json = async (path) => {
    try {
      const res = await authFetch(path, { headers: { 'Cache-Control': 'no-cache' } });
      return res.ok ? await res.json() : null;
    } catch {
      return null; // a page that cannot reach one API still shows the rest
    }
  };

  return {
    /** A page of the journal AND what the whole filter holds — the total is
     *  the service's judgement, never a count of the rows that fitted. */
    async journal(filter, limit, offset) {
      const q = query(filter);
      q.set('limit', String(limit));
      q.set('offset', String(offset));
      try {
        const res = await authFetch(`${REVENUE}/journalEntry?${q}`, { headers: { 'Cache-Control': 'no-cache' } });
        if (!res.ok) return null;
        const total = res.headers.get('X-Total-Count');
        return { rows: await res.json(), total: total === null ? null : Number(total) };
      } catch {
        return null;
      }
    },
    sourceTypes: () => json(`${REVENUE}/journalSourceType`),
    chart: () => json(`${REVENUE}/accountMapping`),
    changes: () => json(`${REVENUE}/configChange`),

    /** The same question, as a file the general ledger can ingest. */
    async exportCsv(filter, format) {
      const q = query(filter);
      if (format) q.set('format', format);
      const res = await authFetch(`${REVENUE}/journalExport?${q}`);
      if (!res || !res.ok) return null;
      return res.text();
    },

    /** Rung one of the ladder: write the proposal down. */
    draft: (body) => authFetch(`${REVENUE}/configChange`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    /** Every other rung is the same shape: a named step on one change. */
    step: (id, name) => authFetch(`${REVENUE}/configChange/${id}/${name}`, { method: 'POST' }),
  };
}

/** Hand the operator a file without leaving the page. */
export function offerDownload(text, name) {
  const blob = new Blob([text], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}
