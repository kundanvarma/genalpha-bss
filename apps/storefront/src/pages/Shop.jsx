import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { listOfferings, priceIndex } from '../api.js';
import { fmtPrice, monthlyTotal, pricesOf } from '../money.js';

export default function Shop() {
  const [offerings, setOfferings] = useState(null);
  const [prices, setPrices] = useState({});
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.all([listOfferings(), priceIndex()])
      .then(([o, p]) => { setOfferings(o); setPrices(p); })
      .catch((e) => setError(e.message));
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!offerings) return <p className="dim">Loading offers…</p>;

  const bundles = offerings.filter((o) => o.isBundle);
  const singles = offerings.filter((o) => !o.isBundle);

  return (
    <>
      {bundles.length > 0 && <h1>Bundles</h1>}
      <div className="cards">
        {bundles.map((o) => <OfferingCard key={o.id} offering={o} prices={prices} />)}
      </div>
      <h1>All offers</h1>
      <div className="cards">
        {singles.map((o) => <OfferingCard key={o.id} offering={o} prices={prices} />)}
      </div>
    </>
  );
}

function OfferingCard({ offering, prices }) {
  const own = pricesOf(offering, prices);
  const monthly = monthlyTotal(own);
  return (
    <Link className={offering.isBundle ? 'card bundle' : 'card'} to={`/offering/${offering.id}`}>
      {offering.isBundle && <span className="tag">Bundle</span>}
      <h2>{offering.name}</h2>
      <p className="dim">{offering.description}</p>
      {offering.isBundle && (
        <ul className="includes">
          {(offering.bundledProductOffering || []).map((c) => <li key={c.id}>{c.name}</li>)}
        </ul>
      )}
      <div className="pricing">
        {monthly
          ? <strong>{monthly.value.toFixed(2)} {monthly.unit}/month</strong>
          : own.length > 0 && <strong>{fmtPrice(own[0])}</strong>}
      </div>
    </Link>
  );
}
