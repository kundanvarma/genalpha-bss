import { useState } from 'react';
import { aiTicketReply, judgeIncident, workTicket } from '../api.js';
import { hasRole } from '../auth.js';

const NEXT = {
  acknowledged: ['inProgress', 'resolved'],
  inProgress: ['resolved'],
  resolved: ['closed', 'inProgress'],
};

export default function TicketCard({ ticket, onChanged, trace }) {
  const [note, setNote] = useState('');
  const [error, setError] = useState(null);
  const [drafting, setDrafting] = useState(false);
  const [judged, setJudged] = useState(null);

  /* WAS THE MACHINE'S DIAGNOSIS ANY GOOD? The agent already wrote it onto this
   * ticket as a note, so the answer is on the screen in front of whoever is
   * reading. Until now there was nowhere to say so — and three "useful"
   * verdicts on one signature are what draft a runbook that makes every later
   * diagnosis of it deterministic and free. (#285) */
  async function judge(useful) {
    try {
      setError(null);
      await judgeIncident(trace.id, useful, null);
      setJudged(useful ? 'useful' : 'not-useful'); // the store's spelling, so one branch covers both
    } catch (e) {
      setError('Verdict: ' + e.message);
    }
  }

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

  // The copilot drafts into the note box; the agent edits and sends.
  async function draftReply() {
    setDrafting(true);
    try {
      setError(null);
      const draft = await aiTicketReply({
        ticket: {
          name: ticket.name,
          status: ticket.status,
          severity: ticket.severity,
          notes: (ticket.note || []).map((n) => n.text),
        },
      });
      setNote(draft.reply);
    } catch (e) {
      setError('Copilot: ' + e.message);
    } finally {
      setDrafting(false);
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
      {trace && hasRole('ai:use') && (
        <div className="stack" data-testid="diagnosis-verdict">
          {judged || trace.verdict !== 'pending' ? (
            <span className="dim small">{(judged || trace.verdict) === 'useful'
              ? 'Diagnosis marked useful — it counts toward a runbook for this kind of fault.'
              : 'Diagnosis marked not useful — it will not be reused.'}</span>
          ) : (
            <>
              <span className="dim small">Did the agent’s diagnosis help?</span>
              <button className="ghost" data-testid="diagnosis-useful" onClick={() => judge(true)}>Useful</button>
              <button className="ghost" data-testid="diagnosis-not-useful" onClick={() => judge(false)}>Not useful</button>
            </>
          )}
        </div>
      )}
      {error && <p className="error small">{error}</p>}
      {NEXT[ticket.status] && (
        <div className="stack">
          <input name="ticketNote" placeholder="Add a note…" value={note}
                 onChange={(e) => setNote(e.target.value)} />
          {hasRole('ai:use') && (
            <button className="ghost" data-testid="draft-reply" onClick={draftReply} disabled={drafting}>
              {drafting ? 'Drafting…' : '✨ Draft reply'}
            </button>
          )}
          {note.trim() && <button className="ghost" onClick={() => change(null)}>Note only</button>}
          {NEXT[ticket.status].map((s) => (
            <button className="ghost" key={s} onClick={() => change(s)}>→ {s}</button>
          ))}
        </div>
      )}
    </div>
  );
}
