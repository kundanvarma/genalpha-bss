import { useCallback, useEffect, useState } from 'react';
import { moment, money, plain } from '../bills/words.js';

/* Two setup surfaces that used to sit among the daily pages.
 *
 * DELIVERIES is the ledger of every bill's trip to the distribution partner:
 * what was sent, after how many tries, what the buyer answered, and what
 * failed and deserves another go. A failure is the exception here, so it is
 * the only thing on the page that is loud.
 *
 * SHADOW BILLING is the standing parallel bill run: what will bill DIFFERENTLY
 * next cycle against what the last real invoice charged. An empty sweep is the
 * good answer and reads as one.
 */

export function Deliveries({ api }) {
  const [state, setState] = useState({ loading: true });
  const [busy, setBusy] = useState(null);

  const load = useCallback(() => api.deliveries().then((rows) => (rows
    ? { loading: false, rows }
    : { loading: false, rows: [], why: 'Billing could not be reached.' })), [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setState(next); });
    return () => { alive = false; };
  }, [load]);

  const retry = async (row) => {
    setBusy(row.id);
    await api.retry(row.id);
    setBusy(null);
    setState(await load());
  };

  if (state.loading) return <p>Reading the delivery ledger…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  const failed = state.rows.filter((r) => r.status === 'failed');
  // the exception goes to the top. A failure buried on row ninety of a ledger
  // that is otherwise all "sent" is a sentence saying six things went wrong and
  // no way to see one of them.
  const rows = [...failed, ...state.rows.filter((r) => r.status !== 'failed')];

  return (
    <section data-testid="deliveries">
      <p className="dim" style={{ margin: '0 0 10px', fontSize: 13 }}>
        Every bill&apos;s trip to the distribution partner, and what the buyer answered.
      </p>
      {failed.length === 0 ? (
        <p data-testid="deliveries-clean" className="dim" style={{ color: 'var(--ink)' }}>
          ✓ Every bill this page holds reached its partner.
        </p>
      ) : (
        <p data-testid="deliveries-failed" style={{ color: 'var(--danger, #b3261e)', fontWeight: 600 }}>
          {failed.length} {failed.length === 1 ? 'delivery' : 'deliveries'} failed and can be sent again.
        </p>
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Bill</th><th>How it went</th><th>Format</th><th>Channel</th>
              <th>Buyer answered</th><th>Sent</th><th /></tr>
          </thead>
          <tbody data-testid="deliveries-body">
            {rows.map((r) => (
              <tr key={r.id}>
                <td>{r.billNo}</td>
                <td style={r.status === 'failed' ? { color: 'var(--danger, #b3261e)' } : undefined}>
                  {r.status}{r.attempts > 1 ? ` after ${r.attempts} tries` : ''}
                  {r.lastError ? ` — ${plain(r.lastError)}` : ''}
                </td>
                <td>{r.format || <span className="dim">—</span>}</td>
                <td>{r.channel || <span className="dim">—</span>}</td>
                <td>{r.buyerStatus || <span className="dim">nothing yet</span>}</td>
                <td>{r.sentAt ? moment(r.sentAt) : <span className="dim">—</span>}</td>
                <td>{r.status === 'failed' ? (
                  <button type="button" className="ghost" data-testid="delivery-retry"
                    disabled={busy === r.id} onClick={() => retry(r)}>
                    {busy === r.id ? 'Sending…' : 'Send again'}
                  </button>
                ) : null}</td>
              </tr>
            ))}
            {rows.length === 0 ? (
              <tr><td colSpan={7} className="dim">No bill has been delivered yet.</td></tr>
            ) : null}
          </tbody>
        </table>
      </div>
    </section>
  );
}

export function Shadow({ api }) {
  const [state, setState] = useState({ loading: true });
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api.drift().then((rows) => (rows
    ? { loading: false, rows }
    : { loading: false, rows: [], why: 'Billing could not be reached.' })), [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setState(next); });
    return () => { alive = false; };
  }, [load]);

  const sweep = async () => {
    setBusy(true);
    await api.sweep();
    setState(await load());
    setBusy(false);
  };

  if (state.loading) return <p>Reading the shadow run…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  return (
    <section data-testid="shadow">
      <div style={{ display: 'flex', gap: 12, alignItems: 'baseline', marginBottom: 10 }}>
        <p className="dim" style={{ margin: 0, fontSize: 13 }}>
          The standing parallel bill run: what would bill differently next cycle against what the last
          real invoice charged. Caught here, before an invoice is wrong.
        </p>
        <button type="button" className="ghost" data-testid="shadow-sweep" style={{ marginLeft: 'auto' }}
          disabled={busy} onClick={sweep}>{busy ? 'Re-pricing…' : 'Run a sweep now'}</button>
      </div>
      {state.rows.length === 0 ? (
        <p data-testid="shadow-clean" className="dim" style={{ color: 'var(--ink)' }}>
          ✓ Nothing would bill differently next cycle.
        </p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>What is priced</th><th>Billed last time</th><th>Would bill now</th>
                <th>Difference</th><th>Found</th></tr>
            </thead>
            <tbody data-testid="shadow-body">
              {state.rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.offeringName || <span className="dim">an unnamed product</span>}</td>
                  <td>{money({ value: r.billedMonthly, unit: r.unit })}</td>
                  <td>{money({ value: r.currentMonthly, unit: r.unit })}</td>
                  <td style={{ color: Number(r.delta) > 0 ? '#b45309' : 'var(--ink)' }}>
                    {money({ value: r.delta, unit: r.unit })}
                  </td>
                  <td>{moment(r.detectedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
