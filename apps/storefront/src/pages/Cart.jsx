import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { checkoutCart, getOffering, priceIndex } from '../api.js';
import { beginLogin, isSignedIn } from '../auth.js';
import { CART_EVENT, cartLines, clearCart, removeLine, setQuantity } from '../cart.js';
import { monthlyTotal, pricesOf } from '../money.js';
import { setPendingCheckout } from '../pending.js';

export default function Cart() {
  const navigate = useNavigate();
  const [lines, setLines] = useState(cartLines());
  const [offerings, setOfferings] = useState({}); // offering id -> full offering
  const [prices, setPrices] = useState({});
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const refresh = () => setLines(cartLines());
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    const ids = [...new Set(lines.flatMap((l) => [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))]
      .filter((id) => !offerings[id]);
    Promise.all([ids.length ? Promise.all(ids.map(getOffering)) : [], priceIndex()])
      .then(([loaded, p]) => {
        setPrices(p);
        if (loaded.length) {
          setOfferings((o) => ({ ...o, ...Object.fromEntries(loaded.map((f) => [f.id, f])) }));
        }
      })
      .catch((e) => setError(e.message));
  }, [lines]);

  if (!lines.length) {
    return <p className="dim">Your cart is empty — <Link to="/">browse the offers</Link>.</p>;
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
      // The cart survives the redirect in localStorage; this flag makes the
      // checkout resume automatically after registration or sign-in.
      setPendingCheckout();
      await beginLogin();
      return;
    }
    setBusy(true);
    try {
      await checkoutCart(lines);
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
      </div>
      <div className="cartactions">
        <Link to="/" className="dim">Continue shopping</Link>
        <button className="primary big" onClick={checkout} disabled={busy}>
          {busy ? 'Placing order…' : 'Checkout'}
        </button>
      </div>
    </>
  );
}
