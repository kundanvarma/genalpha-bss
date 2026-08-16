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

// The product families an order decomposes into, in dependency order — the base
// connection first, then what rides on it, then mobile, then anything else.
const FAMILIES = [
  { type: 'internet', icon: '🌐', label: 'Internet' },
  { type: 'tv', icon: '📺', label: 'TV & Entertainment' },
  { type: 'mobile', icon: '📱', label: 'Mobile' },
  { type: 'other', icon: '📦', label: 'Also in this order' },
];

// Flatten an order's leaf items (bundle picks are nested children).
function leafItems(items, into = []) {
  for (const it of items || []) {
    const kids = it.productOrderItem || [];
    if (kids.length) leafItems(kids, into);
    else if (it.productOffering) into.push(it);
  }
  return into;
}

// Coarse family for a leaf — the decomposition axis. The ordering service stamps
// componentType; fall back to the offering name so older orders still group.
function familyOf(item) {
  if (item.componentType) return item.componentType;
  const n = (item.productOffering?.name || '').toLowerCase();
  if (/fiber|fibre|broadband|internet|dsl/.test(n)) return 'internet';
  if (/\btv\b|stream|sports|entertain|netflix|kids tv/.test(n)) return 'tv';
  if (/mobile|sim|5g|data|gb\b/.test(n)) return 'mobile';
  if (/iphone|galaxy|pixel|phone|handset|watch/.test(n)) return 'device';
  return 'other';
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

  // The SIM/number line for a mobile component — the sub-status the customer cares
  // about: an eSIM is ready at once; a physical SIM's number is reserved until the
  // card arrives; a ported number carries its cutover.
  function mobileLine(item, ship) {
    const chars = (item.product && item.product.productCharacteristic) || [];
    const c = (name) => (chars.find((x) => x.name === name) || {}).value;
    const simType = c('simType');
    const number = c('msisdn') || c('phoneNumber');
    const num = number ? ` ${number}` : '';
    const done = item.state === 'completed';
    if (c('portIn') || c('portingFrom')) {
      const from = c('portingFrom') || 'your old operator';
      const when = c('portDate') || c('cutover');
      return done
        ? { cls: 'ok', text: `✓ Number${num} ported from ${from}` }
        : { cls: 'go', text: `⏳ Porting${num} from ${from}${when ? ` — cutover ${when}` : ''}` };
    }
    if (simType === 'esim') {
      return done
        ? { cls: 'ok', text: `✓ Active on eSIM${num}` }
        : { cls: 'ok', text: `⚡ eSIM ready to install${num}` };
    }
    // physical SIM: the number is reserved and activates when the card lands
    if (simType === 'physical' || (ship && ship.trackingRef)) {
      if (done) return { cls: 'ok', text: `✓ Active on${num || ' your new number'}` };
      const via = ship && ship.carrier ? ` (${ship.carrier})` : '';
      const track = ship && ship.trackingUrl
        ? { trackUrl: ship.trackingUrl, carrier: ship.carrier || 'the carrier' } : {};
      if (ship && ship.state === 'delivered') {
        return { cls: 'ok', text: `✓ SIM delivered — activating on${num || ' your number'}` };
      }
      return {
        cls: 'go',
        text: `⏳ Number${num || ''} reserved — activates when your SIM arrives${via}`,
        ...track,
      };
    }
    return done ? { cls: 'ok', text: `✓ Active${num}` } : { cls: 'go', text: 'Activating…' };
  }

  // What one item's status reads as, given its state, family and any parcel/visit.
  function itemStatus(item, ship, visit, waitingOn) {
    const st = item.state || 'acknowledged';
    const fam = familyOf(item);
    const physical = !!(item.product && item.product.place);
    if (st === 'cancelled') return { cls: 'no', text: 'Cancelled' };

    if (fam === 'mobile') return mobileLine(item, ship);

    if (fam === 'internet') {
      if (st === 'completed') return { cls: 'ok', text: '✓ Broadband active' };
      if (visit) return { cls: 'go', text: '🔧 Install booked' };
      return { cls: 'go', text: '⏳ Setting up your line' };
    }

    if (fam === 'tv') {
      if (st === 'completed') return { cls: 'ok', text: '✓ Ready to watch' };
      // the dependency, in the customer's words
      if (waitingOn) return { cls: 'go', text: `⏳ Activates once your ${waitingOn} is live` };
      return { cls: 'go', text: '⏳ Setting up' };
    }

    // device / other — the physical goods path (packed → shipped → delivered)
    if (st === 'completed') return { cls: 'ok', text: physical ? '✓ Delivered' : '✓ Active' };
    if (physical && ship && ship.trackingRef) {
      const via = ship.carrier ? ` (${ship.carrier})` : '';
      const track = ship.trackingUrl
        ? { trackUrl: ship.trackingUrl, carrier: ship.carrier || 'the carrier' } : {};
      if (ship.deliveryMethod === 'pickupPoint' && ship.pickupPoint) {
        const at = ship.state === 'delivered' ? '✓ Ready for collection at' : '📍 On its way to';
        return { cls: 'go', text: `${at} ${ship.pickupPoint}${via} · ${ship.trackingRef}`, ...track };
      }
      const stageText = ship.state === 'delivered' ? '✓ Delivered'
        : ship.state === 'acknowledged' ? `📦 Packed — preparing to ship · ${ship.trackingRef}${via}`
        : `🚚 Shipped — on its way · ${ship.trackingRef}${via}`;
      return { cls: 'go', text: stageText, ...track };
    }
    if (physical) return { cls: 'go', text: '📦 Packed — preparing to ship' };
    return { cls: 'go', text: 'Activating…' };
  }

  // Group an order's leaves into product families, folding a handset into Mobile
  // (the phone belongs with the line), and note each family's reliesOn base.
  function decompose(order) {
    const leaves = leafItems(order.productOrderItem);
    const byId = Object.fromEntries(leaves.map((l) => [l.id, l]));
    const families = FAMILIES.map((f) => ({ ...f, items: [] }));
    const mobile = families.find((f) => f.type === 'mobile');
    for (const leaf of leaves) {
      const fam = familyOf(leaf);
      // a handset rides in the Mobile section when there's a line to ride with
      if (fam === 'device' && mobile) { mobile.items.push({ leaf, kind: 'handset' }); continue; }
      const target = families.find((f) => f.type === fam) || families.find((f) => f.type === 'other');
      target.items.push({ leaf, kind: fam });
    }
    // the plain-language "waiting on" for a family whose base isn't live yet
    const waitOn = (leaf) => {
      const rel = (leaf.orderItemRelationship || [])
        .find((r) => r.relationshipType === 'reliesOn');
      if (!rel) return null;
      const base = byId[rel.id];
      if (!base || base.state === 'completed') return null;
      return familyOf(base) === 'internet' ? 'broadband' : (base.productOffering?.name || 'base service');
    };
    return { families: families.filter((f) => f.items.length), leaves, byId, waitOn };
  }

  return (
    <>
      <h1>My orders</h1>
      <div className="rows">
        {orders.map((o) => {
          const ship = ships[o.id];
          const visit = visits[o.id];
          const { families, leaves, waitOn } = decompose(o);
          const done = leaves.filter((l) => l.state === 'completed').length;
          const isMulti = families.length > 1 || leaves.length > 1;
          // The process flow can finish a beat before the order state flips
          // (two services, eventual consistency). If so, don't frame it as "why
          // is it stuck" — it's finishing up.
          const loadedJ = journeys[o.id];
          const flowDone = loadedJ && loadedJ !== 'none' && (loadedJ.taskFlow || []).length > 0
            && (loadedJ.taskFlow || []).every((t) => t.state === 'completed');
          const openText = openJourney === o.id ? 'Hide progress ▲'
            : flowDone ? 'Finishing up — see steps ▾' : 'Why is it in progress? ▾';
          return (
            <div className="row orderrow" key={o.id}>
              <div className="ordermain">
                <strong>{o.description || o.id}</strong>
                <div className="dim small">
                  {o.orderDate ? new Date(o.orderDate).toLocaleString() : ''}
                  {isMulti && !TERMINAL.includes(o.state) && leaves.length > 0
                    && <> · <b>{done} of {leaves.length}</b> ready</>}
                </div>
                {visit && (
                  <div className="small installnote">
                    🔧 Install: {new Date(visit.validFor.startDateTime).toLocaleString(undefined,
                      { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
                  </div>
                )}
                {families.length > 0 && (
                  <div className="components" data-testid="components">
                    {families.map((fam) => (
                      <div className="component" data-testid={`component-${fam.type}`} key={fam.type}>
                        <div className="component-head">
                          <span className="component-icon">{fam.icon}</span>
                          <span className="component-label">{fam.label}</span>
                        </div>
                        <ul className="component-lines">
                          {fam.items.map(({ leaf, kind }, i) => {
                            const waitingOn = kind === 'tv' ? waitOn(leaf) : null;
                            const s = itemStatus(leaf, ship, visit, waitingOn);
                            const isHandset = kind === 'handset';
                            return (
                              <li key={leaf.id || i} className={isHandset ? 'sub' : ''}>
                                <span className="itemname">
                                  {isHandset ? '📦 ' : ''}{leaf.productOffering?.name || 'Item'}
                                </span>
                                <span className={`itemstatus ${s.cls}`}>{s.text}</span>
                                {s.trackUrl && (
                                  <a className="tracklink" data-testid="track-link" href={s.trackUrl}
                                     target="_blank" rel="noopener noreferrer">Track with {s.carrier} ↗</a>
                                )}
                              </li>
                            );
                          })}
                        </ul>
                      </div>
                    ))}
                  </div>
                )}
                {!TERMINAL.includes(o.state) && (
                  <button className="linkish small" data-testid="why-toggle"
                          onClick={() => toggleJourney(o.id)}>
                    {openText}
                  </button>
                )}
                {openJourney === o.id && (() => {
                  const j = journeys[o.id];
                  if (!j) return <div className="dim small journey" data-testid="journey">Loading…</div>;
                  if (j === 'none') return <div className="dim small journey" data-testid="journey">
                    We're on it — your order is being set up.</div>;
                  const g = (state) => state === 'completed' ? '✓' : state === 'cancelled' ? '✗' : '⏳';
                  const allDone = (j.taskFlow || []).length > 0
                    && (j.taskFlow || []).every((t) => t.state === 'completed');
                  // Flow done but the order chrome hasn't caught up (two services,
                  // a beat apart): say "finishing up", not a bare "all completed".
                  const headline = allDone && !TERMINAL.includes(o.state)
                    ? 'Provisioned — finalizing your order'
                    : (j.summary?.headline || '');
                  const why = allDone && !TERMINAL.includes(o.state)
                    ? 'Your services are set up; this will show as complete in a moment.'
                    : (j.summary?.why || '');
                  return (
                    <div className="journey" data-testid="journey">
                      <p className="journeywhy"><b>{headline}</b>
                        {why ? ` — ${why}` : ''}</p>
                      <ul className="journeysteps">
                        {(j.taskFlow || []).map((t) => (
                          <li key={t.id} className={`jstep ${t.state === 'completed' ? 'completed' : 'active'}`}>
                            <span className="jglyph">{g(t.state)}</span> {t.name}
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
