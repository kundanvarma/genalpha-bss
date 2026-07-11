import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getOffering, listOfferings, myRecommendations, priceIndex } from '../api.js';
import { isSignedIn } from '../auth.js';
import { fmtPrice, monthlyTotal, pricesOf } from '../money.js';

export default function Shop() {
  const [offerings, setOfferings] = useState(null);
  const [prices, setPrices] = useState({});
  const [recommended, setRecommended] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.all([listOfferings(), priceIndex()])
      .then(([o, p]) => { setOfferings(o); setPrices(p); })
      .catch((e) => setError(e.message));
    // TMF680 is additive: the shop renders fine without it.
    if (isSignedIn()) {
      myRecommendations()
        .then((recs) => setRecommended(recs[0]?.recommendationItem?.map((i) => i.offering.id) || []))
        .catch(() => {});
    }
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!offerings) return <p className="dim">Loading offers…</p>;

  const bundles = offerings.filter((o) => o.isBundle);
  const singles = offerings.filter((o) => !o.isBundle);
  const picks = recommended.map((id) => offerings.find((o) => o.id === id)).filter(Boolean);

  return (
    <>
      {picks.length > 0 && (
        <>
          <h1>Recommended for you</h1>
          <div className="cards" data-testid="recommended">
            {picks.map((o) => <OfferingCard key={'rec-' + o.id} offering={o} prices={prices} />)}
          </div>
        </>
      )}
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
  const bundled = offering.bundledProductOffering || [];
  const choices = bundled.filter((e) => Array.isArray(e.options));
  const [fromMonthly, setFromMonthly] = useState(null);

  // A configurable bundle advertises "from": fixed charges + cheapest option.
  useEffect(() => {
    if (!choices.length || !monthly) return;
    Promise.all(choices.map(async (choice) => {
      const optionMonthlies = await Promise.all(choice.options.map(async (opt) => {
        const full = await getOffering(opt.id);
        return monthlyTotal(pricesOf(full, prices))?.value ?? 0;
      }));
      return Math.min(...optionMonthlies);
    })).then((cheapest) => {
      setFromMonthly({ value: monthly.value + cheapest.reduce((a, b) => a + b, 0), unit: monthly.unit });
    }).catch(() => {});
  }, [offering.id, prices]);

  return (
    <Link className={offering.isBundle ? 'card bundle' : 'card'} to={`/offering/${offering.id}`}>
      {offering.attachment?.[0]?.url && (
        <img className="offerart" src={offering.attachment[0].url} alt=""
             onError={(e) => { e.currentTarget.style.display = 'none'; }} />
      )}
      {offering.isBundle && <span className="tag">Bundle</span>}
      <h2>{offering.name}</h2>
      <p className="dim">{offering.description}</p>
      {offering.isBundle && (
        <ul className="includes">
          {bundled.map((c) => <li key={c.id || c.name}>{c.name}</li>)}
        </ul>
      )}
      <div className="pricing">
        {choices.length && fromMonthly
          ? <strong>from {fromMonthly.value.toFixed(2)} {fromMonthly.unit}/month</strong>
          : monthly
            ? <strong>{monthly.value.toFixed(2)} {monthly.unit}/month</strong>
            : own.length > 0 && <strong>{fmtPrice(own[0])}</strong>}
      </div>
    </Link>
  );
}
