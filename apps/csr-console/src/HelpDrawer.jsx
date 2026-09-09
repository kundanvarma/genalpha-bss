import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { askKnowledge, searchKnowledge, shelfKnowledge } from './api.js';
import { hasRole } from './auth.js';

/** Contextual help: the published articles tagged for THIS screen (csr:<route>),
 * audience-gated by the server from the agent's token. Search all help, and — with
 * ai:use — ask; the answer is grounded on the same shelf and cached server-side. */
export default function HelpDrawer() {
  const { pathname } = useLocation();
  const context = 'csr:' + (pathname.split('/')[1] || 'customers').replace('customer', 'customers');
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [articles, setArticles] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return undefined;
    const t = setTimeout(() => {
      (q.trim() ? searchKnowledge(q.trim()) : shelfKnowledge(context)).then(setArticles).catch(() => setArticles([]));
    }, q ? 300 : 0);
    return () => clearTimeout(t);
  }, [open, q, context]);

  async function ask() {
    setBusy(true); setAnswer(null);
    try { setAnswer(await askKnowledge(q.trim(), context)); } catch (e) { setAnswer({ answer: e.message }); }
    setBusy(false);
  }

  return (
    <>
      <button type="button" className="ghost help-button" title="Help for this page" data-testid="help-button"
        onClick={() => setOpen((v) => !v)}>?</button>
      {open && (
        <aside className="help-drawer" data-testid="help-drawer">
          <div className="help-head"><h2>Help</h2><button type="button" className="ghost" data-testid="help-close" onClick={() => setOpen(false)}>×</button></div>
          <input placeholder="Search all help…" value={q} data-testid="help-search" onChange={(e) => setQ(e.target.value)} />
          <div data-testid="help-list">
            {!articles.length && <p className="dim" data-testid="help-empty">{q.trim() ? 'Nothing found. Try other words.' : 'No help written for this page yet.'}</p>}
            {articles.map((a) => (
              <details key={a.id} data-testid="help-article"><summary>{a.title}</summary><div className="help-body">{a.body}</div></details>
            ))}
          </div>
          {hasRole('ai:use') && (
            <div className="help-ask">
              <button type="button" className="primary" data-testid="help-ask" disabled={!q.trim() || busy} onClick={ask}>{busy ? 'Asking…' : '✨ Ask'}</button>
              {answer && (
                <div className="help-answer" data-testid="help-answer">
                  <p>{answer.answer}</p>
                  {(answer.sources || []).length > 0 && <p className="dim">Sources: {answer.sources.map((s) => s.title).join(' · ')}{answer.cached ? ' · cached answer' : ''}</p>}
                </div>
              )}
            </div>
          )}
        </aside>
      )}
    </>
  );
}
