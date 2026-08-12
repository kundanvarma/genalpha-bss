import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { beacon, consentChoice, forYou, getOffering, getSpec, listOfferings, myExperience, myRecommendations, priceIndex, saveConsent, submitSalesLead } from '../api.js';
import { CART_EVENT, cartLines } from '../cart.js';
import { isSignedIn } from '../auth.js';
import { fmtMonthly, fmtPrice, monthlyTotal, pricesOf } from '../money.js';
import { t } from '../i18n.js';

export default function Shop() {
  const [offerings, setOfferings] = useState(null);
  const [prices, setPrices] = useState({});
  const [recommended, setRecommended] = useState([]);
  const [personal, setPersonal] = useState(null);
  const [experience, setExperience] = useState(null);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState('Bundles'); // line-of-business shop tab
  const [planSort, setPlanSort] = useState('data');   // Mobile: compare by data|price
  const [planView, setPlanView] = useState('cards');  // Mobile: 'cards' | 'table' (compare is opt-in)
  const [deviceBrand, setDeviceBrand] = useState('All'); // Devices: brand filter
  const [planSpecs, setPlanSpecs] = useState({}); // offeringId -> {charName: value}
  const [inCart, setInCart] = useState(new Set()); // ids already in the cart

  // "Recommended for you" must not recommend what's already in the cart —
  // track the cart's offering ids (lines + selections), live across changes.
  useEffect(() => {
    if (!isSignedIn()) return undefined;
    const refresh = () => cartLines()
      .then((ls) => setInCart(new Set((ls || []).flatMap((l) =>
        [l.offeringId, ...(l.selections || []).map((s) => s.offeringId)]))))
      .catch(() => setInCart(new Set()));
    refresh();
    window.addEventListener(CART_EVENT, refresh);
    return () => window.removeEventListener(CART_EVENT, refresh);
  }, []);

  useEffect(() => {
    Promise.all([listOfferings(), priceIndex()])
      .then(([o, p]) => { setOfferings(o); setPrices(p); })
      .catch((e) => setError(e.message));
    // the insight question is additive too: no consent, default page
    myExperience().then(setExperience).catch(() => {});
    // the individualized rail is additive: without it (or without the
    // intelligence component) the raw TMF680 picks still render
    if (isSignedIn()) {
      forYou().then((fy) => {
        if (fy && fy.items?.length) {
          setPersonal(fy);
          setRecommended(fy.items.map((i) => i.id));
        } else {
          return myRecommendations()
            .then((recs) => setRecommended(recs[0]?.recommendationItem?.map((i) => i.offering.id) || []));
        }
      }).catch(() => {});
    }
  }, []);

  // Load each mobile plan's spec characteristics so the comparison table shows
  // real per-plan values (Data / Network / EU roaming / Calls & texts).
  useEffect(() => {
    if (!offerings) return;
    const plans = offerings.filter((o) =>
      ((o.category || [])[0] || {}).name === 'Mobile plans' && o.productSpecification?.id);
    Promise.all(plans.map(async (o) => {
      const spec = await getSpec(o.productSpecification.id).catch(() => null);
      const chars = {};
      for (const c of (spec?.productSpecCharacteristic || [])) {
        const v = (c.productSpecCharacteristicValue || [])[0]?.value;
        if (v != null) chars[c.name] = v;
      }
      return [o.id, chars];
    })).then((entries) => setPlanSpecs(Object.fromEntries(entries))).catch(() => {});
  }, [offerings]);

  if (error) return <p className="error">{error}</p>;
  if (!offerings) return <p className="dim">Loading offers…</p>;

  const bundles = offerings.filter((o) => o.isBundle);
  let singles = offerings.filter((o) => !o.isBundle);
  const picks = recommended.map((id) => offerings.find((o) => o.id === id))
    .filter(Boolean)
    .filter((o) => !inCart.has(o.id)); // never recommend what's already in the cart

  // personalization, honest and gentle: what they looked at leads; an
  // operator experience rule can pin one offering on top of that
  const hero = experience?.personalized ? experience.heroCategory : null;
  const catOf = (o) => ((o.category || [])[0] || {}).name || '';
  // the FULL interest profile ranks the grid (they consented to exactly
  // this); the single hero — a rule's override — still floats to the top
  const interestRank = (o) => {
    const list = experience?.personalized ? (experience.interests || []) : [];
    const i = list.indexOf(catOf(o));
    return i === -1 ? list.length : i;
  };
  if (experience?.personalized && (experience.interests || []).length) {
    singles = [...singles].sort((a, b) => interestRank(a) - interestRank(b));
  }
  if (hero) {
    singles = [...singles].sort((a, b) => (catOf(b) === hero) - (catOf(a) === hero));
  }
  const pinned = experience?.teaserOfferingId
    ? offerings.find((o) => o.id === experience.teaserOfferingId) : null;

  // NEXT-HIT: the offerings they just looked at, most recent first —
  // "pick up where you left off", the immediate-session rail
  const recent = (experience?.recentOfferings || [])
    .map((id) => offerings.find((o) => o.id === id)).filter(Boolean);

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
      {recent.length > 0 && (
        <>
          <h1>{t('Pick up where you left off')}</h1>
          <div className="cards" data-testid="recently-viewed">
            {recent.map((o) => <OfferingCard key={'recent-' + o.id} offering={o} prices={prices} />)}
          </div>
        </>
      )}
      {personal?.upsell && (
        <div className="lobcard" data-testid="upsell-card" style={{ margin: '10px 0', padding: '12px 16px' }}>
          <strong>
            {t('You\'ve used')} {personal.upsell.usedValue} {t('of your')}{' '}
            {personal.upsell.currentAllowance} {personal.upsell.units} {personal.upsell.bucketName}
          </strong>
          <p className="dim" style={{ margin: '4px 0' }}>
            {personal.upsell.suggestedOffering.name} {t('gives you')}{' '}
            {personal.upsell.suggestedAllowance} {personal.upsell.units} —{' '}
            <Link to={'/offering/' + personal.upsell.suggestedOffering.id}>{t('take a look')}</Link>
          </p>
        </div>
      )}
      {picks.length > 0 && (
        <>
          <h1>{t('Recommended for you')}</h1>
          {personal?.caption && (
            <p className="dim" data-testid="foryou-caption" style={{ margin: '4px 0' }}>
              ✨ {personal.caption}
            </p>
          )}
          {personal?.retentionFlag && (
            <p className="dim" data-testid="retention-banner" style={{ margin: '4px 0' }}>
              💙 {t('Thanks for being with us — this shelf includes our best loyalty picks.')}
            </p>
          )}
          <div className="cards" data-testid="recommended">
            {picks.map((o) => <OfferingCard key={'rec-' + o.id} offering={o} prices={prices} />)}
          </div>
        </>
      )}
      {(() => {
        // Shop by line of business — tabs, the way a telco storefront is laid
        // out (Mobile · Internet · TV · Devices · Security · Bundles), instead
        // of one long "all offers" scroll.
        const isFamily = (o) => /family|kids/i.test(`${o.name || ''} ${o.description || ''}`);
        const LOB = [
          { key: 'Bundles', label: t('Bundles'), items: bundles },
          { key: 'Family', label: t('Family'), items: offerings.filter(isFamily) },
          { key: 'Mobile', label: t('Mobile'), items: singles.filter((o) => catOf(o) === 'Mobile plans') },
          { key: 'Internet', label: t('Internet'), items: singles.filter((o) => catOf(o) === 'Broadband') },
          { key: 'TV', label: t('TV & Streaming'), items: singles.filter((o) => ['TV & Add-ons', 'Partner services'].includes(catOf(o))) },
          { key: 'Devices', label: t('Devices'), items: singles.filter((o) => catOf(o) === 'Devices') },
          { key: 'Security', label: t('Security'), items: singles.filter((o) => ['Security', 'Insurance'].includes(catOf(o))) },
          { key: 'Top-ups', label: t('Top-ups'), items: singles.filter((o) => catOf(o) === 'Top-ups') },
        ].filter((l) => l.items.length);
        if (!LOB.length) return <TalkToSales />;
        const active = LOB.find((l) => l.key === tab) || LOB[0];

        // Baymard telco UX: compare plans (data/price) and filter the device shop.
        // Comparison values come from each plan's SPEC characteristics (real data),
        // falling back to the name only when a spec hasn't loaded.
        const monthlyOf = (o) => monthlyTotal(pricesOf(o, prices))?.value ?? Infinity;
        const specOf = (o) => planSpecs[o.id] || {};
        const dataText = (o) => specOf(o).Data
          || ((o.name || '').match(/(\d+\s*GB)/i)?.[1]) || (/unlimited/i.test(o.name || '') ? t('Unlimited') : '—');
        const dataRank = (o) => {
          const s = dataText(o);
          if (/unlimited/i.test(s)) return Infinity;
          const m = String(s).match(/(\d+)/);
          return m ? Number(m[1]) : 0;
        };
        const brandOf = (o) => (o.name || '').split(' ')[0];

        let items = active.items;
        let controls = null;
        const mobileTable = active.key === 'Mobile' && planView === 'table';
        if (active.key === 'Mobile') {
          items = [...items].sort((a, b) => planSort === 'price'
            ? monthlyOf(a) - monthlyOf(b) : dataRank(b) - dataRank(a));
          controls = (
            <div className="shopfilter" data-testid="plan-sort">
              <span className="dim">{t('Sort')}:</span>
              {['data', 'price'].map((s) => (
                <button key={s} type="button" className={`chip ${planSort === s ? 'on' : ''}`}
                  onClick={() => setPlanSort(s)}>{s === 'data' ? t('Most data') : t('Lowest price')}</button>
              ))}
              <span className="dim" style={{ marginLeft: 8 }}>{t('View')}:</span>
              {['table', 'cards'].map((v) => (
                <button key={v} type="button" className={`chip ${planView === v ? 'on' : ''}`}
                  onClick={() => setPlanView(v)}>{v === 'table' ? t('Compare') : t('Cards')}</button>
              ))}
            </div>
          );
        } else if (active.key === 'Devices') {
          const brands = ['All', ...Array.from(new Set(active.items.map(brandOf)))];
          items = active.items
            .filter((o) => deviceBrand === 'All' || brandOf(o) === deviceBrand)
            .sort((a, b) => monthlyOf(a) - monthlyOf(b));
          controls = (
            <div className="shopfilter" data-testid="device-filter">
              <span className="dim">{t('Brand')}:</span>
              {brands.map((b) => (
                <button key={b} type="button" className={`chip ${deviceBrand === b ? 'on' : ''}`}
                  onClick={() => setDeviceBrand(b)}>{b === 'All' ? t('All') : b}</button>
              ))}
            </div>
          );
        } else if (active.key === 'Family') {
          // family bundle first, then the family-friendly add-ons
          items = [...active.items].sort((a, b) => (b.isBundle ? 1 : 0) - (a.isBundle ? 1 : 0));
          controls = (
            <p className="dim familyintro" data-testid="family-intro" style={{ margin: '0 0 12px' }}>
              👨‍👩‍👧‍👦 {t('Everything the household needs on one bill — pick your lines, phones and extras.')}
            </p>
          );
        }
        return (
          <>
            <nav className="shoptabs" data-testid="shop-tabs">
              {LOB.map((l) => (
                <button key={l.key} type="button"
                  className={`shoptab ${active.key === l.key ? 'on' : ''}`}
                  onClick={() => setTab(l.key)}>{l.label}</button>
              ))}
            </nav>
            {controls}
            {mobileTable ? (
              <div className="comparewrap" data-testid="plan-compare">
                <table className="comparetable">
                  <thead>
                    <tr>
                      <th className="feat" />
                      {items.map((o) => (
                        <th key={o.id}>
                          <div className="planname">{o.name.replace(/^GenAlpha /, '')}</div>
                          <div className="planprice">
                            {monthlyOf(o) !== Infinity
                              ? <><strong>{fmtMonthly({ value: monthlyOf(o), unit: 'EUR' })}</strong></>
                              : <span className="dim">—</span>}
                          </div>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td className="feat">{t('Data')}</td>
                      {items.map((o) => <td key={o.id} className="hero">{dataText(o)}</td>)}
                    </tr>
                    <tr>
                      <td className="feat">{t('Network')}</td>
                      {items.map((o) => <td key={o.id}>{specOf(o).Network || '—'}</td>)}
                    </tr>
                    <tr>
                      <td className="feat">{t('Calls & texts')}</td>
                      {items.map((o) => <td key={o.id}>{specOf(o)['Calls & texts'] || t('Unlimited')}</td>)}
                    </tr>
                    <tr>
                      <td className="feat">{t('EU roaming')}</td>
                      {items.map((o) => {
                        const r = specOf(o)['EU roaming'];
                        return <td key={o.id}>{r === 'Included'
                          ? <span className="yes">✓ {t('Included')}</span>
                          : (r ? <span className="dim">{r}</span> : '—')}</td>;
                      })}
                    </tr>
                    <tr className="choose">
                      <td className="feat" />
                      {items.map((o) => (
                        <td key={o.id}>
                          <Link className="btn" to={`/offering/${o.id}`}>{t('Choose')}</Link>
                        </td>
                      ))}
                    </tr>
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="cards" data-testid={`lob-${active.key}`}>
                {items.map((o) => <OfferingCard key={o.id} offering={o} prices={prices} />)}
              </div>
            )}
          </>
        );
      })()}
      <TalkToSales />
    </>
  );
}

/**
 * TMF699 at the edge: a business prospect isn't a customer yet — no
 * account, no cart. This mini-form mints a salesLead; sales works it in
 * the console (qualify → opportunity → quote).
 */
function TalkToSales() {
  const [sent, setSent] = useState(false);
  const [error, setError] = useState(null);
  const submit = async (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    try {
      await submitSalesLead({
        name: f.get('need'), contactName: f.get('who'), contactEmail: f.get('email'),
        company: f.get('company'), source: 'storefront',
      });
      setSent(true);
    } catch (err) { setError(err.message); }
  };
  if (sent) {
    return (
      <section className="lobcard" data-testid="sales-thanks" style={{ marginTop: 24 }}>
        <p style={{ margin: 0 }}>✅ {t('Thanks — our sales team will be in touch shortly.')}</p>
      </section>
    );
  }
  return (
    <section className="lobcard" data-testid="talk-to-sales" style={{ marginTop: 24 }}>
      <h2 style={{ marginTop: 0 }}>{t('Something bigger in mind?')}</h2>
      <p className="dim" style={{ marginTop: 0 }}>
        {t('Fleets, offices, IoT — tell us what you need and sales will call you back.')}
      </p>
      <form onSubmit={submit} style={{ display: 'grid', gap: 8, maxWidth: 460 }}>
        <input name="who" placeholder={t('Your name')} required data-testid="sales-name" />
        <input name="email" type="email" placeholder={t('Work email')} required data-testid="sales-email" />
        <input name="company" placeholder={t('Company')} data-testid="sales-company" />
        <textarea name="need" rows="2" required data-testid="sales-need"
          placeholder={t('What do you need? e.g. 40 SIMs for delivery vans')} />
        <button className="primary" type="submit" data-testid="sales-submit">
          {t('Talk to sales')}
        </button>
      </form>
      {error && <p className="error">{error}</p>}
    </section>
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
