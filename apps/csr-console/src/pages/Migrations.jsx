import { useEffect, useState } from 'react';
import { armMigrationPlan, attachMigrationSimulation, createMigrationPlan,
  exitMigrationCustomer, migrationCustomers, migrationPlans, migrationProgress,
  offeringNames, pauseMigrationPlan, resumeMigrationPlan,
  rollbackMigrationCustomer } from '../api.js';
import { hasRole } from '../auth.js';

/**
 * The migration desk: plan list + builder over the catalog, the rehearsal
 * gate (Arm stays disabled until a simulation receipt is attached — the
 * server refuses with a 409 anyway; we surface it as a banner), the wave
 * switches, per-plan progress, and the per-customer doors (the exercised
 * exit the law requires, and the snapshot rollback).
 */
const DELTA_CLASSES = ['beneficial', 'neutral', 'detrimental'];
const IN_BINDING = ['defer-to-expiry', 'exclude', 'free-exit'];
const CUSTOMER_STATES = ['all', 'scheduled', 'noticed', 'exit-window', 'order-emitted',
  'migrated', 'exited', 'failed', 'rolled-back'];

const emptyRow = () => ({ sourceOfferingId: '', targetOfferingId: '', deltaClass: 'neutral' });

export default function Migrations() {
  const admin = hasRole('migration:admin');
  const [plans, setPlans] = useState(null);
  const [selected, setSelected] = useState(null);   // the plan object
  const [progress, setProgress] = useState(null);
  const [customers, setCustomers] = useState([]);
  const [stateFilter, setStateFilter] = useState('all');
  const [offerings, setOfferings] = useState({});   // id -> name
  const [simRef, setSimRef] = useState('');
  const [error, setError] = useState(null);
  const [showBuilder, setShowBuilder] = useState(false);
  const [draft, setDraft] = useState({ name: '', inBinding: 'defer-to-expiry', noticeDays: 30, rows: [emptyRow()] });

  const reloadPlans = () => { migrationPlans().then(setPlans).catch((e) => setError(e.message)); };
  useEffect(reloadPlans, []);
  useEffect(() => { offeringNames().then(setOfferings).catch(() => {}); }, []);

  const openPlan = (plan) => {
    setSelected(plan);
    setSimRef(plan.simulationRef || '');
    setProgress(null);
    migrationProgress(plan.id).then(setProgress).catch(() => {});
    migrationCustomers(plan.id, stateFilter === 'all' ? null : stateFilter)
      .then(setCustomers).catch(() => setCustomers([]));
  };

  useEffect(() => {
    if (!selected) return;
    migrationCustomers(selected.id, stateFilter === 'all' ? null : stateFilter)
      .then(setCustomers).catch(() => setCustomers([]));
  }, [stateFilter]);

  async function act(fn) {
    try {
      setError(null);
      const updated = await fn();
      reloadPlans();
      if (updated && updated.id && selected && updated.id === selected.id) openPlan(updated);
      else if (selected) migrationProgress(selected.id).then(setProgress).catch(() => {});
      return updated;
    } catch (e) {
      // the 409s carry the story (e.g. "cannot arm without an attached
      // simulation receipt") — show them verbatim, no page loss
      setError(e.message);
      return null;
    }
  }

  const offeringOptions = Object.entries(offerings)
    .sort((a, b) => a[1].localeCompare(b[1]));

  return (
    <>
      <h1>Migration desk</h1>
      {error && <p className="error" data-testid="migration-error">{error}</p>}

      <h2>Plans{plans && !plans.length && <span className="secnone"> — none</span>}
        {admin && (
          <button className="ghost" style={{ marginLeft: 10 }} data-testid="plan-builder-toggle"
                  onClick={() => setShowBuilder((s) => !s)}>
            {showBuilder ? 'Close builder' : 'New plan'}
          </button>
        )}
      </h2>

      {showBuilder && admin && (
        <form className="copilot" data-testid="plan-builder" onSubmit={(e) => {
          e.preventDefault();
          const matrix = draft.rows.filter((r) => r.sourceOfferingId && r.targetOfferingId)
            .map((r) => ({ sourceOfferingId: r.sourceOfferingId, targetOfferingId: r.targetOfferingId, deltaClass: r.deltaClass }));
          if (!draft.name.trim() || !matrix.length) { setError('A plan needs a name and at least one source → target row.'); return; }
          act(() => createMigrationPlan({
            name: draft.name.trim(),
            matrix,
            eligibility: { inBinding: draft.inBinding },
            trigger: { type: 'bulk' },
            jurisdictionPack: { noticeDays: Number(draft.noticeDays) || 30 },
          })).then((created) => {
            if (created) {
              setShowBuilder(false);
              setDraft({ name: '', inBinding: 'defer-to-expiry', noticeDays: 30, rows: [emptyRow()] });
            }
          });
        }}>
          <div className="stack">
            <input placeholder="Plan name (e.g. Sunset legacy broadband)" data-testid="plan-name"
                   value={draft.name} onChange={(e) => setDraft((s) => ({ ...s, name: e.target.value }))} />
          </div>
          {draft.rows.map((row, i) => (
            <div className="stack" key={i} data-testid="matrix-row">
              <select value={row.sourceOfferingId} data-testid="matrix-source"
                      onChange={(e) => setDraft((s) => ({ ...s, rows: s.rows.map((r, j) => (j === i ? { ...r, sourceOfferingId: e.target.value } : r)) }))}>
                <option value="">source offering…</option>
                {offeringOptions.map(([oid, name]) => <option key={oid} value={oid}>{name}</option>)}
              </select>
              <select value={row.targetOfferingId} data-testid="matrix-target"
                      onChange={(e) => setDraft((s) => ({ ...s, rows: s.rows.map((r, j) => (j === i ? { ...r, targetOfferingId: e.target.value } : r)) }))}>
                <option value="">target offering…</option>
                {offeringOptions.map(([oid, name]) => <option key={oid} value={oid}>{name}</option>)}
              </select>
              <select value={row.deltaClass} data-testid="matrix-delta"
                      title="detrimental exposes the penalty-free exit; the notice says so"
                      onChange={(e) => setDraft((s) => ({ ...s, rows: s.rows.map((r, j) => (j === i ? { ...r, deltaClass: e.target.value } : r)) }))}>
                {DELTA_CLASSES.map((dc) => <option key={dc} value={dc}>{dc}</option>)}
              </select>
              {draft.rows.length > 1 && (
                <button type="button" className="ghost danger"
                        onClick={() => setDraft((s) => ({ ...s, rows: s.rows.filter((_, j) => j !== i) }))}>×</button>
              )}
            </div>
          ))}
          <div className="stack">
            <button type="button" className="ghost" data-testid="matrix-add-row"
                    onClick={() => setDraft((s) => ({ ...s, rows: [...s.rows, emptyRow()] }))}>
              + mapping row
            </button>
            <select value={draft.inBinding} data-testid="plan-inbinding"
                    title="What happens to customers still in binding"
                    onChange={(e) => setDraft((s) => ({ ...s, inBinding: e.target.value }))}>
              {IN_BINDING.map((b) => <option key={b} value={b}>in binding: {b}</option>)}
            </select>
            <input type="number" min="0" style={{ maxWidth: 150, flex: 'none' }} data-testid="plan-notice-days"
                   title="Notice days before any change lands (statutory floor applies)"
                   value={draft.noticeDays}
                   onChange={(e) => setDraft((s) => ({ ...s, noticeDays: e.target.value }))} />
            <span className="dim small" style={{ alignSelf: 'center' }}>notice days</span>
            <button className="primary" type="submit" data-testid="plan-create">Create plan</button>
          </div>
        </form>
      )}

      <div className="rows" data-testid="plan-list">
        {(plans || []).map((p) => (
          <div className="row" key={p.id} data-testid="plan-row"
               style={{ cursor: 'pointer', background: selected?.id === p.id ? 'var(--teal-soft)' : undefined }}
               onClick={() => openPlan(p)}>
            <div>
              <strong>{p.name}</strong>
              <div className="dim small">
                {(p.matrix || []).length} mapping{(p.matrix || []).length === 1 ? '' : 's'}
                {' · '}notice {p.noticeDays ?? p.jurisdictionPack?.noticeDays ?? '—'} days
                {p.simulationRef ? ` · simulation ${p.simulationRef}` : ' · not simulated'}
              </div>
            </div>
            <span className={`state ${p.state}`}>{p.state}</span>
          </div>
        ))}
        {plans && !plans.length && <p className="dim small">No migration plans yet.</p>}
        {!plans && <p className="dim small">Loading plans…</p>}
      </div>

      {selected && (
        <section data-testid="plan-detail">
          <h2>{selected.name} <span className={`state ${selected.state}`}>{selected.state}</span></h2>

          {admin && (
            <div className="stack" data-testid="plan-lifecycle">
              <input placeholder="simulationRef (commercial-simulator receipt)" data-testid="sim-ref"
                     value={simRef} onChange={(e) => setSimRef(e.target.value)} />
              <button className="ghost" data-testid="attach-simulation"
                      disabled={!simRef.trim()}
                      onClick={() => act(() => attachMigrationSimulation(selected.id, simRef.trim()))}>
                Attach simulation
              </button>
              <button className="ghost" data-testid="arm-plan"
                      disabled={selected.state !== 'simulated'}
                      title={selected.state === 'simulated'
                        ? 'Arm the plan — the wave scheduler takes it from here'
                        : 'A plan arms only after a simulation receipt is attached (state must be simulated)'}
                      onClick={() => act(() => armMigrationPlan(selected.id))}>
                Arm
              </button>
              {['armed', 'running'].includes(selected.state) && (
                <button className="ghost danger" data-testid="pause-plan"
                        onClick={() => act(() => pauseMigrationPlan(selected.id))}>
                  Pause
                </button>
              )}
              {selected.state === 'paused' && (
                <button className="ghost" data-testid="resume-plan"
                        onClick={() => act(() => resumeMigrationPlan(selected.id))}>
                  Resume
                </button>
              )}
            </div>
          )}
          {selected.state !== 'simulated' && !selected.simulationRef && (
            <p className="dim small" data-testid="arm-hint">
              Arm is disabled until a simulation is attached — the rehearsal gate is server-enforced.
            </p>
          )}

          <h2>Progress</h2>
          {!progress ? <p className="dim small">Loading progress…</p> : (
            <div className="rows" data-testid="plan-progress">
              <div className="row">
                <span>{progress.totalCustomers} customer{progress.totalCustomers === 1 ? '' : 's'} in scope</span>
                <div className="rowend">
                  {Object.entries(progress.byState || {}).map(([st, n]) => (
                    <span key={st} className={`state ${st}`} data-testid={`progress-${st}`}>{st} {n}</span>
                  ))}
                  {progress.consecutiveFailures > 0 && (
                    <span className="error small" data-testid="breaker-count">
                      {progress.consecutiveFailures} consecutive failure{progress.consecutiveFailures === 1 ? '' : 's'}
                    </span>
                  )}
                </div>
              </div>
            </div>
          )}

          <h2>Customers</h2>
          <div className="tabs">
            {CUSTOMER_STATES.map((s) => (
              <button key={s} className={`tab ${stateFilter === s ? 'on' : ''}`}
                      data-testid={`customer-filter-${s}`}
                      onClick={() => setStateFilter(s)}>{s}</button>
            ))}
          </div>
          <div className="rows" data-testid="plan-customers">
            {customers.map((c) => (
              <div className="row" key={c.id} data-testid="migration-customer-row">
                <div>
                  <strong>{c.partyId}</strong>
                  <div className="dim small">
                    {c.sourceOffering?.name || c.sourceOffering?.id || ''}
                    {(c.targetOffering?.name || c.targetOffering?.id) ? ` → ${c.targetOffering.name || c.targetOffering.id}` : ''}
                    {c.deltaClass ? ` · ${c.deltaClass}` : ''}
                    {c.noticeSentAt ? ` · noticed ${String(c.noticeSentAt).slice(0, 10)}` : ''}
                    {c.failureReason ? ` · ${c.failureReason}` : ''}
                  </div>
                </div>
                <div className="rowend">
                  <span className={`state ${c.state}`}>{c.state}</span>
                  {admin && !['exited', 'rolled-back'].includes(c.state) && (
                    <button className="ghost danger" data-testid="customer-exit"
                            title="The exercised penalty-free exit — terminates without fees, as the notice promised"
                            onClick={() => window.confirm('Exercise the penalty-free exit for this customer?')
                              && act(() => exitMigrationCustomer(selected.id, c.id))}>
                      Exit
                    </button>
                  )}
                  {admin && ['migrated', 'failed'].includes(c.state) && (
                    <button className="ghost" data-testid="customer-rollback"
                            title="Restore the snapshot plan the customer had before the wave"
                            onClick={() => window.confirm('Roll this customer back to the snapshot plan?')
                              && act(() => rollbackMigrationCustomer(selected.id, c.id))}>
                      Rollback
                    </button>
                  )}
                </div>
              </div>
            ))}
            {!customers.length && <p className="dim small">No customers in this state.</p>}
          </div>
        </section>
      )}
    </>
  );
}
