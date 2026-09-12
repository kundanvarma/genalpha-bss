import { useState } from 'react';
import TicketCard from '../TicketCard.jsx';

/* One customer story. Interactions, orders, tickets and ports become one
 * chronological timeline with filters; the Overview shows the newest few and
 * "View all activity" opens this. Every event keeps its source record in reach. */

export const dt = (v) => v ? new Date(v).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
export const chan = (c) => Array.isArray(c)
  ? c.map((x) => (x && typeof x === 'object' ? (x.name || x.id || '') : x)).filter(Boolean).join(', ')
  : (c && typeof c === 'object' ? (c.name || c.id || '') : (c || ''));

const FILTERS = [['all', 'All'], ['interaction', 'Interactions'], ['order', 'Orders'], ['ticket', 'Tickets'], ['porting', 'Porting']];

/** Everything that happened, newest first. */
export function timelineOf({ interactions, orders, tickets, portingOrders }) {
  const ev = [];
  for (const ix of interactions) ev.push({ kind: 'interaction', at: ix.interactionDate, title: ix.description || ix.reason, meta: `${chan(ix.channel)} · ${ix.direction}${ix.sourceSystem ? ` · via ${ix.sourceSystem}` : ''}`, ref: ix });
  for (const o of orders) ev.push({ kind: 'order', at: o.orderDate, title: `Order ${o.state}: ${o.description || o.id.slice(0, 8)}`, meta: (o.productOrderItem || []).map((i) => i.productOffering?.name).filter(Boolean).slice(0, 3).join(', '), state: o.state, ref: o });
  for (const t of tickets) ev.push({ kind: 'ticket', at: t.creationDate, title: `Ticket ${t.status}: ${t.name}`, meta: t.severity, state: t.status, ref: t });
  for (const po of portingOrders) ev.push({ kind: 'porting', at: po.createdAt || po.scheduledAt || po.requestedAt, title: `${po.direction === 'portOut' ? 'Port-out' : 'Port-in'} ${po.status}: ${po.phoneNumber}`, meta: `${po.otherOperator || '—'} · ${po.country || ''}`, state: po.status, ref: po });
  ev.sort((a, b) => new Date(b.at || 0) - new Date(a.at || 0));
  return ev;
}

export default function Activity({ interactions, interactionsTotal, orders, tickets, portingOrders, onMoreInteractions, onFewerInteractions, reload }) {
  const [filter, setFilter] = useState('all');
  const [openTicket, setOpenTicket] = useState(null);
  const events = timelineOf({ interactions, orders, tickets, portingOrders }).filter((e) => filter === 'all' || e.kind === filter);
  return (
    <div data-testid="activity">
      <div className="tabs small">
        {FILTERS.map(([k, label]) => (
          <button key={k} className={filter === k ? 'tab on' : 'tab'} onClick={() => setFilter(k)}>{label}</button>
        ))}
      </div>
      <div className="rows timeline">
        {!events.length && <p className="dim small">Nothing on record yet.</p>}
        {events.map((e, i) => (
          <div className="row" key={e.kind + (e.ref.id || i)}>
            <div>
              <span className={`chip kind ${e.kind}`}>{e.kind}</span> <span>{e.title}</span>
              <div className="dim small">{dt(e.at)}{e.meta ? ` · ${e.meta}` : ''}</div>
              {e.kind === 'ticket' && openTicket === e.ref.id && <TicketCard ticket={e.ref} onChanged={reload} />}
            </div>
            <div className="rowend">
              {e.state && <span className={`state ${e.state}`}>{e.state}</span>}
              {e.kind === 'ticket' && <button className="ghost small" onClick={() => setOpenTicket(openTicket === e.ref.id ? null : e.ref.id)}>{openTicket === e.ref.id ? 'Close' : 'Open ticket'}</button>}
            </div>
          </div>
        ))}
        {interactions.length < interactionsTotal && (
          <button className="ghost" data-testid="more-interactions" onClick={onMoreInteractions}>
            Show more ({interactionsTotal - interactions.length} older) ↓
          </button>
        )}
        {interactions.length > 5 && <button className="ghost" data-testid="fewer-interactions" onClick={onFewerInteractions}>Show less ↑</button>}
      </div>
    </div>
  );
}
