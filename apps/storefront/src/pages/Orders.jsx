import { useEffect, useState } from 'react';
import { cancelOrder, myAppointments, myOrders } from '../api.js';

const TERMINAL = ['completed', 'cancelled'];

export default function Orders() {
  const [orders, setOrders] = useState(null);
  const [visits, setVisits] = useState({}); // order id -> appointment
  const [error, setError] = useState(null);

  const load = () => myOrders().then(setOrders).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    myAppointments().then((appointments) => {
      const byOrder = {};
      for (const appt of appointments) {
        const orderId = (appt.relatedEntity || [])[0]?.id;
        if (orderId && appt.status === 'confirmed') byOrder[orderId] = appt;
      }
      setVisits(byOrder);
    }).catch(() => {});
  }, []);

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
              {visits[o.id] && (
                <div className="small installnote">
                  🔧 Install: {new Date(visits[o.id].validFor.startDateTime).toLocaleString(undefined,
                    { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                </div>
              )}
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
