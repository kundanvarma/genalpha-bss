import { useEffect, useState } from 'react';
import { stockLevels } from '../api.js';

export default function Stock() {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    stockLevels().then(setRows).catch((e) => setError(e.message));
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!rows) return <p className="dim">Loading stock…</p>;

  return (
    <>
      <h1>Product stock</h1>
      <div className="rows">
        {rows.map((s) => (
          <div className="row" key={s.id}>
            <div>
              <strong>{s.productOffering?.name || s.name}</strong>
              <div className="dim small">{s.name}</div>
            </div>
            <div className="rowend">
              <span className="dim small">stocked {s.stockedQuantity.amount}</span>
              <span className="dim small">reserved {s.reservedQuantity.amount}</span>
              <span className={s.availableQuantity.amount === 0 ? 'state cancelled' : 'state active'}>
                {s.availableQuantity.amount} available
              </span>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
