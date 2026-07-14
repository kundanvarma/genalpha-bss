import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { beacon, consentChoice, getOffering, listOfferings, myExperience, myRecommendations, priceIndex, saveConsent } from '../api.js';
import { isSignedIn } from '../auth.js';
import { fmtMonthly, fmtPrice, monthlyTotal, pricesOf } from '../money.js';
import { t } from '../i18n.js';

export default function Shop() {
  const [offerings, setOfferings] = useState(null);
  const [prices, setPrices] = useState({});
  const [recommended, setRecommended] = useState([]);
  const [experience, setExperience] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.all([listOfferings(), priceIndex()])
      .then(([o, p]) => { setOfferings(o); setPrices(p); })
      .catch((e) => setError(e.message));
    // the insight question is additive too: no consent, default page
    myExperience().then(setExperience).catch(() => {});
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
  let singles = offerings.filter((o) => !o.isBundle);
  const picks = recommended.map((id) => offerings.find((o) => o.id === id)).filter(Boolean);

  // personalization, honest and gentle: what they looked at leads; an
  // operator experience rule can pin one offering on top of that
  const hero = experience?.personalized ? experience.heroCategory : null;
  const catOf = (o) => ((o.category || [])[0] || {}).name || '';
  if (hero) {
    singles = [...singles].sort((a, b) => (catOf(b) === hero) - (catOf(a) === hero));
  }
  const pinned = experience?.teaserOfferingId
    ? offerings.find((o) => o.id === experience.teaserOfferingId) : null;

  const brand = window.BSS_STOREFRONT_CONFIG || {};
  return (
    <>
      <section className="hero">
        <h1>{brand.brandName || 'Welcome'}</h1>
        <p>Mobile, broadband and TV that just work together. Pick a bundle, keep your number, and be live in minutes.</p>
      </section>
      <ConsentBanner onDecided={() => myExperience().then(setExperience).catch(() => {})} />
      {hero && (
        <p className="dim" data-testid="personal-banner" style={{ margin: '4px 0' }}>
          ✨ {experience.banner || `${t('Because you were looking at')} ${hero}`}
        </p>
      )}
      {pinned && (
        <div className="cards" data-testid="personal-pick">
          <OfferingCard key={'pin-' + pinned.id} offering={pinned} prices={prices} />
        </div>
      )}
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

/**
 * The consent choice, honestly presented: decline exactly as prominent as
 * accept, nothing collected before the answer — and a decline means the
 * insight component holds NOTHING about this browser.
 */
function ConsentBanner({ onDecided }) {
  const [answered, setAnswered] = useState(Boolean(consentChoice()));
  const decide = async (yes) => {
    await saveConsent(yes, yes);
    setAnswered(true);
    if (yes) {
      beacon('page', null, null); // the visit itself, now that we may
      onDecided();
    }
  };
  // changing your mind must be as easy as consenting was
  if (answered) {
    return (
      <p style={{ margin: '2px 0' }}>
        <button className="ghost" data-testid="consent-reopen"
          style={{ fontSize: 12, padding: '2px 8px' }}
          onClick={() => setAnswered(false)}>
          {t('Privacy choices')}
        </button>
      </p>
    );
  }
  // deliberately LOUD: the brand accent frames the one question we must
  // never sneak past anyone — both answers equally prominent
  return (
    <section className="lobcard" data-testid="consent-banner" style={{
      padding: '14px 18px',
      border: '2px solid var(--teal)',
      background: 'var(--teal-soft, rgba(69,175,172,.12))',
      boxShadow: '0 6px 22px rgba(0,0,0,.12)' }}>
      <p style={{ margin: '0 0 10px', fontSize: 15, fontWeight: 600 }}>
        🍪 {t('May we use your browsing here to personalize offers?')}
      </p>
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 13 }}>
        {t('First-party only, deleted on decline — your choice either way.')}
      </p>
      <div style={{ display: 'flex', gap: 10, maxWidth: 460 }}>
        <button className="primary" data-testid="consent-accept" style={{ flex: 1 }}
          onClick={() => decide(true)}>{t('Yes, personalize')}</button>
        <button className="primary" data-testid="consent-reject" style={{ flex: 1 }}
          onClick={() => decide(false)}>{t('No thanks')}</button>
      </div>
    </section>
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
          ? <strong>{t('from')} {fmtMonthly(fromMonthly)}</strong>
          : monthly
            ? <strong>{fmtMonthly(monthly)}</strong>
            : own.length > 0 && <strong>{fmtPrice(own[0])}</strong>}
      </div>
    </Link>
  );
}
