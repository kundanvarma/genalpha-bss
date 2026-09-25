import { useEffect, useMemo, useState } from 'react';
import { deskReader, partyOf, totals } from './deskApi.js';
import { amounts, Calm, Head } from './parts.jsx';
import { day, money, plain } from '../bills/words.js';
import { holdWord, isRunning, STAGE } from './ladder.js';
import { Case } from './Case.jsx';

/* Collections — payment failure and recovery, as its own workflow.
 *
 * A case is the unit of work here, not a bill: one customer, one ladder, one
 * conversation. The list says who is in trouble and what holds each case; a
 * case opens into the six things the paper asks a collections agent to have
 * in one place.
 */

export function Collections({ authFetch }) {
  const api = useMemo(() => deskReader(authFetch), [authFetch]);
  const [s, setS] = useState({ loading: true });
  const [names, setNames] = useState({});
  const [open, setOpen] = useState(null);

  useEffect(() => {
    let alive = true;
    api.cases().then(async (cases) => {
      if (!alive) return;
      if (!cases) { setS({ loading: false, why: 'Collections could not be reached.' }); return; }
      setS({ loading: false, cases });
      // a hundred cases means a hundred lookups; asked all at once the browser
      // and the gateway drop some, and the rows that lost the race read "this
      // customer". Eight at a time, and the names arrive as they resolve.
      const ids = [...new Set(cases.map(partyOf).filter(Boolean))];
      for (let i = 0; i < ids.length && alive; i += 8) {
        const batch = ids.slice(i, i + 8);
        const pairs = await Promise.all(batch.map(async (id) => [id, await api.partyName(id)]));
        if (alive) setNames((was) => ({ ...was, ...Object.fromEntries(pairs) }));
      }
    });
    return () => { alive = false; };
  }, [api]);

  if (open) {
    return <Case api={api} id={open.id} who={names[partyOf(open)]} onBack={() => setOpen(null)} />;
  }
  if (s.loading) return <p>Reading collection cases…</p>;
  if (s.why) return <p className="dim">{s.why}</p>;

  const cases = s.cases || [];
  const live = cases.filter(isRunning);
  const held = live.filter((c) => holdWord(c, day));
  const owed = totals(live.map((c) => c.overdueBalance));

  return (
    <section data-testid="collections-desk">
      <p className="dim" style={{ margin: '0 0 2px', fontSize: 13 }}>
        Payment failure and recovery. A case is one customer's whole story.
      </p>

      <Head sub="Cases still running, and what each is waiting on.">Open cases</Head>
      {live.length === 0 ? (
        <Calm testid="collections-clean">✓ No collection cases are open.</Calm>
      ) : (
        <>
          <Calm testid="collections-total">
            {live.length} open, {amounts(owed)} overdue in all
            {held.length ? `; ${held.length} held by a promise, a dispute or hardship.` : '.'}
          </Calm>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Customer</th><th>Overdue</th><th>Oldest unpaid</th><th>Stage</th><th>Held by</th></tr>
              </thead>
              <tbody data-testid="collections-body">
                {live.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <button
                        type="button"
                        data-testid="case-link"
                        onClick={() => setOpen(c)}
                        style={{ background: 'none', border: 0, padding: 0, font: 'inherit', color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline' }}
                      >
                        {/* plain() strips the epoch a seed leaves inside a
                            name, so "Mia Journey1783934553605" reads as a
                            person rather than as a batch number */}
                        {names[partyOf(c)] ? plain(names[partyOf(c)]) : '…'}
                      </button>
                    </td>
                    <td>{money(c.overdueBalance)}</td>
                    <td>{day(c.oldestDueAt)}</td>
                    <td>{plain(STAGE[c.state] || c.state || '')}</td>
                    <td className="dim">{holdWord(c, day) || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <Head sub="Cleared or written off. Kept for the record.">Closed</Head>
      <Calm testid="collections-closed">
        {cases.length - live.length} {cases.length - live.length === 1 ? 'case has' : 'cases have'} been
        cleared or written off — the debt was paid, so the ladder reset.
      </Calm>
    </section>
  );
}
