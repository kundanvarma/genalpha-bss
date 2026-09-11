import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { orgTickets, getCustomer } from '../api.js';
import TicketCard from './TicketCard.jsx';

/* The queue as a queue: dense rows with what triage needs — severity, age,
 * customer, status — and the live ticket beside it, so an agent never leaves
 * the list to work one. What the ticket does not carry (an owner, an SLA
 * clock) is not invented; age and time-in-state are computed from what it has. */
const STATUSES = ['', 'acknowledged', 'inProgress', 'resolved', 'closed'];
const SEV_RANK = { critical: 0, major: 1, minor: 2 };

function age(iso) {
  if (!iso) return '';
  const ms = Date.now() - new Date(iso).getTime();
  if (Number.isNaN(ms) || ms < 0) return '';
  const m = Math.floor(ms / 60000);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h}h ${m % 60}m`;
  return `${Math.floor(h / 24)}d`;
}

export default function Tickets() {
  const [status, setStatus] = useState('');
  const [tickets, setTickets] = useState(null);
  const [selected, setSelected] = useState(null);
  const [error, setError] = useState(null);
  const [names, setNames] = useState({}); // partyId -> display name, fetched once per id

  const load = () => orgTickets(status).then((rows) => {
    const sorted = [...rows].sort((a, b) => (SEV_RANK[a.severity] ?? 9) - (SEV_RANK[b.severity] ?? 9) || new Date(a.creationDate || 0) - new Date(b.creationDate || 0));
    setTickets(sorted);
    setSelected((s) => (s && sorted.find((t) => t.id === s.id)) || sorted[0] || null);
  }).catch((e) => setError(e.message));
  useEffect(() => { load(); }, [status]);
  useEffect(() => {
    const ids = [...new Set((tickets || []).map((t) => (t.relatedParty || [])[0]?.id).filter(Boolean))].filter((i) => !(i in names)).slice(0, 40);
    if (!ids.length) return;
    Promise.all(ids.map((i) => getCustomer(i).then((c) => [i, `${c.givenName || ''} ${c.familyName || ''}`.trim() || i.slice(0, 8)]).catch(() => [i, i.slice(0, 8)])))
      .then((pairs) => setNames((n) => ({ ...n, ...Object.fromEntries(pairs) })));
  }, [tickets]);

  const open = (tickets || []).filter((t) => t.status !== 'closed' && t.status !== 'resolved').length;

  return (
    <>
      <h1>Ticket queue{tickets && <span className="dim small"> — {open} open{status ? ` · showing ${status}` : ''}</span>}</h1>
      <div className="tabs">
        {STATUSES.map((s) => (
          <button key={s || 'all'} className={status === s ? 'tab on' : 'tab'} onClick={() => setStatus(s)}>
            {s || 'all'}
          </button>
        ))}
      </div>
      {error && <p className="error">{error}</p>}
      {!tickets ? <p className="dim">Loading…</p> : !tickets.length ? <p className="dim">No tickets in this view.</p> : (
        <div className="queue" data-testid="ticket-queue">
          <div className="queue-list" role="list">
            <div className="queue-head dim small"><span>Sev</span><span>Issue</span><span>Customer</span><span>Age</span><span>In state</span><span>Status</span></div>
            {tickets.map((t) => (
              <div key={t.id} role="listitem" className={`ticket queue-row${selected?.id === t.id ? ' on' : ''}`} data-testid="queue-row"
                   tabIndex={0} onClick={() => setSelected(t)} onKeyDown={(e) => { if (e.key === 'Enter') setSelected(t); }}>
                <span className={`sev sev-${t.severity || 'minor'}`} title={t.severity}>{(t.severity || 'minor')[0].toUpperCase()}</span>
                <span className="queue-issue"><strong>{t.name}</strong></span>
                <span className="small queue-who">{names[(t.relatedParty || [])[0]?.id] || <span className="dim mono">{((t.relatedParty || [])[0]?.id || '').slice(0, 8)}</span>}</span>
                <span className="dim small">{age(t.creationDate)}</span>
                <span className="dim small">{age(t.statusChangeDate || t.creationDate)}</span>
                <span className={`state ${t.status}`}>{t.status}</span>
              </div>
            ))}
          </div>
          <div className="queue-detail" data-testid="ticket-detail">
            {selected ? (
              <>
                <div className="row" style={{ borderBottom: 'none', padding: '0 4px 6px' }}>
                  <span className="dim small">Ticket {selected.id.slice(0, 8)} · raised {selected.creationDate ? new Date(selected.creationDate).toLocaleString() : '—'}</span>
                  {(selected.relatedParty || [])[0]?.id && (
                    <Link className="ghost small linkbtn" data-testid="queue-open-customer" to={`/customer/${selected.relatedParty[0].id}`}>Open customer →</Link>
                  )}
                </div>
                <TicketCard ticket={selected} onChanged={load} />
              </>
            ) : <p className="dim">Pick a ticket.</p>}
          </div>
        </div>
      )}
    </>
  );
}
