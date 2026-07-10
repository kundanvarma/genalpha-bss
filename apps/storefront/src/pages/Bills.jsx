import { useEffect, useState } from 'react';
import { billRates, createPayment, myBills, settleBill } from '../api.js';

export default function Bills() {
  const [bills, setBills] = useState(null);
  const [rates, setRates] = useState({});     // bill id -> line items
  const [paying, setPaying] = useState(null); // bill id with the card form open
  const [card, setCard] = useState({ cardNumber: '', expiry: '', cvc: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const load = () => myBills().then(setBills).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

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

  async function pay(bill) {
    setBusy(true);
    setError(null);
    try {
      const payment = await createPayment(
        { unit: bill.amountDue.unit, value: bill.amountDue.value },
        card, `Bill ${bill.billNo}`);
      await settleBill(bill.id, { id: payment.id, href: payment.href, '@referredType': 'Payment' });
      setPaying(null);
      setCard({ cardNumber: '', expiry: '', cvc: '' });
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  const cardReady = card.cardNumber.replace(/\s/g, '').length >= 12 && card.expiry.trim() && card.cvc.trim();

  return (
    <>
      <h1>My bills</h1>
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
                {bill.state === 'new' && (
                  <button className="primary" onClick={() => setPaying(paying === bill.id ? null : bill.id)}>
                    Pay
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
                    {busy ? 'Paying…' : `Pay ${bill.amountDue.value.toFixed(2)} ${bill.amountDue.unit}`}
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
