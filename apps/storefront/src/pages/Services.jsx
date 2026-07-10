import { useEffect, useState } from 'react';
import { myProducts, myUsage } from '../api.js';

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

export default function Services() {
  const [products, setProducts] = useState(null);
  const [buckets, setBuckets] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    myProducts().then(setProducts).catch((e) => setError(e.message));
    // Usage is additive: a service list without meters still renders.
    myUsage().then((report) => setBuckets(report.bucket || [])).catch(() => {});
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!products) return <p className="dim">Loading your services…</p>;
  if (!products.length) {
    return <p className="dim">Nothing active yet — services appear here once an order completes.</p>;
  }

  return (
    <>
      <h1>My services</h1>
      <div className="rows">
        {products.map((p) => (
          <div className="row" key={p.id}>
            <strong>{p.name}</strong>
            <span className={`state ${p.status}`}>{p.status}</span>
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
