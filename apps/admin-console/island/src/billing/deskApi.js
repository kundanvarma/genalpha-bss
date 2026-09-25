/* Everything the overview, payments and collections screens read.
 *
 * Every figure on these screens is a fact some service already decided: a
 * bill's situation is computed once in billing, a run's status is the run's
 * own, an unapplied payment is money the bank named and no bill claimed. The
 * desk sums and sorts; it never decides whether something is late.
 */

const BILLING = '/tmf-api/customerBillManagement/v4';
const PAYMENT = '/tmf-api/paymentManagement/v4';
const PARTY = '/tmf-api/party/v4';
const RISK = '/tmf-api/riskManagement/v4';
const COMMS = '/tmf-api/communicationManagement/v4';

/** A reader bound to the shell's fetch: JSON on 200, null on anything else. */
export function deskReader(authFetch) {
  const json = async (path) => {
    try {
      const res = await authFetch(path, { headers: { 'Cache-Control': 'no-cache' } });
      return res.ok ? await res.json() : null;
    } catch {
      return null; // a screen that cannot reach one API still shows the rest
    }
  };
  const names = new Map();

  return {
    json,
    /* HOW MANY, asked of the service rather than counted in the browser.
     *
     * A page of bills is not the book: this tenant holds 4 691 bills and a
     * list caps a page at 100. Counting a page and printing the answer as
     * "103 overdue" when the true number is 3 815 is the kind of figure an
     * operator acts on, so the count comes from the service — billSituation
     * judges every bill, then pages, and X-Total-Count is that judged total.
     * One cheap row is fetched because the header is what is wanted. */
    async countOf(situation) {
      try {
        const q = situation ? `&situation=${encodeURIComponent(situation)}` : '';
        const res = await authFetch(`${BILLING}/billSituation?limit=1${q}`, { headers: { 'Cache-Control': 'no-cache' } });
        if (!res.ok) return null;
        const total = res.headers.get('X-Total-Count');
        return total === null ? null : Number(total);
      } catch {
        return null;
      }
    },
    /** A sample of one situation — rows to show, never a total to quote. */
    someOf: (situation, limit = 100) => json(
      `${BILLING}/billSituation?limit=${limit}&situation=${encodeURIComponent(situation)}`),
    /** One customer's bills, asked for by customer — not filtered out of a page. */
    billsOf: (partyId) => json(
      `${BILLING}/customerBill?relatedPartyId=${encodeURIComponent(partyId)}&limit=100`),
    /** The bill behind a bill number, because the operator types the number
     *  and the apply endpoint takes the identity. */
    async billByNo(billNo) {
      const found = await json(`${BILLING}/customerBill?billNo=${encodeURIComponent(billNo)}&limit=1`);
      return (found || [])[0] || null;
    },
    runs: () => json(`${BILLING}/billingRun`),
    /** The hundred most recent parked rows — the endpoint serves no more and
     *  reports no total, so nothing here may be called "all of it". */
    unapplied: () => json(`${BILLING}/remittance/unapplied`),
    cases: () => json(`${BILLING}/collectionCase`),
    caseOf: (id) => json(`${BILLING}/collectionCase/${id}`),
    payments: (limit = 100) => json(`${PAYMENT}/payment?limit=${limit}`),
    riskOf: (partyId) => json(`${RISK}/partyRiskAssessment?relatedParty.id=${encodeURIComponent(partyId)}`),
    /* The filter key is the house one, relatedPartyId — "relatedParty.id" is
     * refused with a 400, which this reader turns into null, which a screen
     * then shows as "nothing has been sent". A wrong key looked exactly like
     * a quiet customer. */
    noticesFor: (partyId) => json(`${COMMS}/communicationMessage?relatedPartyId=${encodeURIComponent(partyId)}&limit=20`),

    /** Apply unapplied cash to a bill — the one action billing serves here.
     *  The endpoint takes the bill's identity; the operator types its number,
     *  so the number is looked up first and an unknown one is said plainly. */
    applyUnapplied: (id, billId) => authFetch(`${BILLING}/remittance/unapplied/${id}/apply`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ billId }),
    }),

    /** A customer is a name on this desk, never an identifier.
     *
     *  A failed lookup is NOT cached: the reader turns any error into null, so
     *  a burst of requests that the gateway trims would otherwise write "this
     *  customer" over a real name and keep it there for the session. A list of
     *  a hundred cases showed most of its rows as "this customer" that way,
     *  while every one of those parties resolved perfectly when asked alone. */
    async partyName(id) {
      if (!id) return '—';
      if (names.has(id)) return names.get(id);
      const p = await json(`${PARTY}/individual/${id}`);
      let name = p && ([p.givenName, p.familyName].filter(Boolean).join(' ') || p.fullName || p.name);
      if (!name) {
        const o = await json(`${PARTY}/organization/${id}`);
        name = o && (o.tradingName || o.name);
      }
      if (name) names.set(id, name);
      return name || 'this customer';
    },
  };
}

/** Sum a list of {value, unit} by unit — a tenant may hold more than one. */
export function totals(amounts) {
  const by = new Map();
  for (const m of amounts) {
    if (!m || m.value === null || m.value === undefined) continue;
    const unit = m.unit || '';
    by.set(unit, (by.get(unit) || 0) + Number(m.value || 0));
  }
  return [...by.entries()].map(([unit, value]) => ({ unit, value }));
}

/** The situation counts and sums of one page of bills. */
export function tally(bills) {
  const count = {};
  const owed = {};
  for (const b of bills || []) {
    const v = (b.billSituation || {}).value;
    if (!v) continue;
    count[v] = (count[v] || 0) + 1;
    (owed[v] = owed[v] || []).push((b.billSituation || {}).outstanding || b.amountDue);
  }
  return { count, sum: (v) => totals(owed[v] || []) };
}

/** The most recent run, and whether the last one failed. */
export function lastRun(runs) {
  const list = [...(runs || [])].sort((a, b) => String(b.startedAt || '').localeCompare(String(a.startedAt || '')));
  const last = list[0] || null;
  const failed = last && /fail|error/i.test(last.status || '') ? last : null;
  return { last, failed };
}

/** The party a collection case or a bill belongs to. */
export function partyOf(row) {
  const parties = (row && row.relatedParty) || [];
  const customer = parties.find((p) => /customer|owner/i.test(p.role || '')) || parties[0];
  return customer ? customer.id : null;
}
