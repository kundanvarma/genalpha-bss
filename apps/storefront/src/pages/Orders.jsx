import { useEffect, useState } from 'react';
import { cancelOrder, myOrders } from '../api.js';

const TERMINAL = ['completed', 'cancelled'];

export default function Orders() {
  const [orders, setOrders] = useState(null);
  const [error, setError] = useState(null);

  const load = () => myOrders().then(setOrders).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  if (error) return <p className="error">{error}</p>;
  if (!orders) return <p className="dim">Loading your orders…</p>;
  if (!orders.length) return <p className="dim">No orders yet — pick an offer to get started.</p>;

  async function cancel(id) {
    if (!confirm('Cancel this order?')) return;
    try {
      await cancelOrder(id);
      load();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <>
      <h1>My orders</h1>
      <div className="rows">
        {orders.map((o) => (
          <div className="row" key={o.id}>
            <div>
              <strong>{o.description || o.id}</strong>
              <div className="dim small">
                {o.orderDate ? new Date(o.orderDate).toLocaleString() : ''}
              </div>
            </div>
            <div className="rowend">
              <span className={`state ${o.state}`}>{o.state}</span>
              {!TERMINAL.includes(o.state) && (
                <button className="ghost danger" onClick={() => cancel(o.id)}>Cancel</button>
              )}
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
