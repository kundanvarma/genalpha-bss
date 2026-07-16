import { useEffect, useState } from 'react';
import { billRates, createPayment, disputeBill, myBills, myPaymentMethods, payInstallment, paymentWithSavedMethod, setBillingDay, settleBill, splitBill } from '../api.js';

export default function Bills() {
  const [bills, setBills] = useState(null);
  const [rates, setRates] = useState({});     // bill id -> line items
  const [paying, setPaying] = useState(null); // bill id with the card form open
  const [card, setCard] = useState({ cardNumber: '', expiry: '', cvc: '' });
  const [savedMethods, setSavedMethods] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const load = () => myBills().then(setBills).catch((e) => setError(e.message));
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

  return (
    <>
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
                {bill.dispute && (
                  <span className={`state ${bill.dispute.status === 'open' ? 'onHold' : bill.dispute.status}`}
                        data-testid="dispute-chip" title={bill.dispute.reason}>
                    dispute {bill.dispute.status}
                  </span>
                )}
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
                        Pay with {m.details.brand} •••• {m.details.lastFourDigits}
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
