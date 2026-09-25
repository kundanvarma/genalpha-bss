import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { EMPTY, narrowed, offerDownload, query } from './api.js';
import { accountsSaid, amount, sides, sourceLabel } from './words.js';
import { day, plain } from '../bills/words.js';

/* The journal — every billing and payment event as a business event first.
 *
 * The old page led with "Cash received — 0b3ae95d-…" and four columns of
 * codes. A controller reconciling a month does not read identifiers; they read
 * what happened, to whom, for how much, and only then which accounts it
 * touched. So the row is the event, the disclosure holds the double entry, and
 * the identifiers sit under technical details where a support call can find
 * them.
 *
 * Filters and the export ask the SAME question (api.js builds the query once),
 * and the count above the table is the service's judged total for the filter —
 * never the length of the page, which for a book of thousands would read as a
 * plausible lie.
 */

const PAGE = 50;

function Filters({ draft, setDraft, types, onApply, onClear, busy }) {
  return (
    <form
      data-testid="journal-filters"
      onSubmit={(e) => { e.preventDefault(); onApply(draft); }}
      style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', margin: '0 0 12px' }}
    >
      <label style={{ display: 'grid', fontSize: '0.8rem' }}>
        From
        <input type="date" name="fromDate" data-testid="filter-from" value={draft.fromDate}
          onChange={(e) => setDraft({ ...draft, fromDate: e.target.value })} />
      </label>
      <label style={{ display: 'grid', fontSize: '0.8rem' }}>
        To
        <input type="date" name="toDate" data-testid="filter-to" value={draft.toDate}
          onChange={(e) => setDraft({ ...draft, toDate: e.target.value })} />
      </label>
      <label style={{ display: 'grid', fontSize: '0.8rem' }}>
        Business event
        <select data-testid="filter-source" value={draft.sourceType}
          onChange={(e) => setDraft({ ...draft, sourceType: e.target.value })}>
          <option value="">Every kind</option>
          {types.map((t) => <option key={t} value={t}>{sourceLabel(t)}</option>)}
        </select>
      </label>
      <label style={{ display: 'grid', fontSize: '0.8rem' }}>
        Account code
        <input type="text" data-testid="filter-account" placeholder="e.g. 1200" value={draft.account}
          onChange={(e) => setDraft({ ...draft, account: e.target.value.trim() })} />
      </label>
      <button type="submit" className="ghost" data-testid="filter-apply" disabled={busy}>Apply</button>
      <button type="button" className="ghost" data-testid="filter-clear" onClick={onClear}>Show everything</button>
    </form>
  );
}

/** The double entry, plus the identifiers a support call needs. */
function Posting({ entry }) {
  const { debit, credit, balanced } = sides(entry);
  return (
    <div style={{ padding: '0.6rem 0.2rem 0.9rem' }}>
      <table data-testid="posting-lines" style={{ width: '100%', marginBottom: 8 }}>
        <thead>
          <tr><th>Account</th><th style={{ textAlign: 'right' }}>Debit</th>
            <th style={{ textAlign: 'right' }}>Credit</th><th>What it is</th></tr>
        </thead>
        <tbody>
          {(entry.lines || []).map((l) => (
            <tr key={l.seq}>
              <td>{l.accountName} <span className="dim">{l.accountCode}</span></td>
              <td style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {Number(l.debit) ? amount(l.debit) : ''}</td>
              <td style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {Number(l.credit) ? amount(l.credit) : ''}</td>
              <td>{plain(l.description)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="dim" style={{ margin: '0 0 6px', fontSize: '0.85rem' }}>
        {balanced
          ? `Balanced: ${amount(debit)} on each side, ${entry.currency}.`
          : `NOT balanced: ${amount(debit)} against ${amount(credit)} ${entry.currency}.`}
      </p>
      <details data-testid="posting-technical">
        <summary style={{ cursor: 'pointer', fontSize: '0.85rem' }}>Technical details</summary>
        <dl style={{ margin: '6px 0 0', fontSize: '0.83rem', display: 'grid',
          gridTemplateColumns: 'max-content 1fr', gap: '2px 12px' }}>
          <dt className="dim">Entry</dt><dd style={{ margin: 0 }}>{entry.id}</dd>
          <dt className="dim">Source reference</dt><dd style={{ margin: 0 }}>{entry.sourceRef}</dd>
          <dt className="dim">Kind</dt><dd style={{ margin: 0 }}>{entry.sourceType}</dd>
          {(entry.relatedParty || []).map((p) => (
            <Fragment key={p.id}>
              <dt className="dim">Party ({p.role})</dt><dd style={{ margin: 0 }}>{p.id}</dd>
            </Fragment>
          ))}
        </dl>
      </details>
    </div>
  );
}

export function Journal({ api }) {
  const [filter, setFilter] = useState(EMPTY);
  const [draft, setDraft] = useState(EMPTY);
  const [page, setPage] = useState(0);
  const [state, setState] = useState({ loading: true });
  const [open, setOpen] = useState(null);
  const [types, setTypes] = useState([]);
  const [said, setSaid] = useState('');

  useEffect(() => { api.sourceTypes().then((t) => setTypes(t || [])); }, [api]);

  const load = useCallback(() => {
    let alive = true;
    setState((was) => ({ ...was, loading: true }));
    api.journal(filter, PAGE, page * PAGE).then((answer) => {
      if (!alive) return;
      setState(answer
        ? { loading: false, rows: answer.rows || [], total: answer.total }
        : { loading: false, rows: [], why: 'The subledger could not be reached, so this page would only guess.' });
    });
    return () => { alive = false; };
  }, [api, filter, page]);

  useEffect(load, [load]);

  const apply = (next) => { setFilter(next); setPage(0); setOpen(null); };
  const clear = () => { setDraft(EMPTY); apply(EMPTY); };

  const download = async (format) => {
    setSaid('Preparing the file…');
    const csv = await api.exportCsv(filter, format);
    if (!csv) { setSaid('The subledger would not give up the file.'); return; }
    const q = query(filter).toString();
    offerDownload(csv, `journal-${q ? q.replace(/[^a-z0-9]+/gi, '-') : 'all'}${format ? `-${format}` : ''}.csv`);
    setSaid('Downloaded.');
  };

  const rows = state.rows || [];
  const total = state.total;
  const shown = useMemo(() => {
    const first = page * PAGE;
    return rows.length ? `${first + 1}–${first + rows.length}` : '0';
  }, [rows.length, page]);

  return (
    <section data-testid="journal">
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 13 }}>
        Every billing and payment event as the balanced posting it made. Filter it, then take the same
        question away as a file.
      </p>
      <Filters draft={draft} setDraft={setDraft} types={types} onApply={apply} onClear={clear}
        busy={state.loading} />
      <div style={{ display: 'flex', gap: 10, alignItems: 'baseline', flexWrap: 'wrap', marginBottom: 8 }}>
        <p data-testid="journal-count" className="dim" style={{ margin: 0, fontSize: '0.92rem', color: 'var(--ink)' }}>
          {total === null || total === undefined
            ? `${rows.length} postings on this page.`
            : `${total.toLocaleString()} ${total === 1 ? 'posting' : 'postings'}`
              + `${narrowed(filter) ? ' match this filter' : ' in the book'}, showing ${shown}.`}
        </p>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <button type="button" className="ghost" data-testid="journal-export" onClick={() => download('')}>
            Export CSV
          </button>
          <button type="button" className="ghost" data-testid="journal-export-sap" onClick={() => download('sap')}>
            SAP layout
          </button>
          <button type="button" className="ghost" data-testid="journal-export-netsuite"
            onClick={() => download('netsuite')}>NetSuite layout</button>
        </span>
      </div>
      {said ? <p className="dim" data-testid="journal-said" style={{ margin: '0 0 8px', fontSize: '0.85rem' }}>{said}</p> : null}
      {state.why ? <p className="dim">{state.why}</p> : null}
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Business event</th><th>What happened</th><th>Date</th>
              <th style={{ textAlign: 'right' }}>Amount</th><th>Accounts touched</th></tr>
          </thead>
          <tbody data-testid="journal-body">
            {rows.map((e) => {
              const isOpen = open === e.id;
              const { debit } = sides(e);
              return (
                <Fragment key={e.id}>
                  <tr>
                    <td>
                      <button type="button" data-testid="journal-row" aria-expanded={isOpen}
                        onClick={() => setOpen(isOpen ? null : e.id)}
                        style={{ background: 'none', border: 0, padding: 0, font: 'inherit',
                          color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline' }}>
                        {isOpen ? '▾ ' : '▸ '}{sourceLabel(e.sourceType)}
                      </button>
                    </td>
                    <td>{plain(e.description)}</td>
                    <td>{day(e.entryDate)}</td>
                    <td style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                      {amount(debit)} {e.currency}</td>
                    <td className="dim">{accountsSaid(e)}</td>
                  </tr>
                  {isOpen ? (
                    <tr data-testid="journal-detail">
                      <td colSpan={5} style={{ background: 'var(--card, #fafafa)' }}><Posting entry={e} /></td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
            {!state.loading && rows.length === 0 ? (
              <tr><td colSpan={5} className="dim">No posting matches that.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'center' }}>
        <button type="button" className="ghost" data-testid="journal-prev" disabled={page === 0}
          onClick={() => { setOpen(null); setPage((p) => Math.max(0, p - 1)); }}>Earlier page</button>
        <button type="button" className="ghost" data-testid="journal-next"
          disabled={total !== null && total !== undefined && (page + 1) * PAGE >= total}
          onClick={() => { setOpen(null); setPage((p) => p + 1); }}>Older postings</button>
        <span className="dim" style={{ fontSize: '0.83rem' }}>
          {state.loading ? 'Reading the subledger…' : 'Newest first.'}
        </span>
      </div>
      <p className="dim" style={{ marginTop: 10, fontSize: '0.8rem' }}>
        A posting keeps the account code and name it was born with, so a later change to the chart of
        accounts never rewrites what is above it.
      </p>
    </section>
  );
}
