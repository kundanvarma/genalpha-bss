import { useEffect, useState } from 'react';
import { askKnowledge, searchKnowledge } from '../api.js';
import { hasRole } from '../auth.js';

/**
 * The agent's library: FAQs, cheat-sheets and how-tos, searched live —
 * audience-filtered by the agent's own token (customers' articles plus the
 * CSR shelf). With ai:use, "Ask" turns a question into a grounded answer
 * with the source articles named — retrieval runs AS the agent, so the
 * answer can only draw on what they could read themselves.
 */
export default function Knowledge() {
  const [q, setQ] = useState('');
  const [articles, setArticles] = useState([]);
  const [open, setOpen] = useState(null);
  const [answer, setAnswer] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const search = (term) => {
    searchKnowledge(term).then(setArticles).catch((e) => setError(e.message));
  };
  useEffect(() => { search(''); }, []);

  async function ask() {
    setBusy(true); setAnswer(null); setError(null);
    try {
      setAnswer(await askKnowledge(q));
    } catch (e) { setError(e.message); }
    setBusy(false);
  }

  return (
    <>
      <h1>Knowledge</h1>
      <form className="searchbar" onSubmit={(e) => { e.preventDefault(); search(q); }}>
        <input placeholder="Search FAQs, cheat-sheets, how-tos…" value={q}
          data-testid="kb-search" onChange={(e) => setQ(e.target.value)} />
        <button type="submit" data-testid="kb-go">Search</button>
        {hasRole('ai:use') && (
          <button type="button" data-testid="kb-ask" disabled={!q.trim() || busy} onClick={ask}>
            {busy ? 'Asking…' : '✨ Ask'}
          </button>
        )}
      </form>
      {error && <p className="error">{error}</p>}
      {answer && (
        <section className="copilot" data-testid="kb-answer">
          <p>{answer.answer}</p>
          {(answer.sources || []).length > 0 && (
            <p className="dim" data-testid="kb-sources">
              Sources: {answer.sources.map((s) => s.title).join(' · ')}
            </p>
          )}
        </section>
      )}
      {articles.map((a) => (
        <section className="card" key={a.id} data-testid={`kb-article-${a.id}`}
          style={{ padding: '10px 16px', marginBottom: 10, cursor: 'pointer' }}
          onClick={() => setOpen(open === a.id ? null : a.id)}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            <strong>{a.title}</strong>
            <span className="state active">{a.audience}</span>
            {a.category && <span className="dim">{a.category}</span>}
          </div>
          {open === a.id && <p style={{ whiteSpace: 'pre-wrap' }} data-testid="kb-body">{a.body}</p>}
        </section>
      ))}
      {!articles.length && !error && <p className="dim">Nothing found — try other words.</p>}
    </>
  );
}
