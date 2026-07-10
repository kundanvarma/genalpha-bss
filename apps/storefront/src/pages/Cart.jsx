import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { availabilityFor, getOffering, myParty, priceIndex } from '../api.js';
import { beginLogin, isSignedIn } from '../auth.js';
import { CART_EVENT, cartLines, clearCart, removeLine, setQuantity } from '../cart.js';
import { ADDRESS_FIELDS, addressOf, isComplete, loadDraft, saveDraft } from '../address.js';
import { dueNow, performCheckout } from '../checkout.js';
import { monthlyTotal, pricesOf } from '../money.js';
import { setPendingCheckout } from '../pending.js';

export default function Cart() {
  const navigate = useNavigate();
  const [lines, setLines] = useState(cartLines());
  const [offerings, setOfferings] = useState({}); // offering id -> full offering
  const [prices, setPrices] = useState({});
  const [physical, setPhysical] = useState({});   // offering id -> boolean (stock-managed)
  const [address, setAddress] = useState(loadDraft());
  const [card, setCard] = useState({ cardNumber: '', expiry: '', cvc: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const refresh = () => setLines(cartLines());
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    const allIds = [...new Set(lines.flatMap((l) => [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))];
    const newIds = allIds.filter((id) => !offerings[id]);
    Promise.all([
      newIds.length ? Promise.all(newIds.map(getOffering)) : [],
      priceIndex(),
      Promise.all(allIds.filter((id) => physical[id] === undefined)
        .map(async (id) => [id, (await availabilityFor(id)) != null])),
    ])
      .then(([loaded, p, phys]) => {
        setPrices(p);
        if (loaded.length) {
          setOfferings((o) => ({ ...o, ...Object.fromEntries(loaded.map((f) => [f.id, f])) }));
        }
        if (phys.length) {
          setPhysical((prev) => ({ ...prev, ...Object.fromEntries(phys) }));
        }
      })
      .catch((e) => setError(e.message));
  }, [lines]);

  // Prefill from the saved party address once signed in (draft wins if typed).
  useEffect(() => {
    if (!isSignedIn() || isComplete(loadDraft())) return;
    myParty().then((party) => {
      const saved = addressOf(party);
      if (saved) setAddress(saved);
    }).catch(() => {});
  }, []);

  if (!lines.length) {
    return <p className="dim">Your cart is empty — <Link to="/">browse the offers</Link>.</p>;
  }

  const needsShipping = lines.some((l) =>
    physical[l.offeringId] || (l.selections || []).some((s) => physical[s.offeringId]));
  const addressReady = !needsShipping || isComplete(address);
  const due = dueNow(lines, offerings, prices);
  const signedIn = isSignedIn();
  const cardReady = !due || !signedIn
    || (card.cardNumber.replace(/\s/g, '').length >= 12 && card.expiry.trim() && card.cvc.trim());

  function setField(name, value) {
    const next = { ...address, [name]: value };
    setAddress(next);
    saveDraft(next);
  }

  function lineMonthly(line) {
    const own = offerings[line.offeringId] ? monthlyTotal(pricesOf(offerings[line.offeringId], prices)) : null;
    const opts = (line.selections || [])
      .map((s) => offerings[s.offeringId] ? monthlyTotal(pricesOf(offerings[s.offeringId], prices)) : null)
      .filter(Boolean);
    if (!own && !opts.length) return null;
    return {
      value: ((own?.value || 0) + opts.reduce((a, m) => a + m.value, 0)) * line.quantity,
      unit: own?.unit || opts[0]?.unit || 'EUR',
    };
  }

  const totals = lines.map(lineMonthly).filter(Boolean);
  const grand = totals.length
    ? { value: totals.reduce((a, m) => a + m.value, 0), unit: totals[0].unit }
    : null;

  async function checkout() {
    if (!isSignedIn()) {
      // Cart and typed address survive the redirect in localStorage; this
      // flag makes the checkout resume automatically after sign-in.
      setPendingCheckout();
      await beginLogin();
      return;
    }
    setBusy(true);
    try {
      await performCheckout(lines, due ? card : null);
      clearCart();
      navigate('/orders');
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Cart</h1>
      {error && <p className="error">{error}</p>}
      <div className="rows">
        {lines.map((line) => {
          const monthly = lineMonthly(line);
          return (
            <div className="row" key={line.key}>
              <div>
                <strong>{line.name}</strong>
                {(line.selections || []).map((s) => (
                  <div className="dim small" key={s.offeringId}>
                    {s.name}
                    {Object.entries(s.characteristics || {}).map(([k, v]) => ` · ${v}`).join('')}
                  </div>
                ))}
              </div>
              <div className="rowend">
                {monthly && <span className="linetotal">{monthly.value.toFixed(2)} {monthly.unit}/mo</span>}
                <div className="qty">
                  <button className="ghost" aria-label="decrease"
                          onClick={() => setQuantity(line.key, line.quantity - 1)}>−</button>
                  <span className="qtyval">{line.quantity}</span>
                  <button className="ghost" aria-label="increase"
                          onClick={() => setQuantity(line.key, line.quantity + 1)}>+</button>
                </div>
                <button className="ghost danger" onClick={() => removeLine(line.key)}>Remove</button>
              </div>
            </div>
          );
        })}
        {grand && (
          <div className="row granded">
            <strong>Total per month</strong>
            <strong className="linetotal">{grand.value.toFixed(2)} {grand.unit}</strong>
          </div>
        )}
        {due && (
          <div className="row granded duenow">
            <strong>Due now</strong>
            <strong className="linetotal">{due.value.toFixed(2)} {due.unit}</strong>
          </div>
        )}
      </div>

      {needsShipping && (
        <div className="shipping">
          <h2>Shipping address</h2>
          <p className="dim small">Your cart contains devices that will be delivered.</p>
          <div className="addressgrid">
            {ADDRESS_FIELDS.map((f) => (
              <label className="charfield" key={f.name}>
                <span>{f.label}</span>
                <input name={f.name} value={address[f.name] || ''}
                       onChange={(e) => setField(f.name, e.target.value)} />
              </label>
            ))}
          </div>
        </div>
      )}

      {due && (signedIn ? (
        <div className="payment">
          <h2>Payment</h2>
          <p className="dim small">Card is charged for the one-time amount due now. Dev PSP: any card
            works, a number ending 0002 declines.</p>
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
        </div>
      ) : (
        <p className="dim small paynote">You'll confirm the payment after signing in.</p>
      ))}

      <div className="cartactions">
        <Link to="/" className="dim">Continue shopping</Link>
        <button className="primary big" onClick={checkout} disabled={busy || !addressReady || !cardReady}>
          {busy ? 'Placing order…'
            : !addressReady ? 'Enter shipping address'
            : !cardReady ? 'Enter card details'
            : due && signedIn ? `Pay ${due.value.toFixed(2)} ${due.unit} & checkout`
            : 'Checkout'}
        </button>
      </div>
    </>
  );
}
