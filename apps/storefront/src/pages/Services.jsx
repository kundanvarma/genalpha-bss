import { useEffect, useState } from 'react';
import { myProducts } from '../api.js';

export default function Services() {
  const [products, setProducts] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    myProducts().then(setProducts).catch((e) => setError(e.message));
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
    </>
  );
}
