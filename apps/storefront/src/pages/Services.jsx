import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { changePlan, listOfferings, myActiveServices, myBills, myProducts, myRecommendations, mySim, myUsage, priceIndex, quickOrder, resetSimPin, myHousehold } from '../api.js';
import { tokenClaims } from '../auth.js';
import { fmtPrice, pricesOf } from '../money.js';
import { locale, money as intlMoney, t } from '../i18n.js';

/**
 * SIM self-care for a numbered line: masked ICCID always; PUK on request;
 * PIN pushed to the card over the air. The PUK never renders until asked for.
 */
function SimCard({ serviceId }) {
  const [sim, setSim] = useState(null);
  const [puk, setPuk] = useState(null);
  const [pin, setPin] = useState('');
  const [pinState, setPinState] = useState(null); // null | 'busy' | 'done' | error text

  useEffect(() => { mySim(serviceId).then(setSim).catch(() => {}); }, [serviceId]);
  if (!sim) return null;

  async function showPuk() {
    try { setPuk((await mySim(serviceId, true))?.puk || null); } catch { /* stays hidden */ }
  }
  async function submitPin() {
    setPinState('busy');
    try {
      await resetSimPin(serviceId, pin);
      setPinState('done'); setPin('');
    } catch (e) { setPinState(e.message); }
  }

  return (
    <div className="row" data-testid="sim-card">
      <strong>{t('My SIM')}</strong>
      <span className="dim" data-testid="sim-iccid">{sim.iccid}</span>
      {puk ? (
        <span className="dim">PUK: <strong style={{ color: 'var(--teal)' }} data-testid="sim-puk">{puk}</strong></span>
      ) : (
        <button className="ghost" data-testid="show-puk" onClick={showPuk}>{t('Show PUK')}</button>
      )}
      <span>
        <input
          data-testid="pin-input"
          style={{ width: '6.5em' }}
          placeholder={t('New PIN')}
          inputMode="numeric"
          maxLength={8}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
        />
        <button
          className="ghost"
          data-testid="reset-pin"
          disabled={pin.length < 4 || pinState === 'busy'}
          onClick={submitPin}
        >
          {t('Reset PIN')}
        </button>
        {pinState === 'done' && <span className="dim" data-testid="pin-done"> ✓ sent to your SIM</span>}
        {pinState && pinState !== 'done' && pinState !== 'busy' && <span className="error"> {pinState}</span>}
      </span>
    </div>
  );
}

function UsageMeter({ bucket }) {
  const used = Number(bucket.usedValue);
  const allowed = bucket.allowedValue == null ? null : Number(bucket.allowedValue);
  const over = allowed != null && used > allowed;
  const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
  return (
    <div className="usage-meter" data-testid="usage-meter">
      <div className="usage-meter-head">
        <span>{bucket.name}</span>
        <span className={over ? 'error' : 'dim'}>
          {used} {allowed != null ? `/ ${allowed} ` : ''}{bucket.units}
          {over ? ' — over allowance' : ''}
        </span>
      </div>
      {allowed != null && (
        <div className="usage-meter-track">
          <div
            className={`usage-meter-fill${over ? ' over' : ''}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      )}
    </div>
  );
}

const categoryOf = (offering) => offering?.category?.[0]?.name || '';
const PLAN_CATEGORIES = ['Mobile plans', 'Broadband'];

/**
 * Same number, new plan: a TMF622 modify order that completes instantly.
 * Like-for-like only — the dropdown offers plans from the SAME category as
 * the current one (a mobile plan changes to a mobile plan, broadband to
 * broadband), never devices or add-ons.
 */
function ChangePlan({ product, services, offerings, prices, onChanged }) {
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const current = offerings[product.productOffering?.id];
  const options = !open ? [] : Object.values(offerings)
    .filter((o) => !o.isBundle && !o.requiresVerifiedIdentity && o.id !== current?.id
      && categoryOf(o) === categoryOf(current))
    .map((o) => {
      const monthly = pricesOf(o, prices).find((p) => p.priceType === 'recurring');
      return monthly ? { id: o.id, name: o.name, label: `${o.name} — ${fmtPrice(monthly)}` } : null;
    })
    .filter(Boolean);

  async function confirm() {
    const target = options.find((o) => o.id === choice);
    if (!target) return;
    setBusy(true); setError(null);
    try {
      // the service realizing this product: match by plan name, so the SOM
      // renames the right line
      const svc = services.find((sv) => sv.name === product.name && sv.state === 'active');
      await changePlan(product.id, svc?.id, target);
      setOpen(false);
      onChanged(target.name);
    } catch (e) { setError(e.message); }
    setBusy(false);
  }

  if (!open) {
    return (
      <button className="ghost" data-testid={`change-plan-${product.id}`} onClick={() => setOpen(true)}>
        {t('Change plan')}
      </button>
    );
  }
  return (
    <span className="change-plan" data-testid="change-plan-form">
      <select value={choice} onChange={(e) => setChoice(e.target.value)} disabled={busy}>
        <option value="">{t('New plan…')}</option>
        {(options || []).map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
      <button className="primary" disabled={!choice || busy} onClick={confirm}>
        {busy ? t('Changing…') : t('Confirm')}
      </button>
      <button className="ghost" disabled={busy} onClick={() => setOpen(false)}>{t('Cancel')}</button>
      {error && <span className="error"> {error}</span>}
    </span>
  );
}

/** Every offering id a bundle can decompose into (fixed children + choice options). */
function bundleChildIds(offering) {
  const ids = new Set();
  for (const b of offering?.bundledProductOffering || []) {
    if (b.id) ids.add(b.id);
    for (const option of b.options || []) {
      if (option.id) ids.add(option.id);
    }
  }
  return ids;
}

function ProductRow({ product, services, offerings, prices, onChanged, nested }) {
  const payerTag = (() => {
    const payer = (product.relatedParty || []).find((x) => x.role === 'payer');
    if (!payer || payer.id === tokenClaims().sub) return null;
    return payer['@referredType'] === 'Organization'
      ? t('paid by your company') : t('paid by your household payer');
  })();
  const offering = offerings[product.productOffering?.id];
  const monthly = offering ? pricesOf(offering, prices).find((p) => p.priceType === 'recurring') : null;
  const changeable = product.status === 'active' && PLAN_CATEGORIES.includes(categoryOf(offering));
  return (
    <div className="row" style={nested ? { marginLeft: '1.6em' } : undefined}>
      <strong>{product.name}</strong>
      {monthly && <span className="dim">{fmtPrice(monthly)}</span>}
      {payerTag && <span className="dim" data-testid="paid-by" style={{ fontSize: 12 }}>💳 {payerTag}</span>}
      <span className={`state ${product.status}`}>{product.status}</span>
      {changeable && (
        <ChangePlan product={product} services={services}
          offerings={offerings} prices={prices} onChanged={onChanged} />
      )}
    </div>
  );
}

export default function Services() {
  const [products, setProducts] = useState(null);
  const [services, setServices] = useState([]);
  const [buckets, setBuckets] = useState([]);
  const [offerings, setOfferings] = useState({});
  const [prices, setPrices] = useState({});
  const [changed, setChanged] = useState(null);
  const [bills, setBills] = useState([]);
  const [recIds, setRecIds] = useState([]);
  const [error, setError] = useState(null);

  const [hh, setHh] = useState(null);
  function refresh() {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    myActiveServices().then(setServices).catch(() => {});
    // Usage and bills are additive: the page renders without them.
    myUsage().then((report) => setBuckets(report.bucket || [])).catch(() => {});
    myBills().then(setBills).catch(() => {});
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
  const lines = services.filter((sv) => sv.state === 'active'
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
  const ownedOfferingIds = new Set(products.map((p) => p.productOffering?.id));
  const recOffers = recIds.map((id) => offerings[id])
    .filter((o) => o && !ownedOfferingIds.has(o.id) && !o.requiresVerifiedIdentity
      && categoryOf(o) !== 'Top-ups') // the top-up has its own button on the Mobile card
    .slice(0, 3);
  const dataBuckets = buckets.filter((b) => b.name === 'Mobile data');
  const otherBuckets = buckets.filter((b) => b.name !== 'Mobile data');
  const topUps = Object.values(offerings).filter((o) => categoryOf(o) === 'Top-ups');
  const ownsMobile = mobilePlans.length > 0 || bundleGroups.length > 0 || Boolean(number);

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

  return (
    <>
      <h1>{t('My page')}</h1>
      {changed && (
        <p className="dim" data-testid="plan-changed">
          ✓ Plan changed to <strong style={{ color: 'var(--teal)' }}>{changed}</strong> — you keep your number.
        </p>
      )}

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
              <p className="dim" data-testid="my-number">{t('Your number:')} <strong style={{ color: 'var(--teal)' }}>{numberOf(sv)}</strong></p>
              <SimCard serviceId={sv.id} />
            </div>
          )) : <p className="dim">{t('Your line appears here once the plan activates.')}</p>}
          {dataBuckets.map((b, i) => <UsageMeter bucket={b} key={i} />)}
          {topUps.map((t) => {
            const oneTime = pricesOf(t, prices).find((p) => p.priceType === 'oneTime');
            return <TopUp key={t.id} offering={t} price={oneTime} onBought={refresh} />;
          })}
        </section>
      )}

      {broadband.length > 0 && (
        <section className="card" data-testid="broadband-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{t('Broadband')}</h2>
          {rowsOf(broadband)}
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
                <Link to={`/offering/${o.id}`}>{o.name}{monthly ? ` — ${fmtPrice(monthly)}` : ''} →</Link>
              </p>
            );
          })}
          {missing.map((m) => (
            <p key={m.cat} style={{ margin: '6px 0' }}>
              <Link to="/">{m.label} →</Link>
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

/** One tap, more data now: buys the top-up and the meter grows this month. */
function TopUp({ offering, price, onBought }) {
  const [state, setState] = useState(null); // null | 'busy' | 'done' | error
  async function buy() {
    setState('busy');
    try {
      await quickOrder(offering);
      setState('done');
      // the boost lands via the event stream; give it a beat then refresh
      setTimeout(onBought, 2500);
      setTimeout(onBought, 6000);
    } catch (e) { setState(e.message); }
  }
  return (
    <p style={{ margin: '8px 0 0' }}>
      <button className="ghost" data-testid={`topup-${offering.id}`} disabled={state === 'busy'} onClick={buy}>
        {state === 'busy' ? t('Buying…') : `${offering.name}${price ? ` — ${fmtPrice(price)}` : ''}`}
      </button>
      {state === 'done' && <span className="dim" data-testid="topup-done"> ✓ added to this month's allowance</span>}
      {state && state !== 'done' && state !== 'busy' && <span className="error"> {state}</span>}
    </p>
  );
}

