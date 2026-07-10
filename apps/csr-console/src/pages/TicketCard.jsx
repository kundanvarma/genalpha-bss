import { useState } from 'react';
import { workTicket } from '../api.js';

const NEXT = {
  acknowledged: ['inProgress', 'resolved'],
  inProgress: ['resolved'],
  resolved: ['closed', 'inProgress'],
};

export default function TicketCard({ ticket, onChanged }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);

  async function change(status) {
    try {
      setError(null);
      await workTicket(ticket.id, {
        ...(status ? { status } : {}),
        ...(note.trim() ? { note: [{ text: note.trim() }] } : {}),
      });
      setNote('');
      onChanged();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <div className="ticket">
      <div className="row">
        <div>
          <strong>{ticket.name}</strong>
          <div className="dim small">{ticket.severity} · {(ticket.relatedParty || [])[0]?.id}</div>
        </div>
        <span className={`state ${ticket.status}`}>{ticket.status}</span>
      </div>
      {(ticket.note || []).map((n, i) => (
        <p className="dim small ticketnote" key={i}>“{n.text}” — {n.author}</p>
      ))}
      {error && <p className="error small">{error}</p>}
      {NEXT[ticket.status] && (
        <div className="stack">
          <input name="ticketNote" placeholder="Add a note…" value={note}
                 onChange={(e) => setNote(e.target.value)} />
          {note.trim() && <button className="ghost" onClick={() => change(null)}>Note only</button>}
          {NEXT[ticket.status].map((s) => (
            <button className="ghost" key={s} onClick={() => change(s)}>→ {s}</button>
          ))}
        </div>
      )}
    </div>
  );
}
