import { useEffect, useState } from 'react';
import { PartnerInvoices } from './PartnerInvoices.jsx';

/* Partner costs (#360, Benefit Choices) — what the operator owes its partners
 * for a month. A customer picks a partner's service with a Choice and pays
 * nothing more; the operator pays the partner its wholesale price for every
 * month the benefit is active. The benefit ledger records each month once and
 * the journal books it as cost of sales against a payable to the partner, so
 * this page and the Journal always tell the same story. */
const LEDGER = '/benefitAllowance/v1/partnerCost';

const thisMonth = () => new Date().toISOString().slice(0, 7);
const monthName = (m) => new Date(`${m}-01T00:00:00Z`)
  .toLocaleDateString(undefined, { month: 'long', year: 'numeric', timeZone: 'UTC' });
const money = (amount, currency) => new Intl.NumberFormat(undefined, { style: 'currency', currency: currency || 'EUR' })
  .format(Number(amount || 0));

export function PartnerCosts({ authFetch }) {
  const [month, setMonth] = useState(thisMonth());
  const [bill, setBill] = useState(null);
  const [version, setVersion] = useState(0);   // bumped when an invoice changes, to read the month again
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    // a new month starts blank; a re-read of the same month (after an invoice changed)
    // keeps what is on screen until the new answer lands, so nothing below unmounts
    setBill((b) => (b && b.period === month ? b : null));
    setError(null);
    authFetch(`${LEDGER}?period=${encodeURIComponent(month)}`)
      .then(async (res) => {
        if (!res.ok) throw new Error(`the benefit ledger answered ${res.status}`);
        return res.json();
      })
      .then((b) => { if (live) setBill(b); })
      .catch((e) => { if (live) setError(`Could not read partner costs, so this is unknown — not zero (${e.message})`); });
    return () => { live = false; };
  }, [authFetch, month, version]);

  return (
    <div data-testid="partner-costs">
      <label className="dim" style={{ fontSize: 13 }}>
        Month{' '}
        <input type="month" data-testid="partner-costs-month" value={month}
          onChange={(e) => e.target.value && setMonth(e.target.value)} />
      </label>
      {error ? <p className="error" role="alert" data-testid="partner-costs-error">{error}</p> : null}
      {bill && bill.lines.length === 0 ? (
        <p className="dim" data-testid="partner-costs-empty">Nothing is owed to benefit partners for {monthName(month)}.</p>
      ) : null}
      {bill && bill.lines.length > 0 ? (
        <>
          <p data-testid="partner-costs-total" style={{ margin: '10px 0' }}>
            For {monthName(month)} the operator owes its benefit partners <strong>{money(bill.total, bill.currency)}</strong>,
            booked on the Journal as partner benefits (cost of sales) against a payable to each partner.
          </p>
          <div style={{ overflowX: 'auto' }}>
            <table className="table">
              <thead><tr><th>Partner</th><th>Benefit</th><th>Households</th><th>Owed</th></tr></thead>
              <tbody>
                {bill.lines.map((l) => (
                  <tr key={`${l.partner}|${l.offeringName}`} data-testid="partner-cost-line">
                    <td>{l.partner}</td>
                    <td>{l.offeringName}</td>
                    <td>{l.active}</td>
                    <td>{money(l.amount, bill.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      ) : null}
      {bill ? <PartnerInvoices authFetch={authFetch} month={month} bill={bill} money={money}
        onChanged={() => setVersion((v) => v + 1)} /> : null}
    </div>
  );
}
