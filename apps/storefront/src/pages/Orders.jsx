import { useEffect, useState } from 'react';
import { cancelOrder, myAppointments, myOrderJourney, myOrders, myShipments } from '../api.js';

const TERMINAL = ['completed', 'cancelled'];

// Friendly labels — the order/item states are TMF622 words; customers see plain ones.
const ORDER_LABEL = {
  acknowledged: 'Received',
  inProgress: 'In progress',
  partiallyCompleted: 'In progress',
  completed: 'Completed',
  cancelled: 'Cancelled',
  held: 'Awaiting approval',
};

// Flatten an order's leaf items (bundle picks are nested children).
function leafItems(items, into = []) {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) leafItems(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
}

export default function Orders() {
  const [orders, setOrders] = useState(null);
  const [visits, setVisits] = useState({}); // order id -> appointment
  const [ships, setShips] = useState({}); // order id -> shipping order
  const [journeys, setJourneys] = useState({}); // order id -> flow (lazy)
  const [openJourney, setOpenJourney] = useState(null);
  const [error, setError] = useState(null);

  // The order journey — fetched the first time a customer expands "Why?".
  function toggleJourney(orderId) {
    if (openJourney === orderId) { setOpenJourney(null); return; }
    setOpenJourney(orderId);
    if (!journeys[orderId]) {
      myOrderJourney(orderId).then((j) => setJourneys((m) => ({ ...m, [orderId]: j || 'none' })));
    }
  }

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
    myShipments().then((shipments) => {
      const byOrder = {};
      for (const s of shipments || []) if (s.productOrderId) byOrder[s.productOrderId] = s;
      setShips(byOrder);
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

  // What one item's status reads as, given its state and any parcel/visit.
  function itemStatus(item, ship, visit) {
    const st = item.state || 'acknowledged';
    const physical = !!(item.product && item.product.place);
    const chars = (item.product && item.product.productCharacteristic) || [];
    const simType = (chars.find((c) => c.name === 'simType') || {}).value;
    if (st === 'completed') {
      if (simType === 'esim') return { cls: 'ok', text: '✓ eSIM active' };
      return { cls: 'ok', text: physical ? '✓ Delivered' : '✓ Active' };
    }
    if (st === 'cancelled') return { cls: 'no', text: 'Cancelled' };
    if (simType === 'esim') return { cls: 'ok', text: '⚡ eSIM — ready to activate' };
    if (physical && ship && ship.trackingRef) {
      const via = ship.carrier ? ` (${ship.carrier})` : '';
      if (ship.deliveryMethod === 'pickupPoint' && ship.pickupPoint) {
        return { cls: 'go', text: `📍 To pickup point · ${ship.pickupPoint}${via}` };
      }
      return { cls: 'go', text: `📦 On its way · ${ship.trackingRef}${via}` };
    }
    if (physical && visit) return { cls: 'go', text: '🔧 Install booked' };
    if (physical) return { cls: 'go', text: '📦 Preparing shipment' };
    return { cls: 'go', text: 'Activating…' };
  }

  return (
    <>
      <h1>My orders</h1>
      <div className="rows">
        {orders.map((o) => {
          const items = leafItems(o.productOrderItem);
          const ship = ships[o.id];
          const visit = visits[o.id];
          return (
            <div className="row orderrow" key={o.id}>
              <div className="ordermain">
                <strong>{o.description || o.id}</strong>
                <div className="dim small">
                  {o.orderDate ? new Date(o.orderDate).toLocaleString() : ''}
                </div>
                {visit && (
                  <div className="small installnote">
                    🔧 Install: {new Date(visit.validFor.startDateTime).toLocaleString(undefined,
                      { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                  </div>
                )}
                {items.length > 0 && (
                  <ul className="itemlines">
                    {items.map((it, i) => {
                      const s = itemStatus(it, ship, visit);
                      return (
                        <li key={it.id || i}>
                          <span className="itemname">{it.productOffering?.name || 'Item'}</span>
                          <span className={`itemstatus ${s.cls}`}>{s.text}</span>
                        </li>
                      );
                    })}
                  </ul>
                )}
                {!TERMINAL.includes(o.state) && (
                  <button className="linkish small" data-testid="why-toggle"
                          onClick={() => toggleJourney(o.id)}>
                    {openJourney === o.id ? 'Hide progress ▲' : 'Why is it in progress? ▾'}
                  </button>
                )}
                {openJourney === o.id && (() => {
                  const j = journeys[o.id];
                  if (!j) return <div className="dim small journey" data-testid="journey">Loading…</div>;
                  if (j === 'none') return <div className="dim small journey" data-testid="journey">
                    We\'re on it — your order is being set up.</div>;
                  const g = (st) => st === 'completed' ? '✓' : (st === 'failed' || st === 'cancelled') ? '✗'
                    : st === 'held' ? '⏸' : '⏳';
                  return (
                    <div className="journey" data-testid="journey">
                      <p className="journeywhy"><b>{j.summary?.headline}</b>
                        {j.summary?.why ? ` — ${j.summary.why}` : ''}</p>
                      <ul className="journeysteps">
                        {(j.taskFlow || []).map((t) => (
                          <li key={t.id} className={`jstep ${t.state}`}>
                            <span className="jglyph">{g(t.state)}</span> {t.name}
                            {t.message ? <span className="dim small"> — {t.message}</span> : null}
                          </li>
                        ))}
                      </ul>
                    </div>
                  );
                })()}
              </div>
              <div className="rowend">
                <span className={`state ${o.state}`}>{ORDER_LABEL[o.state] || o.state}</span>
                {!TERMINAL.includes(o.state) && (
                  <button className="ghost danger" onClick={() => cancel(o.id)}>Cancel</button>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </>
  );
}
