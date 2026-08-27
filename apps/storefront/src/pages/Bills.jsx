import { useEffect, useState } from 'react';
import { t } from '../i18n.js';
import { billRates, createPayment, disputeBill, myBills, myCollectionCase, myCreditNotes, myPaymentMethods, payInstallment, paymentWithSavedMethod, promiseToPay, setBillDelivery, setBillingDay, settleBill, splitBill } from '../api.js';

/* The consequences line the law wants said plainly, per ladder rung. */
const CASE_CONSEQUENCE = {
  reminded: 'A payment reminder has been sent (the statutory reminder fee rides the bill). '
    + 'If the balance stays unpaid, a payment demand follows and services can later be restricted.',
  warned: 'A payment demand has been sent. Unless the balance is paid, outgoing services can be '
    + 'restricted at the earliest one month after the demand — emergency numbers always stay reachable.',
  restricted: 'Outgoing services are restricted — emergency numbers still work. '
    + 'Unless the balance is paid, the line will be suspended next.',
  suspended: 'Your line is suspended for non-payment. No subscription charges accrue while it is '
    + 'suspended — paying restores your services at once.',
  terminated: 'This subscription was terminated for non-payment. Please contact us to settle the remaining balance.',
  writtenOff: 'This balance has been closed. Please contact us if you believe this is wrong.',
};

export default function Bills() {
  const [bills, setBills] = useState(null);
  const [creditNotes, setCreditNotes] = useState([]);
  const [rates, setRates] = useState({});     // bill id -> line items
  const [paying, setPaying] = useState(null); // bill id with the card form open
  const [card, setCard] = useState({ cardNumber: '', expiry: '', cvc: '' });
  const [savedMethods, setSavedMethods] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [ccase, setCcase] = useState(null);   // the customer's own collection case
  const [promiseDays, setPromiseDays] = useState(7);
  const [promiseErr, setPromiseErr] = useState(null);
  const [promiseBusy, setPromiseBusy] = useState(false);

  const load = () => {
    myBills().then(setBills).catch((e) => setError(e.message));
    myCreditNotes().then(setCreditNotes).catch(() => {});
    myCollectionCase().then(setCcase).catch(() => {});
  };
  useEffect(() => { load(); }, []);
  // Hooks must run unconditionally, BEFORE the early returns below.
  useEffect(() => {
    if (paying) myPaymentMethods().then(setSavedMethods).catch(() => {});
  }, [paying]);

  if (error && !bills) return <p className="error">{error}</p>;
  if (!bills) return <p className="dim">Loading your bills…</p>;
  if (!bills.length) return <p className="dim">No bills yet — they appear after each billing period.</p>;

  async function toggleRates(bill) {
    if (rates[bill.id]) {
      setRates(({ [bill.id]: gone, ...rest }) => rest);
      return;
    }
    try {
      setRates({ ...rates, [bill.id]: await billRates(bill.id) });
    } catch (e) {
      setError(e.message);
    }
  }

  // a bill on an active plan pays its NEXT INSTALLMENT; anything else pays in full
  const dueNow = (bill) => bill.installmentPlan?.status === 'active'
    ? { unit: bill.installmentPlan.currency, value: Number(bill.installmentPlan.nextAmount) }
    : bill.amountDue;
  const settleOrInstall = (bill, payment) => bill.installmentPlan?.status === 'active'
    ? payInstallment(bill.id, { id: payment.id, href: payment.href, '@referredType': 'Payment' })
    : settleBill(bill.id, { id: payment.id, href: payment.href, '@referredType': 'Payment' });

  async function pay(bill) {
    setBusy(true);
    setError(null);
    try {
      const payment = await createPayment(dueNow(bill), card, `Bill ${bill.billNo}`);
      await settleOrInstall(bill, payment);
      setPaying(null);
      setCard({ cardNumber: '', expiry: '', cvc: '' });
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  async function payWithSaved(bill, method) {
    setError(null);
    try {
      const payment = await paymentWithSavedMethod(
        dueNow(bill), method.id, `Bill ${bill.billNo}`);
      await settleOrInstall(bill, payment);
      setPaying(null);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  async function split(bill) {
    const n = window.prompt('Split this bill into how many monthly payments? (2-12)', '3');
    if (!n) return;
    setError(null);
    try { await splitBill(bill.id, Number(n)); load(); } catch (e) { setError(e.message); }
  }

  const cardReady = card.cardNumber.replace(/\s/g, '').length >= 12 && card.expiry.trim() && card.cvc.trim();

  async function requestPaymentDate() {
    setPromiseBusy(true);
    setPromiseErr(null);
    try {
      const updated = await promiseToPay(ccase.id, promiseDays);
      setCcase(updated);
    } catch (e) {
      // the backend's words verbatim — allowance used up, case closed, …
      setPromiseErr(e.message);
    } finally {
      setPromiseBusy(false);
    }
  }

  // the first bill the existing payment path can take money against
  const payableBill = bills.find((b) => b.state === 'new'
    || (b.state === 'partiallyPaid' && b.installmentPlan?.status === 'active'));
  const caseOpen = ccase && ccase.state && ccase.state !== 'current';
  const standingPromise = caseOpen && ccase.holds && ccase.holds.promiseToPay;
  const promiseAllowed = caseOpen && !standingPromise
    && !['terminated', 'writtenOff'].includes(ccase.state);

  return (
    <>
      {caseOpen && (
        <div className="collectionsbanner" data-testid="collections-banner">
          <div className="row" style={{ border: 'none', padding: 0 }}>
            <div>
              <strong>Outstanding balance</strong>{' '}
              <span className={`state ${ccase.state}`} data-testid="collections-state">{ccase.state}</span>
              <div data-testid="collections-amount" style={{ marginTop: 4 }}>
                {Number(ccase.overdueBalance?.value || 0).toFixed(2)} {ccase.overdueBalance?.unit || ''} overdue
                {ccase.oldestDueAt && <span className="dim small"> · oldest due {String(ccase.oldestDueAt).slice(0, 10)}</span>}
                {ccase.holds?.dispute && <span className="dim small"> · {Number(ccase.holds.dispute.amount).toFixed(2)} in dispute (not counted against you)</span>}
              </div>
            </div>
            {payableBill && (
              <button className="primary" data-testid="collections-pay-now"
                      onClick={() => setPaying(payableBill.id)}>
                Pay now
              </button>
            )}
          </div>
          <p className="dim small" data-testid="collections-consequence" style={{ margin: '8px 0 0' }}>
            {CASE_CONSEQUENCE[ccase.state] || 'Please settle the outstanding balance to keep your services running.'}
          </p>
          {standingPromise && (
            <p className="small" data-testid="promise-active" style={{ margin: '8px 0 0' }}>
              ✓ You promised to pay {Number(standingPromise.amount).toFixed(2)} {ccase.overdueBalance?.unit || ''} by{' '}
              {String(standingPromise.dueAt).slice(0, 10)} — reminders pause until then.
            </p>
          )}
          {promiseAllowed && (
            <div className="stack" data-testid="promise-form" style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
              <span className="dim small">Need a few more days? Request a payment date:</span>
              <select data-testid="promise-days" value={promiseDays}
                      onChange={(e) => setPromiseDays(Number(e.target.value))}>
                {[3, 5, 7, 10, 14].map((d) => <option key={d} value={d}>within {d} days</option>)}
              </select>
              <button className="ghost" data-testid="promise-submit" disabled={promiseBusy}
                      onClick={requestPaymentDate}>
                {promiseBusy ? 'Sending…' : 'Request a payment date'}
              </button>
            </div>
          )}
          {promiseErr && <p className="error small" data-testid="promise-error" style={{ margin: '6px 0 0' }}>{promiseErr}</p>}
        </div>
      )}
      <h1>My bills
        <button className="ghost" data-testid="change-billing-day" style={{ marginLeft: 12, fontSize: 13 }}
          onClick={async () => {
            const day = window.prompt('Which day of the month should your billing cycle start? (1-28)');
            if (!day) return;
            try {
              await setBillingDay(Number(day));
              setError(null);
              window.alert(`Done — your cycle starts on day ${day} from your next bill.`);
            } catch (e) { setError(e.message); }
          }}>
          Change billing date
        </button>
        <select className="ghost" data-testid="bill-delivery" defaultValue=""
          title="How your bill reaches you — the in-app bill always stays"
          style={{ marginLeft: 8, fontSize: 13 }}
          onChange={async (e) => {
            if (!e.target.value) return;
            try {
              await setBillDelivery(e.target.value === 'default' ? null : e.target.value);
              setError(null);
              window.alert('Done — your bill delivery preference is saved.');
            } catch (err) { setError(err.message); }
          }}>
          <option value="" disabled>Bill delivery…</option>
          <option value="digital">Digital only (in-app / email)</option>
          <option value="einvoice">E-invoice</option>
          <option value="paper">Paper by post</option>
          <option value="default">Operator default</option>
        </select>
      </h1>
      {error && <p className="error">{error}</p>}
      <div className="rows">
        {bills.map((bill) => (
          <div key={bill.id}>
            <div className="row">
              <div>
                <strong>{bill.billNo}</strong>
                <div className="dim small">
                  {bill.billingPeriod?.startDateTime} — {bill.billingPeriod?.endDateTime}
                  {' · '}
                  <button className="linkish" onClick={() => toggleRates(bill)}>
                    {rates[bill.id] ? 'hide items' : 'show items'}
                  </button>
                </div>
              </div>
              <div className="rowend">
                <span className="linetotal">{bill.amountDue.value.toFixed(2)} {bill.amountDue.unit}</span>
                <span className={`state ${bill.state}`}>{bill.state}</span>
                <a className="ghost" data-testid="bill-pdf" style={{ textDecoration: 'none' }}
                   href={`/tmf-api/customerBillManagement/v4/customerBill/${bill.id}/document.pdf`}
                   target="_blank" rel="noreferrer"
                   onClick={(e) => { // carry the token: fetch and open as a blob
                     e.preventDefault();
                     import('../auth.js').then(async ({ authFetch }) => {
                       const res = await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${bill.id}/document.pdf`);
                       if (!res.ok) { setError('PDF: HTTP ' + res.status); return; }
                       const blob = await res.blob();
                       window.open(URL.createObjectURL(blob), '_blank');
                     });
                   }}>
                  PDF
                </a>
                <button className="ghost" data-testid="email-bill"
                  title="Email this invoice (PDF) to your address on file"
                  onClick={async () => {
                    const { authFetch } = await import('../auth.js');
                    const res = await authFetch(`/tmf-api/customerBillManagement/v4/customerBill/${bill.id}/resend`,
                      { method: 'POST', headers: { 'Content-Type': 'application/json' } });
                    if (!res.ok) { setError('Email: HTTP ' + res.status); return; }
                    setError(null);
                  }}>
                  Email me
                </button>
                {bill.dispute && (
                  <span className={`state ${bill.dispute.status === 'open' ? 'onHold' : bill.dispute.status}`}
                        data-testid="dispute-chip" title={bill.dispute.reason}>
                    dispute {bill.dispute.status}
                  </span>
                )}
                {creditNotes.filter((cn) => cn.billId === bill.id).map((cn) => (
                  <a key={cn.id} className="state settled" data-testid="credit-note-chip"
                     title={cn.reason} style={{ textDecoration: 'none', cursor: 'pointer' }}
                     onClick={(e) => {
                       e.preventDefault();
                       import('../auth.js').then(async ({ authFetch }) => {
                         const res = await authFetch(`/tmf-api/customerBillManagement/v4/creditNote/${cn.id}/document.pdf`);
                         if (!res.ok) { setError('PDF: HTTP ' + res.status); return; }
                         window.open(URL.createObjectURL(await res.blob()), '_blank');
                       });
                     }}>
                    {t('credit note')} {cn.creditNoteNo} · −{Number(cn.amount.value).toFixed(2)} {cn.amount.unit}
                  </a>
                ))}
                {(!bill.dispute || bill.dispute.status !== 'open') && (
                  <button className="ghost" data-testid="dispute-bill"
                    onClick={async () => {
                      const reason = window.prompt('What looks wrong on this bill?');
                      if (!reason) return;
                      try { await disputeBill(bill.id, reason); load(); } catch (e) { setError(e.message); }
                    }}>
                    Dispute
                  </button>
                )}
                {bill.installmentPlan && bill.installmentPlan.status !== 'cancelled' && (
                  <span className="dim small" data-testid="plan-chip">
                    {bill.installmentPlan.paidCount}/{bill.installmentPlan.installments} paid
                  </span>
                )}
                {bill.state === 'new' && !bill.installmentPlan && (
                  <button className="ghost" data-testid="split-bill" onClick={() => split(bill)}>
                    Pay in parts
                  </button>
                )}
                {(bill.state === 'new' || (bill.state === 'partiallyPaid'
                    && bill.installmentPlan?.status === 'active')) && (
                  <button className="primary" data-testid="pay-bill"
                          onClick={() => setPaying(paying === bill.id ? null : bill.id)}>
                    {bill.installmentPlan?.status === 'active'
                      ? `Pay part ${bill.installmentPlan.paidCount + 1}` : 'Pay'}
                  </button>
                )}
              </div>
            </div>
            {rates[bill.id] && (
              <div className="billitems">
                {rates[bill.id].map((rate) => (
                  <div className="row small" key={rate.id}>
                    <span className="dim">{rate.name}</span>
                    <span>{Number(rate.taxExcludedAmount.value).toFixed(2)} {rate.taxExcludedAmount.unit}</span>
                  </div>
                ))}
              </div>
            )}
            {paying === bill.id && (
              <div className="payment billpay">
                {savedMethods.length > 0 && (
                  <div className="savedcards">
                    {savedMethods.map((m) => (
                      <button key={m.id} className="ghost savedcard" data-testid="saved-card"
                              onClick={() => payWithSaved(bill, m)}>
                        Pay with {m.details.lastFourDigits
                          ? `${m.details.brand} •••• ${m.details.lastFourDigits}`
                          : (m.details.brand === 'klarna' ? 'Klarna (saved)' : m.details.brand)}
                      </button>
                    ))}
                  </div>
                )}
                <div className="addressgrid">
                  <label className="charfield"><span>Card number</span>
                    <input name="cardNumber" value={card.cardNumber} inputMode="numeric"
                           placeholder="4242 4242 4242 4242"
                           onChange={(e) => setCard({ ...card, cardNumber: e.target.value })} /></label>
                  <label className="charfield"><span>Expiry</span>
                    <input name="expiry" value={card.expiry} placeholder="MM/YY"
                           onChange={(e) => setCard({ ...card, expiry: e.target.value })} /></label>
                  <label className="charfield"><span>CVC</span>
                    <input name="cvc" value={card.cvc} inputMode="numeric" placeholder="123"
                           onChange={(e) => setCard({ ...card, cvc: e.target.value })} /></label>
                </div>
                <div className="cartactions">
                  <span />
                  <button className="primary" disabled={busy || !cardReady} onClick={() => pay(bill)}>
                    {busy ? 'Paying…' : `Pay ${Number(dueNow(bill).value).toFixed(2)} ${dueNow(bill).unit}`}
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </>
  );
}
