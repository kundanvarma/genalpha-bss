import { useEffect, useState } from 'react';
import { caseHold, casePromiseToPay, caseRelease, caseWriteOff, collectionCases,
  dunningPolicies, patchDunningPolicy, runCollectionSweep } from '../api.js';
import { hasRole } from '../auth.js';

/**
 * The collections desk: the case worklist by ladder state with its aging,
 * the per-case doors (promise-to-pay entry, dispute/hardship holds, the
 * write-off with its mandatory reason), the sweep trigger, and the dunning
 * policy beside its country statutory floor — the floor is the law's, shown
 * read-only; only the tenant-adjustable knobs are editable.
 */
const CASE_STATES = ['all', 'current', 'reminded', 'warned', 'restricted',
  'suspended', 'terminated', 'writtenOff'];

const daysOverdue = (iso) => {
  if (!iso) return null;
  return Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86400000));
};

export default function Collections() {
  const admin = hasRole('billing:admin');
  const [cases, setCases] = useState(null);
  const [stateFilter, setStateFilter] = useState('all');
  const [selectedId, setSelectedId] = useState(null);
  const [policies, setPolicies] = useState(null);
  const [promise, setPromise] = useState({ days: '7', amount: '' });
  const [holdAmount, setHoldAmount] = useState('');
  const [error, setError] = useState(null);
  const [sweeping, setSweeping] = useState(false);

  const reload = () => {
    collectionCases(stateFilter === 'all' ? null : stateFilter)
      .then(setCases).catch((e) => setError(e.message));
    dunningPolicies().then(setPolicies).catch((e) => setError(e.message));
  };
  useEffect(reload, [stateFilter]);

  async function act(fn) {
    try {
      setError(null);
      const out = await fn();
      reload();
      return out;
    } catch (e) {
      // the 4xx stories verbatim — "the promise-to-pay allowance is used up",
      // "a write-off needs a reason", the statutory refusals…
      setError(e.message);
      return null;
    }
  }

  const selected = (cases || []).find((c) => c.id === selectedId) || null;

  return (
    <>
      <h1>Collections desk
        {admin && (
          <button className="ghost" style={{ marginLeft: 12, fontSize: 13 }} data-testid="run-sweep"
                  title="Walk this tenant's dunning ladder now instead of waiting for the scheduled tick"
                  disabled={sweeping}
                  onClick={async () => {
                    setSweeping(true);
                    await act(runCollectionSweep);
                    setSweeping(false);
                  }}>
            {sweeping ? 'Sweeping…' : 'Run sweep'}
          </button>
        )}
      </h1>
      {error && <p className="error" data-testid="collections-error">{error}</p>}

      <h2>Cases{cases && !cases.length && <span className="secnone"> — none</span>}</h2>
      <div className="tabs">
        {CASE_STATES.map((s) => (
          <button key={s} className={`tab ${stateFilter === s ? 'on' : ''}`}
                  data-testid={`case-filter-${s}`}
                  onClick={() => setStateFilter(s)}>{s}</button>
        ))}
      </div>
      <div className="rows" data-testid="case-list">
        {(cases || []).map((c) => {
          const aged = daysOverdue(c.oldestDueAt);
          return (
            <div className="row" key={c.id} data-testid="case-row"
                 style={{ cursor: 'pointer', background: selectedId === c.id ? 'var(--teal-soft)' : undefined }}
                 onClick={() => setSelectedId(c.id === selectedId ? null : c.id)}>
              <div>
                <strong>{c.accountId}</strong>
                <div className="dim small">
                  {Number(c.overdueBalance?.value || 0).toFixed(2)} {c.overdueBalance?.unit || ''} overdue
                  {c.oldestDueAt && ` · oldest due ${String(c.oldestDueAt).slice(0, 10)}`}
                  {aged !== null && ` (${aged} day${aged === 1 ? '' : 's'})`}
                  {` · step ${c.stepIndex}`}{c.feeCount > 0 && ` · ${c.feeCount} fee${c.feeCount === 1 ? '' : 's'}`}
                </div>
              </div>
              <div className="rowend">
                {c.holds?.promiseToPay && <span className="state onHold" title={`promised ${c.holds.promiseToPay.amount} by ${c.holds.promiseToPay.dueAt}`}>promise</span>}
                {c.holds?.dispute && <span className="state onHold" title={`${c.holds.dispute.amount} frozen in dispute`}>dispute</span>}
                {c.holds?.hardship && <span className="state onHold">hardship</span>}
                <span className={`state ${c.state}`}>{c.state}</span>
              </div>
            </div>
          );
        })}
        {cases && !cases.length && <p className="dim small">No collection cases in this state.</p>}
        {!cases && <p className="dim small">Loading cases…</p>}
      </div>

      {selected && (
        <section data-testid="case-detail">
          <h2>Case {selected.id.slice(0, 8)}… — {selected.accountId}{' '}
            <span className={`state ${selected.state}`}>{selected.state}</span>
          </h2>
          <div className="rows">
            <div className="row">
              <span>Overdue balance</span>
              <span>{Number(selected.overdueBalance?.value || 0).toFixed(2)} {selected.overdueBalance?.unit || ''}</span>
            </div>
            {selected.warnedAt && (
              <div className="row"><span>Payment demand sent</span>
                <span className="dim">{String(selected.warnedAt).slice(0, 19)}</span></div>
            )}
            {selected.holds?.promiseToPay && (
              <div className="row" data-testid="case-promise-hold">
                <span>Promise to pay</span>
                <span>{Number(selected.holds.promiseToPay.amount).toFixed(2)} by {String(selected.holds.promiseToPay.dueAt).slice(0, 10)}</span>
              </div>
            )}
            {selected.holds?.dispute && (
              <div className="row" data-testid="case-dispute-hold">
                <span>Dispute hold (only this amount is frozen)</span>
                <div className="rowend">
                  <span>{Number(selected.holds.dispute.amount).toFixed(2)}</span>
                  {admin && (
                    <button className="ghost" data-testid="release-dispute"
                            onClick={() => act(() => caseRelease(selected.id, 'dispute'))}>
                      Release
                    </button>
                  )}
                </div>
              </div>
            )}
            {selected.holds?.hardship && (
              <div className="row" data-testid="case-hardship-hold">
                <span>Hardship hold (manual)</span>
                <div className="rowend">
                  {admin && (
                    <button className="ghost" data-testid="release-hardship"
                            onClick={() => act(() => caseRelease(selected.id, 'hardship'))}>
                      Release
                    </button>
                  )}
                </div>
              </div>
            )}
            {(selected.enforcedServices || []).length > 0 && (
              <div className="row" data-testid="case-enforced">
                <span>Services under enforcement</span>
                <span className="dim small">{selected.enforcedServices.join(' · ')}</span>
              </div>
            )}
            {selected.curedAt && (
              <div className="row"><span>Cured</span>
                <span className="dim">{String(selected.curedAt).slice(0, 19)}</span></div>
            )}
            {selected.writtenOffAt && (
              <div className="row" data-testid="case-writeoff-info">
                <span>Written off — {selected.writeOffReason}</span>
                <span className="dim">{String(selected.writtenOffAt).slice(0, 19)}</span>
              </div>
            )}
          </div>

          {admin && !['terminated', 'writtenOff'].includes(selected.state) && (
            <div className="stack" data-testid="case-actions" style={{ flexWrap: 'wrap' }}>
              <input type="number" min="1" style={{ maxWidth: 90, flex: 'none' }}
                     data-testid="case-promise-days" title="Days the customer promises to pay within"
                     value={promise.days}
                     onChange={(e) => setPromise((s) => ({ ...s, days: e.target.value }))} />
              <input type="number" min="0" step="0.01" style={{ maxWidth: 130, flex: 'none' }}
                     data-testid="case-promise-amount" placeholder="amount (full)"
                     title="Promised amount — empty means the full overdue balance"
                     value={promise.amount}
                     onChange={(e) => setPromise((s) => ({ ...s, amount: e.target.value }))} />
              <button className="ghost" data-testid="case-promise"
                      onClick={() => act(() => casePromiseToPay(selected.id, {
                        ...(promise.days ? { days: Number(promise.days) } : {}),
                        ...(promise.amount ? { amount: Number(promise.amount) } : {}),
                      }))}>
                Record promise to pay
              </button>
              <input type="number" min="0" step="0.01" style={{ maxWidth: 130, flex: 'none' }}
                     data-testid="hold-amount" placeholder="disputed amount"
                     value={holdAmount} onChange={(e) => setHoldAmount(e.target.value)} />
              <button className="ghost" data-testid="hold-dispute"
                      title="Freeze ONLY the disputed amount — the rest of the balance still ages"
                      disabled={!holdAmount}
                      onClick={() => act(() => caseHold(selected.id, { type: 'dispute', amount: Number(holdAmount) }))}>
                Dispute hold
              </button>
              <button className="ghost" data-testid="hold-hardship"
                      onClick={() => act(() => caseHold(selected.id, { type: 'hardship' }))}>
                Hardship hold
              </button>
              <button className="ghost danger" data-testid="case-writeoff"
                      title="Only under the policy threshold, only with a reason — the auditors will ask"
                      onClick={() => {
                        const reason = window.prompt('Write off this balance — why? (required)');
                        if (reason) act(() => caseWriteOff(selected.id, reason));
                      }}>
                Write off
              </button>
            </div>
          )}
        </section>
      )}

      <h2>Dunning policy{policies && !policies.length && <span className="secnone"> — none</span>}</h2>
      <div className="rows" data-testid="policy-list">
        {(policies || []).map((p) => (
          <div key={p.id} style={{ marginBottom: 12 }}>
            <div className="row" data-testid="policy-row">
              <div>
                <strong>{p.name}</strong>
                <div className="dim small">
                  {p.country} · payment term {p.paymentTermDays} days · entry ≥ {p.entryThreshold} {p.currency || ''}
                  {' · '}{(p.steps || []).map((s) => `${s.action}@+${s.offsetDays}d`).join(' → ')}
                  {' · '}reconnection fee {p.reconnectionFee} · write-off ≤ {p.writeOffThreshold}
                  {' · '}{p.promiseMaxPerPeriod} promise{p.promiseMaxPerPeriod === 1 ? '' : 's'}/{p.promisePeriodDays}d, max {p.promiseMaxDays}d each
                </div>
                {p.statutory && (
                  <div className="dim small" data-testid="policy-statutory"
                       title="The country statutory pack — the tenant policy cannot undercut it">
                    Statutory floor ({p.statutory.country}) — read-only: fee gate {p.statutory.reminderFeeGateDays} days
                    {p.statutory.reminderFeeCap != null && ` · fee cap ${p.statutory.reminderFeeCap}`}
                    {' '}· max {p.statutory.maxFeeBearingReminders} fee-bearing reminders
                    {' '}· enforcement notice {p.statutory.enforcementNoticeDays} days
                    {' '}· minimum actionable {p.statutory.minActionableAmount}
                    {p.statutory.emergencyAlwaysReachable && ' · emergency numbers always reachable'}
                    {p.statutory.mrcStopsWhileSuspended && ' · no subscription charges while suspended'}
                  </div>
                )}
              </div>
              <span className={`state ${p.active ? 'active' : 'closed'}`}>{p.active ? 'active' : 'inactive'}</span>
            </div>
            {admin && <PolicyEditor policy={p} act={act} />}
          </div>
        ))}
        {policies && !policies.length && <p className="dim small">No dunning policy yet — the ladder stands still until one is active.</p>}
        {!policies && <p className="dim small">Loading policies…</p>}
      </div>
    </>
  );
}

/** The tenant-adjustable knobs only — the statutory floor above is the law's. */
function PolicyEditor({ policy, act }) {
  const [draft, setDraft] = useState({
    entryThreshold: policy.entryThreshold,
    reconnectionFee: policy.reconnectionFee,
    writeOffThreshold: policy.writeOffThreshold,
    active: policy.active,
  });
  const field = (key, label, testid) => (
    <label className="dim small captioned">
      {label}
      <input type="number" min="0" step="0.01"
             data-testid={testid} value={draft[key] ?? ''}
             onChange={(e) => setDraft((s) => ({ ...s, [key]: e.target.value }))} />
    </label>
  );
  return (
    <div className="stack" data-testid="policy-editor" style={{ marginTop: 6, flexWrap: 'wrap' }}>
      {field('entryThreshold', 'entry threshold', 'policy-entry-threshold')}
      {field('reconnectionFee', 'reconnection fee', 'policy-reconnection-fee')}
      {field('writeOffThreshold', 'write-off threshold', 'policy-writeoff-threshold')}
      <label className="dim small" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <input type="checkbox" data-testid="policy-active" checked={!!draft.active}
               onChange={(e) => setDraft((s) => ({ ...s, active: e.target.checked }))} />
        active
      </label>
      <button className="ghost" data-testid="policy-save"
              onClick={() => act(() => patchDunningPolicy(policy.id, {
                entryThreshold: Number(draft.entryThreshold),
                reconnectionFee: Number(draft.reconnectionFee),
                writeOffThreshold: Number(draft.writeOffThreshold),
                active: !!draft.active,
              }))}>
        Save policy
      </button>
    </div>
  );
}
