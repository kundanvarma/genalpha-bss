import { useEffect, useState } from 'react';
import { closeTicket, myTickets, raiseTicket, searchFaq } from '../api.js';
import { t } from '../i18n.js';

/** Answers first, tickets second: the FAQ shelf sits above the ticket form,
 * searched live from the knowledge base — most questions never need a human. */
function Faq() {
  const [q, setQ] = useState('');
  const [articles, setArticles] = useState([]);
  const [open, setOpen] = useState(null);

  const search = (term) => { searchFaq(term).then(setArticles).catch(() => {}); };
  // type-ahead: the FAQ refreshes 300ms after the last keystroke
  useEffect(() => {
    const t = setTimeout(() => { search(q); }, q ? 300 : 0);
    return () => clearTimeout(t);
  }, [q]);

  return (
    <section className="lobcard" data-testid="faq-card">
      <h2>{t('Quick answers')}</h2>
      <div style={{ display: 'flex', gap: 8, margin: '6px 0' }}>
        <input placeholder={t('Search the FAQ…')} value={q} data-testid="faq-search"
          style={{ flex: 1 }} onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && search(q)} />
        <button className="ghost" data-testid="faq-go" onClick={() => search(q)}>{t('Search')}</button>
      </div>
      {articles.map((a) => (
        <div key={a.id} data-testid={`faq-${a.id}`} style={{ margin: '6px 0', cursor: 'pointer' }}
          onClick={() => setOpen(open === a.id ? null : a.id)}>
          <strong>{open === a.id ? '▾' : '▸'} {a.title}</strong>
          {open === a.id && <p className="dim" style={{ whiteSpace: 'pre-wrap' }} data-testid="faq-body">{a.body}</p>}
        </div>
      ))}
      {!articles.length && <p className="dim">{t('Nothing found — raise a ticket below.')}</p>}
    </section>
  );
}

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

      <Faq />

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
