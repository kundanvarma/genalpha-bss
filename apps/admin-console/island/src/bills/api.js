/* Everything the Bills desk reads, and nothing it computes.
 *
 * Each call names an endpoint that already exists; the situation, the amounts
 * and the dates arrive decided. The island is handed the shell's authenticated
 * fetch, so there is no second sign-in and no second API client.
 */

const BILLING = '/tmf-api/customerBillManagement/v4';
const PARTY = '/tmf-api/party/v4';
const PAYMENT = '/tmf-api/paymentManagement/v4';
const REVENUE = '/revenue/v1';

/** A reader bound to the shell's fetch: JSON on 200, null on anything else. */
export function reader(authFetch) {
  const json = async (path) => {
    try {
      const res = await authFetch(path, { headers: { 'Cache-Control': 'no-cache' } });
      return res.ok ? await res.json() : null;
    } catch {
      return null; // a desk that cannot reach one API still shows the rest
    }
  };
  const names = new Map();

  return {
    /** The desk's own list: bills with their situation, newest page first. */
    bills: (limit = 100, offset = 0) => json(`${BILLING}/billSituation?limit=${limit}&offset=${offset}`),
    bill: (id) => json(`${BILLING}/customerBill/${id}`),
    lines: (id) => json(`${BILLING}/customerBill/${id}/appliedCustomerBillingRate`),
    creditNotes: (billId) => json(`${BILLING}/creditNote?billId=${encodeURIComponent(billId)}`),
    disputes: () => json(`${BILLING}/dispute`),
    deliveries: () => json(`${BILLING}/billDistribution`),
    payment: (id) => json(`${PAYMENT}/payment/${id}`),
    /** Postings are keyed by what made them: a bill, a payment, a credit note. */
    postings: (sourceRef) => json(`${REVENUE}/journalEntry?sourceRef=${encodeURIComponent(sourceRef)}`),

    /** A customer is a name on this desk, never an identifier. */
    async partyName(id) {
      if (!id) return '—';
      if (names.has(id)) return names.get(id);
      const p = await json(`${PARTY}/individual/${id}`);
      let name = p && ([p.givenName, p.familyName].filter(Boolean).join(' ') || p.fullName || p.name);
      if (!name) {
        const o = await json(`${PARTY}/organization/${id}`);
        name = (o && (o.tradingName || o.name)) || 'this customer';
      }
      names.set(id, name);
      return name;
    },
  };
}

/** The customer party on a bill, whatever role list the wire carried. */
export function customerId(bill) {
  const parties = (bill && bill.relatedParty) || [];
  const customer = parties.find((p) => /customer/i.test(p.role || '')) || parties[0];
  return customer ? customer.id : null;
}

/** Everything one bill's workspace needs, gathered in parallel. */
export async function workspaceOf(api, id) {
  const bill = await api.bill(id);
  if (!bill) return null;
  const billNo = bill.billNo;
  const [lines, credits, disputes, deliveries, billPostings] = await Promise.all([
    api.lines(id), api.creditNotes(id), api.disputes(), api.deliveries(), api.postings(`bill:${id}`),
  ]);
  const payments = await Promise.all(((bill.payment) || []).map((p) => api.payment(p.id)));
  const paid = payments.filter(Boolean);
  // postings are asked for by what made them, so nothing is scanned or guessed
  const rest = await Promise.all([
    ...paid.map((p) => api.postings(`payment:${p.id}:${p.status}`)),
    ...((credits) || []).map((c) => api.postings(`creditNote:${c.id}`)),
  ]);
  return {
    bill,
    lines: lines || [],
    credits: credits || [],
    disputes: (disputes || []).filter((d) => d.billId === id || (billNo && d.billNo === billNo)),
    deliveries: (deliveries || []).filter((d) => billNo && d.billNo === billNo),
    payments: paid,
    postings: [...(billPostings || []), ...rest.flat().filter(Boolean)],
  };
}
