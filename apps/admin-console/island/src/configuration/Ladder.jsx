import { useCallback, useEffect, useState } from 'react';
import { LADDER, rungOf, stateLabel } from '../accounting/words.js';
import { moment, plain } from '../bills/words.js';

/* The ladder: drafted, validated, approved, activated.
 *
 * A change to the chart of accounts decides which account real money lands in,
 * so it does not happen because somebody typed. It is written down, checked
 * against the live books, signed for by name, and only then applied — and the
 * service refuses every step taken out of order, so this screen shows a ladder
 * rather than enforcing one.
 *
 * Every rung carries a name and a time, because six months later the only
 * question that matters about an account is who moved it and why.
 */

const STEPS = {
  draft: { step: 'validate', label: 'Validate' },
  validated: { step: 'approve', label: 'Approve' },
  approved: { step: 'activate', label: 'Activate' },
};

/** The four rungs, with the one it stands on marked. */
function Rungs({ state }) {
  const at = rungOf(state);
  if (state === 'withdrawn') {
    return <span className="dim" data-testid="rungs">Withdrawn — nothing changed.</span>;
  }
  return (
    <ol data-testid="rungs" style={{ display: 'flex', gap: 6, listStyle: 'none', margin: 0, padding: 0,
      fontSize: '0.8rem', flexWrap: 'wrap' }}>
      {LADDER.map((rung, i) => (
        <li key={rung.key} style={{ color: i <= at ? 'var(--ink)' : 'var(--dim)',
          fontWeight: i === at ? 600 : 400 }}>
          {i <= at ? '●' : '○'} {rung.label}{i < LADDER.length - 1 ? ' →' : ''}
        </li>
      ))}
    </ol>
  );
}

function Findings({ findings }) {
  if (!findings || !findings.length) return null;
  return (
    <ul data-testid="findings" style={{ margin: '8px 0 0', paddingLeft: '1.1rem', fontSize: '0.85rem' }}>
      {findings.map((f) => (
        <li key={f.message} data-severity={f.severity}
          style={{ color: f.severity === 'blocks' ? 'var(--danger, #b3261e)' : '#b45309' }}>
          {f.severity === 'blocks' ? 'Refused: ' : 'Consequence: '}{plain(f.message)}
        </li>
      ))}
    </ul>
  );
}

/** Who did what, and when — the audit metadata, folded away until asked for. */
function Trail({ change }) {
  const rows = [
    ['Drafted', change.draftedBy, change.draftedAt],
    ['Validated', change.validatedBy, change.validatedAt],
    ['Approved', change.approvedBy, change.approvedAt],
    ['Activated', change.activatedBy, change.activatedAt],
  ].filter(([, who]) => who);
  return (
    <details data-testid="change-trail" style={{ marginTop: 10 }}>
      <summary style={{ cursor: 'pointer', fontSize: '0.85rem' }}>Who changed what</summary>
      <dl style={{ margin: '6px 0 0', fontSize: '0.83rem', display: 'grid',
        gridTemplateColumns: 'max-content 1fr', gap: '2px 12px' }}>
        {rows.map(([label, who, at]) => (
          <Row key={label} label={label} who={who} at={at} />
        ))}
        <dt className="dim">Posting key</dt><dd style={{ margin: 0 }}>{change.postingKey}</dd>
        <dt className="dim">Change</dt><dd style={{ margin: 0 }}>{change.id}</dd>
      </dl>
    </details>
  );
}

function Row({ label, who, at }) {
  return (
    <>
      <dt className="dim">{label}</dt>
      <dd style={{ margin: 0 }}>{who}{at ? ` · ${moment(at)}` : ''}</dd>
    </>
  );
}

function Change({ change, onStep, busy, said }) {
  const next = STEPS[change.state];
  return (
    <li data-testid="change" data-state={change.state}
      style={{ border: '1px solid var(--line, #ddd)', borderRadius: 10, padding: '0.8rem 1rem' }}>
      <b style={{ display: 'block' }} data-testid="change-summary">{plain(change.summary)}</b>
      <p className="dim" style={{ margin: '2px 0 8px', fontSize: '0.85rem' }}>
        {change.reason ? `${plain(change.reason)} · ` : ''}
        {change.postingsUsing
          ? `${change.postingsUsing.toLocaleString()} postings already carry ${change.currentCode}`
          : 'nothing has been booked to this account yet'} · {stateLabel(change.state)}
      </p>
      <Rungs state={change.state} />
      <Findings findings={change.findings} />
      <p style={{ margin: '8px 0 0', fontSize: '0.88rem' }} data-testid="change-next">{change.nextStep}</p>
      <div style={{ display: 'flex', gap: 8, marginTop: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        {next ? (
          <button type="button" data-testid={`change-${next.step}`} disabled={busy === change.id}
            onClick={() => onStep(change, next.step)}>
            {busy === change.id ? 'Working…' : next.label}
          </button>
        ) : null}
        {STEPS[change.state] ? (
          <button type="button" className="ghost" data-testid="change-withdraw" disabled={busy === change.id}
            onClick={() => onStep(change, 'withdraw')}>Withdraw</button>
        ) : null}
        {said[change.id] ? (
          <span className="dim" data-testid="change-said" style={{ fontSize: '0.85rem' }}>{said[change.id]}</span>
        ) : null}
      </div>
      <Trail change={change} />
    </li>
  );
}

export function Ladder({ api }) {
  const [state, setState] = useState({ loading: true });
  const [busy, setBusy] = useState(null);
  const [said, setSaid] = useState({});

  const load = useCallback(() => api.changes().then((changes) => (changes
    ? { loading: false, changes }
    : { loading: false, changes: [], why: 'The subledger could not be reached.' })), [api]);

  useEffect(() => {
    let alive = true;
    load().then((next) => { if (alive) setState(next); });
    return () => { alive = false; };
  }, [load]);

  const step = async (change, name) => {
    setBusy(change.id);
    const res = await api.step(change.id, name);
    let why = '';
    if (!res || !res.ok) {
      why = 'That step would not go through.';
      try {
        const answer = await res.json();
        if (answer && answer.message) why = plain(answer.message);
      } catch { /* the status is the answer */ }
    }
    setSaid((was) => ({ ...was, [change.id]: why }));
    setBusy(null);
    setState(await load());
  };

  if (state.loading) return <p>Reading the changes on the ladder…</p>;
  if (state.why) return <p className="dim">{state.why}</p>;

  const open = state.changes.filter((c) => ['draft', 'validated', 'approved'].includes(c.state));
  const done = state.changes.filter((c) => !['draft', 'validated', 'approved'].includes(c.state));

  return (
    <section data-testid="ladder">
      <p className="dim" style={{ margin: '0 0 12px', fontSize: 13 }}>
        A change to the chart of accounts is written down, checked against the live books, approved by
        name and only then applied. Nothing here takes effect until it is activated.
      </p>
      {open.length === 0 ? (
        <p className="dim" data-testid="ladder-clean" style={{ color: 'var(--ink)' }}>
          ✓ No change is waiting. The chart of accounts is as it was approved.
        </p>
      ) : (
        <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: 12 }}>
          {open.map((c) => <Change key={c.id} change={c} onStep={step} busy={busy} said={said} />)}
        </ul>
      )}
      <details data-testid="ladder-history" style={{ marginTop: 16 }}>
        <summary style={{ cursor: 'pointer', fontSize: '0.9rem' }}>
          What has already been decided ({done.length})
        </summary>
        <ul style={{ listStyle: 'none', margin: '10px 0 0', padding: 0, display: 'grid', gap: 12 }}>
          {done.map((c) => <Change key={c.id} change={c} onStep={step} busy={busy} said={said} />)}
          {done.length === 0 ? <li className="dim">Nothing has been decided yet.</li> : null}
        </ul>
      </details>
    </section>
  );
}
