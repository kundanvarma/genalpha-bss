import { useMemo, useState } from 'react';
import { Chart } from './Chart.jsx';
import { Journal } from './Journal.jsx';
import { reader } from './api.js';

/* Accounting — the journal and the chart of accounts, one destination.
 *
 * They were two peer tabs among ten, which meant a controller reconciling a
 * month had to know the data model to find either. They are one job: what the
 * books say, and what the books are told to say. So they are one page with two
 * views, and the page is a React island (ADR-0022) because row disclosure and
 * a filter bar are more than the generic table can express.
 */

const VIEWS = [
  { key: 'journal', label: 'Journal', goal: 'Goal: every event on the books, and the file the ledger ingests.' },
  { key: 'chart', label: 'Chart of accounts', goal: 'Goal: every kind of money books where finance says it does.' },
];

export function Accounting({ authFetch, view: opened }) {
  const api = useMemo(() => reader(authFetch), [authFetch]);
  // several tabs share this island: Journal and Chart of accounts each land on
  // their own view, so a deep link and a suite still reach what they named
  const [view, setView] = useState(VIEWS.some((v) => v.key === opened) ? opened : 'journal');
  const [drafted, setDrafted] = useState(null);

  const current = VIEWS.find((v) => v.key === view) || VIEWS[0];

  return (
    <section data-testid="accounting">
      <div className="chips" style={{ margin: '0 0 10px' }}>
        {VIEWS.map((v) => (
          <button key={v.key} type="button" className={`chip${v.key === view ? ' on' : ''}`}
            data-testid={`accounting-${v.key}`} aria-pressed={v.key === view}
            onClick={() => { setView(v.key); setDrafted(null); }}>
            {v.label}
          </button>
        ))}
      </div>
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 13 }}>{current.goal}</p>
      {drafted ? (
        <p data-testid="accounting-drafted"
          style={{ margin: '0 0 12px', padding: '0.7rem 0.9rem', borderRadius: 10,
            border: '1px solid var(--line, #ddd)' }}>
          Written down: {drafted.summary} It changes nothing yet — it waits on Configuration, where it is
          validated, approved and then activated.{' '}
          <button type="button" className="ghost" data-testid="accounting-to-configuration"
            onClick={() => (window.consoleGoTo ? window.consoleGoTo('financialConfiguration') : null)}>
            Open Configuration
          </button>
        </p>
      ) : null}
      {view === 'journal'
        ? <Journal api={api} />
        : <Chart api={api} onDrafted={setDrafted} />}
    </section>
  );
}
