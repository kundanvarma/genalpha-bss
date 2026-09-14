import { Link } from 'react-router-dom';
import { LineDoctor } from './Services.jsx';
import { myActiveServices } from '../api.js';
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
  const [services, setServices] = useState(null);

  const load = () => myTickets().then(setTickets).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);
  useEffect(() => { myActiveServices().then((svcs) => setServices((Array.isArray(svcs) ? svcs : []).filter((sv) => sv.state === 'active'))).catch(() => setServices([])); }, []);

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
      <h1>{t('Support')}</h1>
      {error && <p className="error">{error}</p>}

      {/* guided: the line first, because most calls are "it does not work" */}
      <div className="support-guide" data-testid="support-guide">
        <section className="card step" data-testid="support-step">
          <h2>1 · {t('Check your line')}</h2>
          {services === null && <p className="dim small">{t('Reading your services…')}</p>}
          {services && !services.length && <p className="dim small">{t('No active service on this account yet.')}</p>}
          {(services || []).slice(0, 6).map((sv) => (
            <div key={sv.id} className="row" style={{ border: 'none', padding: '4px 0' }}>
              <span>{sv.name}{(sv.supportingResource || []).find((r) => r.value) ? <span className="msisdn"> {(sv.supportingResource || []).find((r) => r.value).value}</span> : null}</span>
              <LineDoctor serviceId={sv.id} />
            </div>
          ))}
          {services && services.length > 6 && <p className="dim small">{t('More lines under')} <Link to="/services">{t('Services')}</Link>.</p>}
          <p className="dim small">{t('Outage, out of data or paused — the check says which, so you know what to do next.')}</p>
        </section>
        <section className="card step" data-testid="support-step">
          <h2>2 · {t('Find the answer')}</h2>
          <Faq />
        </section>
        <section className="card step" data-testid="support-step">
          <h2>3 · {t('Talk to us')}</h2>
          <p className="dim small">{t('Chat with us from the bubble at the bottom right, or raise a case below and we come back to you.')}</p>
        </section>
      </div>

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
