import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { getOffering, placeOrder, priceIndex } from '../api.js';
import { fmtPrice, monthlyTotal, pricesOf } from '../money.js';

export default function Offering() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [offering, setOffering] = useState(null);
  const [prices, setPrices] = useState({});
  const [error, setError] = useState(null);
  const [ordering, setOrdering] = useState(false);

  useEffect(() => {
    Promise.all([getOffering(id), priceIndex()])
      .then(([o, p]) => { setOffering(o); setPrices(p); })
      .catch((e) => setError(e.message));
  }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!offering) return <p className="dim">Loading…</p>;

  const own = pricesOf(offering, prices);
  const monthly = monthlyTotal(own);

  async function order() {
    setOrdering(true);
    try {
      await placeOrder(offering);
      navigate('/orders');
    } catch (e) {
      setError(e.message);
      setOrdering(false);
    }
  }

  return (
    <div className="detail">
      {offering.isBundle && <span className="tag">Bundle</span>}
      <h1>{offering.name}</h1>
      <p>{offering.description}</p>

      {offering.isBundle && (
        <>
          <h2>What's included</h2>
          <ul className="includes big">
            {(offering.bundledProductOffering || []).map((c) => <li key={c.id}>{c.name}</li>)}
          </ul>
        </>
      )}

      {own.length > 0 && (
        <>
          <h2>Pricing</h2>
          <table className="pricetable">
            <tbody>
              {own.map((p) => (
                <tr key={p.id}>
                  <td>{p.name}</td>
                  <td className="num">{fmtPrice(p)}</td>
                </tr>
              ))}
              {monthly && (
                <tr className="total">
                  <td>Total per month</td>
                  <td className="num">{monthly.value.toFixed(2)} {monthly.unit}</td>
                </tr>
              )}
            </tbody>
          </table>
        </>
      )}

      <button className="primary big" onClick={order} disabled={ordering}>
        {ordering ? 'Placing order…' : 'Order now'}
      </button>
    </div>
  );
}
