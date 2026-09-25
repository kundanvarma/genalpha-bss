import { money } from '../bills/words.js';

/* The shared pieces of the Billing & Revenue desk, so the overview, payments
 * and collections read as one screen family rather than three.
 *
 * The paper's priority rule lives here, in one place: an exception is loud and
 * takes you somewhere, a queue is visible but quiet, a normal financial state
 * is calm and never styled as a warning, and a count of zero reads as clean.
 */

const BAD = 'var(--danger, #b3261e)';
const WARN = '#b45309';

/** An amount list ("1 412.33 EUR") from totals(); em dash when there is none. */
export const amounts = (list) => (list && list.length ? list.map(money).join(' · ') : '—');

/** A count read at a glance: 3 815, never 3815. */
export const num = (v) => new Intl.NumberFormat().format(Number(v) || 0);

/** P1 — an exception worth acting on: loud, counted, and a way in.
 *  With nothing wrong it becomes a tick, quiet, and leads nowhere. */
export function Exception({ testid, clean, count, amount, label, hint, onOpen }) {
  const clear = !count;
  return (
    <button
      type="button"
      data-testid={testid}
      data-state={clear ? 'clean' : 'exception'}
      onClick={clear ? undefined : onOpen}
      disabled={clear}
      title={hint || ''}
      style={{
        flex: '1 1 14rem', minWidth: '13rem', textAlign: 'left', font: 'inherit',
        padding: '0.85rem 1rem', borderRadius: 10, cursor: clear ? 'default' : 'pointer',
        border: `1px solid ${clear ? 'var(--line, #ddd)' : BAD}`,
        background: clear ? 'var(--card, #fff)' : 'color-mix(in srgb, var(--danger, #b3261e) 6%, var(--card, #fff))',
      }}
    >
      {clear ? (
        <span style={{ color: 'var(--dim)' }}>✓ {clean}</span>
      ) : (
        <>
          <b style={{ display: 'block', fontSize: '1.5rem', color: BAD }}>{amount || num(count)}</b>
          <span style={{ fontSize: '0.85rem' }}>{label}</span>
          {hint ? <span style={{ display: 'block', fontSize: '0.78rem', color: 'var(--dim)' }}>{hint}</span> : null}
        </>
      )}
    </button>
  );
}

/** P2 — the queue: work waiting, seen without being shouted about. */
export function Queue({ items }) {
  const live = items.filter((i) => i.count);
  if (!live.length) return <p className="dim" data-testid="queue-empty" style={{ margin: 0 }}>Nothing waiting.</p>;
  return (
    <ul data-testid="queue" style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 6 }}>
      {live.map((i) => (
        <li key={i.key} style={{ display: 'flex', gap: 10, alignItems: 'baseline' }}>
          <b style={{ minWidth: '2.5rem', color: WARN }}>{num(i.count)}</b>
          <button
            type="button"
            data-testid={`queue-${i.key}`}
            onClick={i.onOpen}
            style={{ background: 'none', border: 0, padding: 0, font: 'inherit', color: 'var(--teal-text, var(--teal))', cursor: 'pointer', textDecoration: 'underline' }}
          >
            {i.label}
          </button>
          {i.note ? <span className="dim" style={{ fontSize: '0.85rem' }}>{i.note}</span> : null}
        </li>
      ))}
    </ul>
  );
}

/** P3 — status, calm by construction: no colour, no border, no alarm. */
export function Calm({ testid, children }) {
  return (
    <p data-testid={testid} className="dim" style={{ margin: '0 0 4px', fontSize: '0.92rem', color: 'var(--ink)' }}>
      {children}
    </p>
  );
}

/** A section head, so the three screens carry the same rhythm. */
export function Head({ children, sub }) {
  return (
    <div style={{ margin: '18px 0 8px' }}>
      <h3 style={{ margin: 0, fontSize: '1rem' }}>{children}</h3>
      {sub ? <p className="dim" style={{ margin: '2px 0 0', fontSize: '0.83rem' }}>{sub}</p> : null}
    </div>
  );
}

/** Info — history and finished work, folded away until asked for. */
export function Drilldown({ summary, testid, children }) {
  return (
    <details data-testid={testid} style={{ marginTop: 14 }}>
      <summary style={{ cursor: 'pointer', fontSize: '0.9rem' }}>{summary}</summary>
      <div style={{ marginTop: 8 }}>{children}</div>
    </details>
  );
}
