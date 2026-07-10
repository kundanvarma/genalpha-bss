import { useEffect, useState } from 'react';
import { closeTicket, myTickets, raiseTicket } from '../api.js';

export default function Support() {
  const [tickets, setTickets] = useState(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState(null);

  const load = () => myTickets().then(setTickets).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  async function submit(e) {
    e.preventDefault();
    if (!name.trim()) return;
    try {
      setError(null);
      await raiseTicket(name.trim(), description.trim());
      setName('');
      setDescription('');
      load();
    } catch (err) {
      setError(err.message);
    }
  }

  async function close(id) {
    try {
      await closeTicket(id);
      load();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <>
      <h1>Support</h1>
      {error && <p className="error">{error}</p>}

      <form className="supportform" onSubmit={submit}>
        <input name="name" placeholder="What's wrong? (short summary)" value={name}
               onChange={(e) => setName(e.target.value)} />
        <input name="description" placeholder="Any details that help us…" value={description}
               onChange={(e) => setDescription(e.target.value)} />
        <button className="primary" type="submit" disabled={!name.trim()}>Raise ticket</button>
      </form>

      {!tickets ? <p className="dim">Loading your tickets…</p>
        : !tickets.length ? <p className="dim">No tickets — all running smoothly.</p> : (
        <div className="rows">
          {tickets.map((t) => (
            <div key={t.id}>
              <div className="row">
                <div>
                  <strong>{t.name}</strong>
                  {t.description && <div className="dim small">{t.description}</div>}
                </div>
                <div className="rowend">
                  <span className={`state ${t.status}`}>{t.status}</span>
                  {t.status === 'resolved' && (
                    <button className="ghost" onClick={() => close(t.id)}>Close</button>
                  )}
                </div>
              </div>
              {(t.note || []).map((n, i) => (
                <p className="dim small ticketnote" key={i}>“{n.text}”</p>
              ))}
            </div>
          ))}
        </div>
      )}
    </>
  );
}
