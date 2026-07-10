import { useEffect, useState } from 'react';
import { orgTickets } from '../api.js';
import TicketCard from './TicketCard.jsx';

const STATUSES = ['', 'acknowledged', 'inProgress', 'resolved', 'closed'];

export default function Tickets() {
  const [status, setStatus] = useState('');
  const [tickets, setTickets] = useState(null);
  const [error, setError] = useState(null);

  const load = () => orgTickets(status).then(setTickets).catch((e) => setError(e.message));
  useEffect(() => { load(); }, [status]);

  return (
    <>
      <h1>Ticket queue</h1>
      <div className="tabs">
        {STATUSES.map((s) => (
          <button key={s || 'all'} className={status === s ? 'tab on' : 'tab'}
                  onClick={() => setStatus(s)}>
            {s || 'all'}
          </button>
        ))}
      </div>
      {error && <p className="error">{error}</p>}
      {!tickets ? <p className="dim">Loading…</p> : !tickets.length
        ? <p className="dim">No tickets in this view.</p>
        : tickets.map((t) => <TicketCard key={t.id} ticket={t} onChanged={load} />)}
    </>
  );
}
