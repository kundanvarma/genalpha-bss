import { useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { t } from './i18n.js';
import { authFetch } from './auth.js';

const KNOWLEDGE = '/tmf-api/knowledgeManagement/v4';

/** Contextual help for shoppers and customers: the articles tagged for THIS page
 * (shop:<route>). The server's audience gate means a customer only ever sees
 * customer-facing articles — never staff how-tos. No AI here: search, then Support. */
export default function HelpDrawer() {
  const { pathname } = useLocation();
  const context = 'shop:' + (pathname.split('/')[1] || 'offers');
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [articles, setArticles] = useState([]);

  useEffect(() => {
    if (!open) return undefined;
    const timer = setTimeout(async () => {
      try {
        const url = q.trim() ? `${KNOWLEDGE}/article?q=${encodeURIComponent(q.trim())}` : `${KNOWLEDGE}/article?tag=${encodeURIComponent(context)}`;
        const r = await authFetch(url);
        setArticles(r.ok ? await r.json() : []);
      } catch { setArticles([]); }
    }, q ? 300 : 0);
    return () => clearTimeout(timer);
  }, [open, q, context]);

  return (
    <>
      <button type="button" className="ghost help-button" title={t('Help for this page')} data-testid="help-button"
        onClick={() => setOpen((v) => !v)}>?</button>
      {open && (
        <aside className="help-drawer" data-testid="help-drawer">
          <div className="help-head"><h2>{t('Help')}</h2><button type="button" className="ghost" data-testid="help-close" onClick={() => setOpen(false)}>×</button></div>
          <input placeholder={t('Search help…')} value={q} data-testid="help-search" onChange={(e) => setQ(e.target.value)} />
          <div data-testid="help-list">
            {!articles.length && <p className="dim" data-testid="help-empty">{q.trim() ? t('Nothing found — try other words.') : t('No help for this page yet.')}</p>}
            {articles.map((a) => (
              <details key={a.id} data-testid="help-article"><summary>{a.title}</summary><div className="help-body">{a.body}</div></details>
            ))}
          </div>
          <p className="dim"><Link to="/support" onClick={() => setOpen(false)}>{t('Still stuck? Support & FAQ')}</Link></p>
        </aside>
      )}
    </>
  );
}
