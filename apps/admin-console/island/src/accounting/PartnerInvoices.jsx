import { useState } from 'react';

/* Owed beside invoiced (#390). The ledger knows what each benefit partner is
 * owed for the month; the partner sends its own invoice. Finance records that
 * invoice here, sees the difference in words, and marks it paid — once — which
 * the Journal books as the payable settled against cash. The same invoice
 * recorded twice is still one invoice. */
const INVOICES = '/benefitAllowance/v1/partnerInvoice';

async function send(authFetch, url, body) {
  const res = await authFetch(url, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    // an unreadable error body still says what happened: the status
    const problem = await res.json().catch(() => ({ message: `the benefit ledger answered ${res.status}` }));
    throw new Error(problem.message || `the benefit ledger answered ${res.status}`);
  }
  return res.json();
}

function gap(p, money) {
  const d = Number(p.difference);
  if (!p.invoices.length) return 'no invoice yet';
  if (d === 0) return 'matches';
  return d > 0 ? `invoiced ${money(d)} more than owed` : `invoiced ${money(-d)} less than owed`;
}

export function PartnerInvoices({ authFetch, month, bill, money, onChanged }) {
  const [form, setForm] = useState({ partner: '', reference: '', amount: '' });
  const [said, setSaid] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const partners = bill.partners || [];
  const m = (v) => money(v, bill.currency);

  const run = (fn, words) => {
    setBusy(true); setError(null); setSaid(null);
    fn().then(() => { setSaid(words); onChanged(); }).catch((e) => setError(e.message)).finally(() => setBusy(false));
  };

  return (
    <section data-testid="partner-invoices" style={{ marginTop: 18 }}>
      <h4 style={{ margin: '0 0 6px' }}>Owed and invoiced</h4>
      {said ? <p className="ok" data-testid="partner-invoices-said">{said}</p> : null}
      {error ? <p className="error" role="alert">{error}</p> : null}
      {partners.length > 0 ? (
        <div style={{ overflowX: 'auto' }}>
          <table className="table">
            <thead><tr><th>Partner</th><th>Owed</th><th>Invoiced</th><th>Difference</th><th>Invoices</th></tr></thead>
            <tbody>
              {partners.map((p) => (
                <tr key={p.partner} data-testid="partner-reconciled" data-partner={p.partner}>
                  <td>{p.partner}</td>
                  <td>{m(p.owed)}</td>
                  <td>{m(p.invoiced)}</td>
                  <td data-testid="partner-difference">{gap(p, m)}</td>
                  <td>{p.invoices.map((i) => (
                    <div key={i.id} data-testid="partner-invoice">
                      {i.reference} · {m(i.amount)} · {i.paid ? `paid ${i.paidAt}` : (
                        <button type="button" className="ghost small" data-testid="partner-invoice-pay" disabled={busy}
                          onClick={() => run(() => send(authFetch, `${INVOICES}/${i.id}/pay`),
                            `${p.partner}'s invoice ${i.reference} is paid — the Journal settles the payable.`)}>
                          Mark paid
                        </button>
                      )}
                    </div>
                  ))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : <p className="dim">No partner is owed anything this month, and no invoice has arrived.</p>}
      <form data-testid="partner-invoice-new" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'end' }}
        onSubmit={(e) => {
          e.preventDefault();
          run(() => send(authFetch, INVOICES, { partner: form.partner, period: month, reference: form.reference, amount: Number(form.amount) }),
            `${form.partner}'s invoice ${form.reference} is recorded for this month.`);
        }}>
        <label className="small">Partner<br />
          <input name="partner" list="partner-names" required value={form.partner} disabled={busy}
            onChange={(e) => setForm({ ...form, partner: e.target.value })} />
          <datalist id="partner-names">{partners.map((p) => <option key={p.partner} value={p.partner} />)}</datalist>
        </label>
        <label className="small">Their invoice number<br />
          <input name="reference" required value={form.reference} disabled={busy}
            onChange={(e) => setForm({ ...form, reference: e.target.value })} />
        </label>
        <label className="small">Amount ({bill.currency})<br />
          <input name="amount" type="number" min="0" step="0.01" required value={form.amount} disabled={busy}
            onChange={(e) => setForm({ ...form, amount: e.target.value })} />
        </label>
        <button type="submit" disabled={busy}>Record invoice</button>
      </form>
    </section>
  );
}
