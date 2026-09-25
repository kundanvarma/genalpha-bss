import { Fragment, useCallback, useEffect, useState } from 'react';
import { ProposeForm } from './Propose.jsx';
import { setting } from './words.js';
import { plain } from '../bills/words.js';

/* The chart of accounts — what each posting key books, and which of the
 * tenant's own accounts it books into.
 *
 * Two things make this page different from the table it replaces. First, the
 * business description leads: "What customers owe" before "1200", and the
 * posting key — an identifier — lives under technical details. Second, nothing
 * here edits anything. These settings decide which account real money lands
 * in, so a change is PROPOSED, and it climbs the ladder on the Configuration
 * page: drafted, validated, approved, activated.
 */

/** An account already carrying postings says so, quietly but plainly. */
function Used({ n }) {
  if (!n) return <span className="dim">not yet used</span>;
  return (
    <span title="Booked lines keep the code they were born with, so moving this account leaves them behind.">
      {n.toLocaleString()} {n === 1 ? 'posting' : 'postings'}
    </span>
  );
}

function Row({ row, open, onToggle, change, onPropose }) {
  const isOpen = open === row.key;
  return (
    <Fragment>
      <tr>
        <td>
          <button type="button" data-testid="chart-row" aria-expanded={isOpen}
            onClick={() => onToggle(isOpen ? null : row.key)}
            style={{ background: 'none', border: 0, padding: 0, font: 'inherit',
              color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline' }}>
            {isOpen ? '▾ ' : '▸ '}{row.accountName}
          </button>
        </td>
        <td style={{ fontVariantNumeric: 'tabular-nums' }}>{row.accountCode}</td>
        <td>{row.setting ? `${setting(row.configValue)} (${row.setting})` : <span className="dim">—</span>}</td>
        <td><Used n={row.postings || 0} /></td>
        <td>{change
          ? <span data-testid="chart-pending" style={{ color: '#b45309' }}>a change is on the ladder</span>
          : <span className="dim">settled</span>}</td>
      </tr>
      {isOpen ? (
        <tr data-testid="chart-detail">
          <td colSpan={5} style={{ background: 'var(--card, #fafafa)', padding: '0.7rem 0.9rem' }}>
            <p data-testid="chart-books" style={{ margin: '0 0 8px' }}>{row.books}</p>
            {change ? (
              <p style={{ margin: '0 0 8px', color: '#b45309' }} data-testid="chart-pending-detail">
                {plain(change.summary)} {change.nextStep}
              </p>
            ) : (
              <button type="button" className="ghost" data-testid="chart-propose"
                onClick={() => onPropose(row)}>Propose a change</button>
            )}
            <details data-testid="chart-technical" style={{ marginTop: 10 }}>
              <summary style={{ cursor: 'pointer', fontSize: '0.85rem' }}>Technical details</summary>
              <dl style={{ margin: '6px 0 0', fontSize: '0.83rem', display: 'grid',
                gridTemplateColumns: 'max-content 1fr', gap: '2px 12px' }}>
                <dt className="dim">Posting key</dt><dd style={{ margin: 0 }}>{row.key}</dd>
                <dt className="dim">Account code</dt><dd style={{ margin: 0 }}>{row.accountCode}</dd>
                {row.setting ? (
                  <Fragment>
                    <dt className="dim">Setting</dt>
                    <dd style={{ margin: 0 }}>{setting(row.configValue)} — {row.setting}</dd>
                  </Fragment>
                ) : null}
              </dl>
            </details>
          </td>
        </tr>
      ) : null}
    </Fragment>
  );
}

export function Chart({ api, onDrafted }) {
  const [state, setState] = useState({ loading: true });
  const [open, setOpen] = useState(null);
  const [search, setSearch] = useState('');
  const [proposing, setProposing] = useState(null);

  const load = useCallback(async () => {
    const [chart, changes] = await Promise.all([api.chart(), api.changes()]);
    if (!chart) {
      return { loading: false, rows: [], why: 'The subledger could not be reached.' };
    }
    // how many postings each account carries arrives WITH the row: the
    // subledger counts once, the page never asks thirty times
    return { loading: false, rows: chart, changes: changes || [] };
  }, [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setState(next); });
    return () => { alive = false; };
  }, [load]);

  const refresh = async () => setState(await load());

  if (state.loading) return <p>Reading the chart of accounts…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  const openChange = (key) => (state.changes || []).find(
    (c) => c.postingKey === key && ['draft', 'validated', 'approved'].includes(c.state));
  const q = search.trim().toLowerCase();
  const rows = state.rows.filter((r) => !q
    || `${r.accountName} ${r.accountCode} ${r.books || ''}`.toLowerCase().includes(q));

  if (proposing) {
    return (
      <ProposeForm
        api={api}
        chart={state.rows}
        row={proposing.row}
        onCancel={() => setProposing(null)}
        onDrafted={async (change) => { setProposing(null); await refresh(); onDrafted(change); }}
      />
    );
  }

  return (
    <section data-testid="chart">
      <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', flexWrap: 'wrap', marginBottom: 10 }}>
        <p className="dim" style={{ margin: 0, fontSize: 13 }}>
          Which of your accounts each kind of money books into. Nothing is edited here: a change is
          proposed, and it becomes live on the Configuration page.
        </p>
        <input type="search" aria-label="Search the chart of accounts"
          placeholder="Search an account, a code or what it books…" value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ marginLeft: 'auto', minWidth: 280 }} />
        <button type="button" className="ghost" data-testid="chart-new"
          onClick={() => setProposing({ row: null })}>+ New account</button>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Account</th><th>Code</th><th>Setting</th><th>Used by</th><th>Standing</th></tr>
          </thead>
          <tbody data-testid="chart-body">
            {rows.map((r) => (
              <Row key={r.key} row={r} open={open} onToggle={setOpen}
                change={openChange(r.key)} onPropose={(picked) => setProposing({ row: picked })} />
            ))}
            {rows.length === 0 ? (
              <tr><td colSpan={5} className="dim">No account matches that.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
      <p className="dim" style={{ marginTop: 10, fontSize: '0.8rem' }}>
        A change applies to future postings only. Everything already booked keeps the account code and
        name it was born with, which is why an account carrying postings cannot simply be moved.
      </p>
    </section>
  );
}
