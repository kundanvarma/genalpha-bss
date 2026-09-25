import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { cancelMyService, enrollLoyalty, loyaltyProgram, myLoyalty, redeemLoyaltyData, redeemLoyaltyVoucher, listOfferings, myActiveServices, myBills, myOrders, myProducts, myRecommendations, myUsage, pauseMyService, priceIndex, resumeMyService, myHousehold } from '../api.js';
import UsageControls from './UsageControls.jsx';
import { tokenClaims } from '../auth.js';
import { fmtPrice, pricesOf } from '../money.js';
import { locale, money as intlMoney, t } from '../i18n.js';
import { SimCard, UsageMeter, LineDoctor, SliceBadge } from './services/LineCards.jsx';
export { LineDoctor } from './services/LineCards.jsx';
import { categoryOf, bundleChildIds, ProductRow, PlanChangeNotices } from './services/PlanChange.jsx';
import { TopUp, GiftData, ReferralCard } from './services/Extras.jsx';
import RouterPanel from './RouterPanel.jsx';

export default function Services() {
  const [loyalty, setLoyalty] = useState(null);
  const [loyaltyProg, setLoyaltyProg] = useState(null);
  const [loyaltyMsg, setLoyaltyMsg] = useState('');
  useEffect(() => {
    loyaltyProgram().then(setLoyaltyProg).catch(() => {});
    myLoyalty().then(setLoyalty).catch(() => {});
  }, []);
  const [products, setProducts] = useState(null);
  const [services, setServices] = useState([]);
  const [buckets, setBuckets] = useState([]);
  const [offerings, setOfferings] = useState({});
  const [prices, setPrices] = useState({});
  const [changed, setChanged] = useState(null);
  const [bills, setBills] = useState([]);
  const [recIds, setRecIds] = useState([]);
  const [orders, setOrders] = useState([]);
  const [error, setError] = useState(null);

  const [hh, setHh] = useState(null);
  // "Review services" lands on the paused section: scroll there once the list is in
  const hash = useLocation().hash;
  const pausedCount = services.filter((sv) => sv.state === 'suspended').length;
  useEffect(() => {
    if (hash === '#paused') setTimeout(() => document.getElementById('paused')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 300);
  }, [hash, pausedCount]);
  function refresh() {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    myActiveServices().then(setServices).catch(() => {});
    // Usage and bills are additive: the page renders without them.
    myUsage().then((report) => setBuckets(report.bucket || [])).catch(() => {});
    myBills().then(setBills).catch(() => {});
    // In-flight orders: what's bought-but-not-yet-provisioned, so discovery
    // never recommends something the customer just ordered.
    myOrders().then(setOrders).catch(() => {});
  }
  useEffect(() => {
    refresh();
    myHousehold().then(setHh).catch(() => {});
    listOfferings().then((all) =>
      setOfferings(Object.fromEntries(all.map((o) => [o.id, o])))).catch(() => {});
    priceIndex().then(setPrices).catch(() => {});
    // TMF680 drives discovery; the category-gap links are the fallback
    myRecommendations()
      .then((recs) => setRecIds(recs[0]?.recommendationItem?.map((i) => i.offering.id) || []))
      .catch(() => {});
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!products) return <p className="dim">{t('Loading your page…')}</p>;
  if (!products.length) {
    return (
      <>
        <p className="dim">Nothing active yet — your plan appears here once an order completes.</p>
        <p><Link to="/family" data-testid="family-hub-link">👪 {t('Family')} →</Link></p>
      </>
    );
  }

  // The MyJio idea: the page RECOMPOSES around what this customer holds —
  // one dashboard card per line of business, discovery for what they lack.
  // WHOSE is whose: products I merely PAY for (a dependent's plan reaches
  // this list through the payer stamp) split into their own family section.
  const me = tokenClaims().sub;
  const ownerIdOf = (p) => (p.relatedParty || []).find((x) => x.role === 'customer')?.id;
  const payerPartyOf = (p) => (p.relatedParty || []).find((x) => x.role === 'payer');
  const familyPaid = products.filter((p) => ownerIdOf(p) && ownerIdOf(p) !== me);
  const own = products.filter((p) => !familyPaid.includes(p));
  const onChanged = (name) => { setChanged(name); refresh(); };
  const catOfProduct = (p) => categoryOf(offerings[p.productOffering?.id]);
  const bundles = own.filter((p) => offerings[p.productOffering?.id]?.isBundle);
  const claimed = new Set(bundles.map((p) => p.id));
  const componentsOf = (bundleProduct) => {
    const childIds = bundleChildIds(offerings[bundleProduct.productOffering?.id]);
    return own.filter((p) => !claimed.has(p.id) && childIds.has(p.productOffering?.id)
      && (claimed.add(p.id), true));
  };
  const bundleGroups = bundles.map((b) => ({ bundle: b, components: componentsOf(b) }));
  const standalone = own.filter((p) => !claimed.has(p.id));
  const byCat = (cat) => standalone.filter((p) => catOfProduct(p) === cat);
  const mobilePlans = byCat('Mobile plans');
  const broadband = byCat('Broadband');
  const entertainment = byCat('TV & Add-ons');
  const devices = byCat('Devices');
  const other = standalone.filter((p) => ![...mobilePlans, ...broadband, ...entertainment, ...devices].includes(p)
    && catOfProduct(p) !== 'Top-ups');

  // EVERY numbered line gets its own row + SIM — families have several
  // (a PAUSED line stays visible: it is still yours, just sleeping)
  const lines = services.filter((sv) => ['active', 'suspended'].includes(sv.state)
    && (sv.supportingResource || []).some((r) => r.value));
  // partner entitlements carry an activationCode characteristic, never a number
  const activationOf = (sv) => (sv.serviceCharacteristic || [])
    .find((c) => c.name === 'activationCode')?.value;
  const entitlements = services.filter((sv) => sv.state === 'active' && activationOf(sv));
  const features = services.filter((sv) => sv.state === 'active' && !activationOf(sv)
    && !(sv.supportingResource || []).some((r) => r.value)
    && categoryOf(offerings[products.find((p) => p.name === sv.name)?.productOffering?.id]) === 'Security');
  const numberOf = (sv) => sv.supportingResource.find((r) => r.value).value;
  const number = lines.length > 0;
  const fmtAmount = (a) => (locale === 'en'
    ? `${a.value.toFixed(2)} ${a.unit}` : intlMoney(a.value, a.unit));
  const latestBill = [...bills].sort((a, b) =>
    String(b.billDate || b.billNo).localeCompare(String(a.billDate || a.billNo)))[0];
  const dataBuckets = buckets.filter((b) => b.name === 'Mobile data');
  // zone-tagged buckets are travel-pass meters — they read in Usage controls
  const zoneBuckets = buckets.filter((b) => b.zone);
  const otherBuckets = buckets.filter((b) => b.name !== 'Mobile data' && !b.zone);
  const topUps = Object.values(offerings).filter((o) => categoryOf(o) === 'Top-ups');
  const ownsMobile = mobilePlans.length > 0 || bundleGroups.length > 0 || Boolean(number);

  // Discovery points at what's MISSING — it must never resell a line of business
  // the customer already holds. Two exclusions:
  //   1. exact offerings they OWN or have just ORDERED (still provisioning), and
  //   2. every CATEGORY they already hold — standalone, covered by a BUNDLE, or
  //      bought in an in-flight order. Without the category rule a bundle that
  //      includes mobile still lets discovery recommend a standalone mobile plan,
  //      because the bundle's child offering ids differ from the plan's.
  const ownedOfferingIds = new Set(products.map((p) => p.productOffering?.id));
  const heldCategories = new Set();
  if (ownsMobile) heldCategories.add('Mobile plans');
  if (broadband.length || bundleGroups.length) heldCategories.add('Broadband');
  if (entertainment.length || bundleGroups.length) heldCategories.add('TV & Add-ons');
  if (devices.length) heldCategories.add('Devices');
  for (const { bundle } of bundleGroups) {
    for (const id of bundleChildIds(offerings[bundle.productOffering?.id])) {
      const cat = categoryOf(offerings[id]);
      if (cat) heldCategories.add(cat);
    }
  }
  for (const o of orders) {
    if (['cancelled', 'rejected'].includes(o.state)) continue;
    for (const it of (o.productOrderItem || [])) {
      if (it.productOffering?.id) ownedOfferingIds.add(it.productOffering.id);
      const cat = categoryOf(offerings[it.productOffering?.id]);
      if (cat) heldCategories.add(cat);
    }
  }
  const recOffers = recIds.map((id) => offerings[id])
    .filter((o) => o && !ownedOfferingIds.has(o.id) && !o.requiresVerifiedIdentity
      && categoryOf(o) !== 'Top-ups' // the top-up has its own button on the Mobile card
      && !heldCategories.has(categoryOf(o)))
    .slice(0, 3);

  const rowsOf = (list) => list.map((p) => (
    <ProductRow key={p.id} product={p} services={services}
      offerings={offerings} prices={prices} onChanged={onChanged} />
  ));

  // discovery: the lines of business this customer does NOT have yet
  const missing = [
    !ownsMobile && { label: t('Add a mobile plan'), cat: 'Mobile plans' },
    !broadband.length && !bundleGroups.length && { label: t('Add broadband'), cat: 'Broadband' },
    !entertainment.length && !bundleGroups.length && { label: t('Add TV & streaming'), cat: 'TV & Add-ons' },
  ].filter(Boolean);

  const pausedAll = services.filter((sv) => sv.state === 'suspended');
  const numberOrNull = (sv) => ((sv.supportingResource || []).find((r) => r.value) || {}).value || null;
  return (
    <>
      <h1>{t('Services')}</h1>
      <p className="dim small quicklinks"><Link to="/devices">{t('My devices')} →</Link> · <Link to="/family">{t('Family')} →</Link></p>
      {pausedAll.length > 0 && (
        <section className="card" id="paused" data-testid="paused-card" style={{ padding: '14px 18px', marginBottom: 14, borderColor: 'var(--danger)' }}>
          <h2 style={{ marginTop: 0 }}>⏸ {pausedAll.length === 1 ? t('One service is paused') : `${pausedAll.length} ${t('services are paused')}`}</h2>
          <p className="dim small">{t('Nothing is charged and nothing connects while a service is paused. Resume the ones you want back.')}</p>
          {pausedAll.map((sv) => (
            <div key={sv.id} className="row" data-testid="paused-row">
              <span>{sv.name}{numberOrNull(sv) ? <span className="dim"> · {numberOrNull(sv)}</span> : null}</span>
              <button className="primary" data-testid="resume-paused" onClick={async () => { try { await resumeMyService(sv.id); refresh(); } catch { /* stays paused */ } }}>{t('Resume now')}</button>
            </div>
          ))}
          {pausedAll.length > 1 && (
            <button className="ghost" data-testid="resume-all" style={{ marginTop: 8 }} onClick={async () => { for (const sv of pausedAll) { try { await resumeMyService(sv.id); } catch { /* next */ } } refresh(); }}>{t('Resume all')}</button>
          )}
        </section>
      )}
      {changed && (
        <p className="dim" data-testid="plan-changed">
          ✓ Plan changed to <strong style={{ color: 'var(--teal)' }}>{changed}</strong> — you keep your number.
        </p>
      )}
      <PlanChangeNotices />

      {bundleGroups.map(({ bundle, components }) => (
        <section className="card" key={bundle.id} data-testid={`bundle-${bundle.id}`}
          style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('My bundle')}</h2>
          <ProductRow product={bundle} services={services}
            offerings={offerings} prices={prices} onChanged={onChanged} />
          {components.map((c) => (
            <ProductRow key={c.id} product={c} services={services} nested
              offerings={offerings} prices={prices} onChanged={onChanged} />
          ))}
        </section>
      ))}

      {ownsMobile && (
        <section className="card" data-testid="mobile-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('Mobile')}</h2>
          {rowsOf(mobilePlans)}
          {number ? lines.map((sv) => (
            <div key={sv.id} data-testid="line-row">
              <LineDoctor serviceId={sv.id} />
              <p className="dim" data-testid="my-number">{t('Your number:')} <strong style={{ color: 'var(--teal)' }}>{numberOf(sv)}</strong>
                {sv.state === 'suspended' && <span className="state suspended" data-testid="line-paused"> {t('paused')}</span>}
                <SliceBadge service={sv} />
                {' '}
                {sv.state === 'active' ? (
                  <button className="ghost" data-testid="pause-line"
                    onClick={async () => {
                      const days = window.prompt(t('Pause this line for how many days? (1-90)'), '30');
                      if (!days) return;
                      try { await pauseMyService(sv.id, Number(days)); refresh(); } catch { /* stays on */ }
                    }}>
                    {t('Pause line')}
                  </button>
                ) : (
                  <button className="ghost" data-testid="resume-line"
                    onClick={async () => { try { await resumeMyService(sv.id); refresh(); } catch { /* stays paused */ } }}>
                    {t('Resume now')}
                  </button>
                )}
                {' '}
                <button className="ghost danger" data-testid="cancel-line"
                  onClick={async () => {
                    if (!window.confirm(t('Cancel this subscription? Your number is released and the line stops working.')
                        + '\n\n' + t('WANT TO KEEP YOUR NUMBER? Do NOT cancel — order with your new operator first; they move the number, and this subscription ends by itself.'))) return;
                    try { await cancelMyService(sv.id); refresh(); } catch { /* stays on */ }
                  }}>
                  {t('Cancel subscription')}
                </button>
              </p>
              {sv.state === 'active' && <SimCard serviceId={sv.id} />}
            </div>
          )) : <p className="dim">{t('Your line appears here once the plan activates.')}</p>}
          {dataBuckets.map((b, i) => <UsageMeter bucket={b} key={i} />)}
          {topUps.map((t) => {
            const oneTime = pricesOf(t, prices).find((p) => p.priceType === 'oneTime');
            return <TopUp key={t.id} offering={t} price={oneTime} onBought={refresh} />;
          })}
          <GiftData hh={hh} onDone={refresh} />
        </section>
      )}

      {loyaltyProg && (
        <section className="card" data-testid="loyalty-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('My points')}</h2>
          {loyalty ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <b data-testid="loyalty-points" style={{ fontSize: '1.4rem' }}>{loyalty.balance}</b>
              <span>{t('points')}</span>
              <button className="ghost" data-testid="loyalty-redeem" disabled={loyalty.balance < loyaltyProg.pointsPerGb}
                onClick={async () => {
                  try {
                    const r = await redeemLoyaltyData(1);
                    setLoyalty(r);
                    setLoyaltyMsg(t('Redeemed! The GB lands on this month\u2019s meter.'));
                  } catch (e) { setLoyaltyMsg(String(e.message || e)); }
                }}>{t('Redeem 1 GB')} ({loyaltyProg.pointsPerGb} {t('points')})</button>
              <button className="ghost" data-testid="loyalty-voucher" disabled={loyalty.balance < (loyaltyProg.pointsPerVoucher || 200)}
                onClick={async () => {
                  try {
                    const r = await redeemLoyaltyVoucher();
                    setLoyalty(r);
                    setLoyaltyMsg(`${t('Voucher')}: ${r.redeemed.voucherCode} (−${r.redeemed.percent}%)`);
                  } catch (e) { setLoyaltyMsg(String(e.message || e)); }
                }}>{t('Redeem voucher')} ({loyaltyProg.pointsPerVoucher || 200} {t('points')})</button>
              {loyaltyMsg && <span data-testid="loyalty-msg" style={{ color: 'var(--dim, #666)' }}>{loyaltyMsg}</span>}
            </div>
          ) : (
            <div>
              <p style={{ margin: '4px 0 10px' }}>{t('Earn points on every bill; redeem them as data.')}</p>
              <button className="ghost" data-testid="loyalty-join" onClick={async () => {
                setLoyalty(await enrollLoyalty());
              }}>{t('Join the loyalty program')}</button>
            </div>
          )}
        </section>
      )}
      <ReferralCard />
      {broadband.length > 0 && (
        <section className="card" data-testid="broadband-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('Broadband')}</h2>
          {rowsOf(broadband)}
                  {services.filter((sv) => sv.state === 'active' && /broadband|fib|dsl|internet/i.test(`${sv.category || ''} ${sv.name || ''}`)).map((sv) => (
            <div key={sv.id} className="row" data-testid="broadband-line">
              <span>{sv.name}<span className="dim small" style={{ display: 'block' }}><RouterPanel serviceId={sv.id} /></span></span>
              <LineDoctor serviceId={sv.id} />
            </div>
          ))}
</section>
      )}

      {entertainment.length > 0 && (
        <section className="card" data-testid="entertainment-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('TV & entertainment')}</h2>
          {rowsOf(entertainment)}
        </section>
      )}

      {devices.length > 0 && (
        <section className="card" data-testid="devices-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('My devices')}</h2>
          {rowsOf(devices)}
        </section>
      )}

      {other.length > 0 && (
        <section className="card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('Also active')}</h2>
          {rowsOf(other)}
        </section>
      )}

      {(entitlements.length > 0 || features.length > 0) && (
        <section className="card" data-testid="vas-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('My subscriptions & protection')}</h2>
          {entitlements.map((sv) => (
            <div className="row" key={sv.id} data-testid="entitlement-row">
              <strong>{sv.name}</strong>
              <span className="dim">{t('activation code:')}{' '}
                <code data-testid="activation-code">{activationOf(sv)}</code></span>
              <span className="dim">{t('manage with the partner')}</span>
            </div>
          ))}
          {features.map((sv) => (
            <div className="row" key={sv.id} data-testid="feature-row">
              <strong>{sv.name}</strong>
              <span className="dim">{t('protecting every line on this account')}</span>
              <span className={`state ${sv.state}`}>{sv.state}</span>
            </div>
          ))}
        </section>
      )}

      {otherBuckets.length > 0 && (
        <section className="card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t("This month's usage")}</h2>
          {otherBuckets.map((b, i) => <UsageMeter bucket={b} key={i} />)}
        </section>
      )}

      {ownsMobile && (
        <UsageControls boostOfferings={topUps} prices={prices} zoneBuckets={zoneBuckets} />
      )}

      {latestBill && (
        <section className="card" data-testid="bill-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('Latest bill')}</h2>
          <div className="row">
            <strong>{latestBill.billNo}</strong>
            <span className="dim">{fmtAmount(latestBill.amountDue)}</span>
            <span className={`state ${latestBill.state}`}>{latestBill.state}</span>
            <Link to="/bills" data-testid="pay-bill">
              {latestBill.state === 'settled' ? t('My bills') : t('Pay')} →
            </Link>
          </div>
        </section>
      )}

      {(recOffers.length > 0 || missing.length > 0) && (
        <section className="card" data-testid="discover" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{products.length ? t('Complete your setup') : t('Get started')}</h2>
          {recOffers.map((o) => {
            const monthly = pricesOf(o, prices).find((p) => p.priceType === 'recurring');
            return (
              <p key={o.id} style={{ margin: '6px 0' }} data-testid="rec-offer">
                <Link className="promolink" to={`/offering/${o.id}`}>{o.name}{monthly ? ` — ${fmtPrice(monthly)}` : ''} →</Link>
              </p>
            );
          })}
          {missing.map((m) => (
            <p key={m.cat} style={{ margin: '6px 0' }}>
              <Link className="promolink" to="/">{m.label} →</Link>
            </p>
          ))}
        </section>
      )}
      {(familyPaid.length > 0 || (hh?.dependents || []).length > 0 || hh?.payer) && (
        <section className="lobcard" data-testid="family-paid-card">
          <h2>👪 {t('Family')}</h2>
          {familyPaid.length > 0 && (
            <p style={{ margin: '4px 0' }}>
              {t('You pay for')} <b>{familyPaid.length}</b> {t('service(s) for')}{' '}
              {[...new Set(familyPaid.map(ownerIdOf))].map((depId) => {
                const dep = (hh?.dependents || []).find((d) => d.id === depId);
                return dep ? `${dep.givenName} ${dep.familyName}` : t('a family member');
              }).join(', ')}.
            </p>
          )}
          <p style={{ margin: '4px 0' }}>
            <Link to="/family" className="promolink" data-testid="family-hub-link">
              {t('Manage your family')} →
            </Link>
          </p>
        </section>
      )}
    </>
  );
}
