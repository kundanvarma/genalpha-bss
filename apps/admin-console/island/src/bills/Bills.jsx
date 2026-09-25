import { useEffect, useMemo, useState } from 'react';
import { reader, customerId } from './api.js';
import { CHIPS, CLEAN, SITUATIONS, day, labelOf, money, period, situationOf, toneOf } from './words.js';
import { Workspace } from './Workspace.jsx';

/* Bills — the operational centre of the Billing & Revenue desk.
 *
 * The customer context is implicit here, so the page is "Bills". The bill
 * number is the way in; there is no repeated View button. Every state on
 * screen is the situation billing computed, in words, and the chips count the
 * same block rather than deciding lateness for themselves. */

function Tone({ value, title }) {
  const tone = toneOf(value);
  const colour = { bad: 'var(--danger, #b3261e)', warn: '#b45309', quiet: 'var(--dim)' }[tone] || 'var(--ink)';
  return (
    <span title={title || ''} style={{ color: colour, fontWeight: tone === 'bad' ? 600 : 400 }}>
      {labelOf(value)}
    </span>
  );
}

function Chips({ counts, active, onPick }) {
  return (
    <div className="chips" style={{ margin: '0 0 14px' }}>
      {CHIPS.map((key) => {
        const n = counts[key] || 0;
        const on = active === key;
        return (
          <button
            key={key}
            type="button"
            className={`chip${on ? ' on' : ''}`}
            data-testid={`chip-${key}`}
            aria-pressed={on}
            onClick={() => onPick(on ? null : key)}
          >
            {n === 0 ? `✓ ${CLEAN[key]}` : `${n} ${SITUATIONS[key].label.toLowerCase()}`}
          </button>
        );
      })}
      {active ? (
        <button type="button" className="chip" onClick={() => onPick(null)}>Show all</button>
      ) : null}
    </div>
  );
}

export function Bills({ authFetch }) {
  const api = useMemo(() => reader(authFetch), [authFetch]);
  const [state, setState] = useState({ loading: true, bills: [] });
  const [names, setNames] = useState({});
  const [filter, setFilter] = useState(null);
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(null);

  useEffect(() => {
    let alive = true;
    api.bills(100).then(async (bills) => {
      if (!alive) return;
      if (!bills) { setState({ loading: false, bills: [], why: 'Billing could not be reached.' }); return; }
      setState({ loading: false, bills });
      const ids = [...new Set(bills.map(customerId).filter(Boolean))];
      const pairs = await Promise.all(ids.map(async (id) => [id, await api.partyName(id)]));
      if (alive) setNames(Object.fromEntries(pairs));
    });
    return () => { alive = false; };
  }, [api]);

  const counts = useMemo(() => {
    const c = {};
    for (const b of state.bills) {
      const v = situationOf(b).value;
      if (v) c[v] = (c[v] || 0) + 1;
    }
    return c;
  }, [state.bills]);

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase();
    return state.bills.filter((b) => {
      if (filter && situationOf(b).value !== filter) return false;
      if (!q) return true;
      const who = names[customerId(b)] || '';
      return `${b.billNo} ${who} ${(b.billingAccount || {}).name || ''}`.toLowerCase().includes(q);
    });
  }, [state.bills, filter, search, names]);

  if (open) {
    return <Workspace api={api} id={open.id} billNo={open.billNo} who={names[customerId(open)]} onBack={() => setOpen(null)} />;
  }
  if (state.loading) return <p>Loading bills…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  return (
    <section data-testid="bills-desk">
      {/* the shell's page head already names the desk; this line is the goal and
          the search, and the chips beneath are the only counts on the page */}
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, flexWrap: 'wrap', marginBottom: 10 }}>
        <p className="dim" style={{ margin: 0, fontSize: 13 }}>
          Goal: every bill is right, on time and paid. Watch: overdue and disputes.
          <span> {state.bills.length} on this page.</span>
        </p>
        <input
          type="search"
          aria-label="Search bills"
          placeholder="Search customer, bill number or account…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ marginLeft: 'auto', minWidth: 280 }}
        />
      </div>
      <Chips counts={counts} active={filter} onPick={setFilter} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Bill</th><th>Customer</th><th>Period</th><th>Amount</th><th>Situation</th><th>Due</th>
            </tr>
          </thead>
          <tbody data-testid="bills-body">
            {rows.map((b) => {
              const s = situationOf(b);
              return (
                <tr key={b.id}>
                  <td>
                    <button
                      type="button"
                      className="linklike"
                      data-testid="bill-link"
                      onClick={() => setOpen(b)}
                      style={{ background: 'none', border: 0, padding: 0, color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline', font: 'inherit' }}
                    >
                      {b.billNo}
                    </button>
                  </td>
                  <td>{names[customerId(b)] || '…'}</td>
                  <td>{period(b.billingPeriod)}</td>
                  <td>{money(b.amountDue)}</td>
                  <td><Tone value={s.value} title={s.reason} /></td>
                  <td>{day(b.dueDate || s.currentDueDate)}</td>
                </tr>
              );
            })}
            {rows.length === 0 ? (
              <tr><td colSpan={6} className="dim">Nothing matches that.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </section>
  );
}
