import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { changePlan, listOfferings, myActiveServices, myProducts, mySim, myUsage, priceIndex, quickOrder, resetSimPin } from '../api.js';
import { fmtPrice, pricesOf } from '../money.js';

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
      <strong>My SIM</strong>
      <span className="dim" data-testid="sim-iccid">{sim.iccid}</span>
      {puk ? (
        <span className="dim">PUK: <strong style={{ color: 'var(--teal)' }} data-testid="sim-puk">{puk}</strong></span>
      ) : (
        <button className="ghost" data-testid="show-puk" onClick={showPuk}>Show PUK</button>
      )}
      <span>
        <input
          data-testid="pin-input"
          style={{ width: '6.5em' }}
          placeholder="New PIN"
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
          Reset PIN
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
        Change plan
      </button>
    );
  }
  return (
    <span className="change-plan" data-testid="change-plan-form">
      <select value={choice} onChange={(e) => setChoice(e.target.value)} disabled={busy}>
        <option value="">New plan…</option>
        {(options || []).map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
      <button className="primary" disabled={!choice || busy} onClick={confirm}>
        {busy ? 'Changing…' : 'Confirm'}
      </button>
      <button className="ghost" disabled={busy} onClick={() => setOpen(false)}>Cancel</button>
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
  const offering = offerings[product.productOffering?.id];
  const monthly = offering ? pricesOf(offering, prices).find((p) => p.priceType === 'recurring') : null;
  const changeable = product.status === 'active' && PLAN_CATEGORIES.includes(categoryOf(offering));
  return (
    <div className="row" style={nested ? { marginLeft: '1.6em' } : undefined}>
      <strong>{product.name}</strong>
      {monthly && <span className="dim">{fmtPrice(monthly)}</span>}
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
  const [error, setError] = useState(null);

  function refresh() {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    myActiveServices().then(setServices).catch(() => {});
    // Usage is additive: a service list without meters still renders.
    myUsage().then((report) => setBuckets(report.bucket || [])).catch(() => {});
  }
  useEffect(() => {
    refresh();
    listOfferings().then((all) =>
      setOfferings(Object.fromEntries(all.map((o) => [o.id, o])))).catch(() => {});
    priceIndex().then(setPrices).catch(() => {});
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!products) return <p className="dim">Loading your page…</p>;
  if (!products.length) {
    return <p className="dim">Nothing active yet — your plan appears here once an order completes.</p>;
  }

  // The MyJio idea: the page RECOMPOSES around what this customer holds —
  // one dashboard card per line of business, discovery for what they lack.
  const onChanged = (name) => { setChanged(name); refresh(); };
  const catOfProduct = (p) => categoryOf(offerings[p.productOffering?.id]);
  const bundles = products.filter((p) => offerings[p.productOffering?.id]?.isBundle);
  const claimed = new Set(bundles.map((p) => p.id));
  const componentsOf = (bundleProduct) => {
    const childIds = bundleChildIds(offerings[bundleProduct.productOffering?.id]);
    return products.filter((p) => !claimed.has(p.id) && childIds.has(p.productOffering?.id)
      && (claimed.add(p.id), true));
  };
  const bundleGroups = bundles.map((b) => ({ bundle: b, components: componentsOf(b) }));
  const standalone = products.filter((p) => !claimed.has(p.id));
  const byCat = (cat) => standalone.filter((p) => catOfProduct(p) === cat);
  const mobilePlans = byCat('Mobile plans');
  const broadband = byCat('Broadband');
  const entertainment = byCat('TV & Add-ons');
  const devices = byCat('Devices');
  const other = standalone.filter((p) => ![...mobilePlans, ...broadband, ...entertainment, ...devices].includes(p)
    && catOfProduct(p) !== 'Top-ups');

  const numbered = services.find((sv) => (sv.supportingResource || []).some((r) => r.value));
  const number = numbered?.supportingResource?.find((r) => r.value)?.value;
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
    !ownsMobile && { label: 'Add a mobile plan', cat: 'Mobile plans' },
    !broadband.length && !bundleGroups.length && { label: 'Add broadband', cat: 'Broadband' },
    !entertainment.length && !bundleGroups.length && { label: 'Add TV & streaming', cat: 'TV & Add-ons' },
  ].filter(Boolean);

  return (
    <>
      <h1>My page</h1>
      {changed && (
        <p className="dim" data-testid="plan-changed">
          ✓ Plan changed to <strong style={{ color: 'var(--teal)' }}>{changed}</strong> — you keep your number.
        </p>
      )}

      {bundleGroups.map(({ bundle, components }) => (
        <section className="card" key={bundle.id} data-testid={`bundle-${bundle.id}`}
          style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>My bundle</h2>
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
          <h2 style={{ marginTop: 0 }}>Mobile</h2>
          {rowsOf(mobilePlans)}
          {number ? (
            <>
              <p className="dim" data-testid="my-number">Your number: <strong style={{ color: 'var(--teal)' }}>{number}</strong></p>
              <SimCard serviceId={numbered.id} />
            </>
          ) : <p className="dim">Your line appears here once the plan activates.</p>}
          {dataBuckets.map((b, i) => <UsageMeter bucket={b} key={i} />)}
          {topUps.map((t) => {
            const oneTime = pricesOf(t, prices).find((p) => p.priceType === 'oneTime');
            return <TopUp key={t.id} offering={t} price={oneTime} onBought={refresh} />;
          })}
        </section>
      )}

      {broadband.length > 0 && (
        <section className="card" data-testid="broadband-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>Broadband</h2>
          {rowsOf(broadband)}
        </section>
      )}

      {entertainment.length > 0 && (
        <section className="card" data-testid="entertainment-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>TV &amp; entertainment</h2>
          {rowsOf(entertainment)}
        </section>
      )}

      {devices.length > 0 && (
        <section className="card" data-testid="devices-card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>My devices</h2>
          {rowsOf(devices)}
        </section>
      )}

      {other.length > 0 && (
        <section className="card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>Also active</h2>
          {rowsOf(other)}
        </section>
      )}

      {otherBuckets.length > 0 && (
        <section className="card" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>This month's usage</h2>
          {otherBuckets.map((b, i) => <UsageMeter bucket={b} key={i} />)}
        </section>
      )}

      {missing.length > 0 && (
        <section className="card" data-testid="discover" style={{ padding: '14px 18px', marginBottom: 14 }}>
          <h2 style={{ marginTop: 0 }}>{products.length ? 'Complete your setup' : 'Get started'}</h2>
          {missing.map((m) => (
            <p key={m.cat} style={{ margin: '6px 0' }}>
              <Link to="/">{m.label} →</Link>
            </p>
          ))}
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
        {state === 'busy' ? 'Buying…' : `${offering.name}${price ? ` — ${fmtPrice(price)}` : ''}`}
      </button>
      {state === 'done' && <span className="dim" data-testid="topup-done"> ✓ added to this month's allowance</span>}
      {state && state !== 'done' && state !== 'busy' && <span className="error"> {state}</span>}
    </p>
  );
}
