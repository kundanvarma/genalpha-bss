import { useEffect, useState } from 'react';
import { changePlan, listOfferings, myActiveServices, myProducts, myUsage, priceIndex } from '../api.js';
import { fmtPrice, pricesOf } from '../money.js';

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

/**
 * Same number, new plan: a TMF622 modify order that completes instantly.
 * The dropdown offers every other non-bundle plan with a monthly price.
 */
function ChangePlan({ product, services, onChanged }) {
  const [open, setOpen] = useState(false);
  const [options, setOptions] = useState(null);
  const [choice, setChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function loadOptions() {
    setOpen(true);
    if (options) return;
    try {
      const [offers, prices] = await Promise.all([listOfferings(), priceIndex()]);
      setOptions(offers
        .filter((o) => !o.isBundle && !o.requiresVerifiedIdentity && o.id !== product.productOffering?.id)
        .map((o) => {
          const monthly = pricesOf(o, prices).find((p) => p.priceType === 'recurring');
          return monthly ? { id: o.id, name: o.name, label: `${o.name} — ${fmtPrice(monthly)}` } : null;
        })
        .filter(Boolean));
    } catch (e) { setError(e.message); }
  }

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
      <button className="ghost" data-testid={`change-plan-${product.id}`} onClick={loadOptions}>
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

export default function Services() {
  const [products, setProducts] = useState(null);
  const [services, setServices] = useState([]);
  const [buckets, setBuckets] = useState([]);
  const [changed, setChanged] = useState(null);
  const [error, setError] = useState(null);

  function refresh() {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    myActiveServices().then(setServices).catch(() => {});
    // Usage is additive: a service list without meters still renders.
    myUsage().then((report) => setBuckets(report.bucket || [])).catch(() => {});
  }
  useEffect(refresh, []);

  if (error) return <p className="error">{error}</p>;
  if (!products) return <p className="dim">Loading your services…</p>;
  if (!products.length) {
    return <p className="dim">Nothing active yet — services appear here once an order completes.</p>;
  }

  return (
    <>
      <h1>My services</h1>
      {(() => {
        const numbered = services.find((sv) => (sv.supportingResource || []).some((r) => r.value));
        const number = numbered?.supportingResource?.find((r) => r.value)?.value;
        return number ? (
          <p className="dim" data-testid="my-number">Your number: <strong style={{ color: 'var(--teal)' }}>{number}</strong></p>
        ) : null;
      })()}
      {changed && (
        <p className="dim" data-testid="plan-changed">
          ✓ Plan changed to <strong style={{ color: 'var(--teal)' }}>{changed}</strong> — you keep your number.
        </p>
      )}
      <div className="rows">
        {products.map((p) => (
          <div className="row" key={p.id}>
            <strong>{p.name}</strong>
            <span className={`state ${p.status}`}>{p.status}</span>
            {p.status === 'active' && (
              <ChangePlan
                product={p}
                services={services}
                onChanged={(name) => { setChanged(name); refresh(); }}
              />
            )}
          </div>
        ))}
      </div>
      {buckets.length > 0 && (
        <>
          <h2>This month's usage</h2>
          <div className="rows">
            {buckets.map((b, i) => (
              <UsageMeter bucket={b} key={i} />
            ))}
          </div>
        </>
      )}
    </>
  );
}
