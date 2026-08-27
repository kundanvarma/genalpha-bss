import { useEffect, useState } from 'react';
import { acceptRevaluation, deviceAgreements, deleteResidual, gradeTradeIn,
  rejectRevaluation, residualTable, tradeInValuations, upsertResidual,
  withdrawalCases } from '../api.js';
import { hasRole } from '../auth.js';

/**
 * The device desk: financing agreements (model, status, paid share),
 * trade-in valuations awaiting the grading verdict, revaluation deltas
 * waiting on the customer's word (acceptable to take over the phone),
 * the staff-curated residual table, and open withdrawal cases.
 */
const AGREEMENT_STATUSES = ['all', 'active', 'settled', 'swapped', 'defaulted', 'withdrawn'];

const money = (v, c) => (v == null ? '—' : `${Number(v).toFixed(2)} ${c || ''}`);
const d = (v) => (v ? new Date(v).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }) : '—');

export default function Devices() {
  const canWrite = hasRole('device:write');
  const [status, setStatus] = useState('all');
  const [agreements, setAgreements] = useState(null);
  const [awaiting, setAwaiting] = useState([]);   // accepted + in-transit → gradable
  const [deltas, setDeltas] = useState([]);       // revalued → accept/reject
  const [residuals, setResiduals] = useState([]);
  const [withdrawals, setWithdrawals] = useState([]);
  const [grade, setGrade] = useState({});         // valuationId -> {finalGrade, finalValue}
  const [residualDraft, setResidualDraft] = useState({ deviceRef: '', ageMonths: '', baseValue: '', currency: 'EUR' });
  const [error, setError] = useState(null);

  const reloadAgreements = () => {
    deviceAgreements(status === 'all' ? null : status)
      .then(setAgreements).catch((e) => setError(e.message));
  };
  const reloadTradeIns = () => {
    Promise.all([tradeInValuations('accepted'), tradeInValuations('in-transit')])
      .then(([a, b]) => setAwaiting([...a, ...b])).catch(() => setAwaiting([]));
    tradeInValuations('revalued').then(setDeltas).catch(() => setDeltas([]));
  };
  const reloadRest = () => {
    residualTable().then(setResiduals).catch(() => setResiduals([]));
    withdrawalCases().then(setWithdrawals).catch(() => setWithdrawals([]));
  };

  useEffect(reloadAgreements, [status]);
  useEffect(() => { reloadTradeIns(); reloadRest(); }, []);

  async function act(fn, andThen) {
    try {
      setError(null);
      await fn();
      andThen();
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <>
      <h1>Device desk</h1>
      {error && <p className="error" data-testid="device-error">{error}</p>}

      <h2>Financing agreements{agreements && !agreements.length && <span className="secnone"> — none</span>}</h2>
      <div className="tabs">
        {AGREEMENT_STATUSES.map((s) => (
          <button key={s} className={`tab ${status === s ? 'on' : ''}`}
                  data-testid={`agreement-filter-${s}`}
                  onClick={() => setStatus(s)}>{s}</button>
        ))}
      </div>
      <div className="rows" data-testid="agreement-list">
        {(agreements || []).map((a) => (
          <div className="row" key={a.id} data-testid="agreement-row">
            <div>
              <strong>{a.device?.name || a.device?.id || 'Device'}</strong>
              <div className="dim small">
                {a.financingModel} · {a.termMonths} mo × {money(a.monthlyAmount, a.currency)}
                {a.externalAgreementNo ? ` · ${a.externalAgreementNo}` : ''}
              </div>
            </div>
            <div className="rowend">
              <span className="dim small">{a.installmentsPaid}/{a.termMonths} paid · {Number(a.paidSharePct || 0).toFixed(0)}%</span>
              <span className="linetotal">{money(a.remainingPrincipal, a.currency)} left</span>
              <span className={`state ${a.status}`}>{a.status}</span>
            </div>
          </div>
        ))}
        {agreements && !agreements.length && <p className="dim small">No agreements with this status.</p>}
        {!agreements && <p className="dim small">Loading agreements…</p>}
      </div>

      <h2>Valuations awaiting grading{!awaiting.length && <span className="secnone"> — none</span>}</h2>
      <div className="rows" data-testid="grading-list">
        {awaiting.map((v) => (
          <div className="row" key={v.id} data-testid="grading-row">
            <div>
              <strong>{v.deviceRef}</strong>
              <div className="dim small">IMEI {v.imei} · estimated {money(v.estimatedValue, v.currency)} · offer until {d(v.offerExpiry)}</div>
            </div>
            <div className="rowend">
              <span className={`state ${v.status}`}>{v.status}</span>
              {canWrite && (
                <form className="stack" style={{ marginTop: 0 }} onSubmit={(e) => {
                  e.preventDefault();
                  const draft = grade[v.id] || {};
                  if (!draft.finalValue) return;
                  act(() => gradeTradeIn(v.id, {
                    finalGrade: draft.finalGrade || 'B',
                    finalValue: Number(draft.finalValue),
                  }), () => { setGrade((g) => ({ ...g, [v.id]: undefined })); reloadTradeIns(); });
                }}>
                  <select value={(grade[v.id] || {}).finalGrade || 'B'} data-testid="grade-select"
                          onChange={(e) => setGrade((g) => ({ ...g, [v.id]: { ...(g[v.id] || {}), finalGrade: e.target.value } }))}>
                    {['A', 'B', 'C', 'D'].map((gr) => <option key={gr} value={gr}>grade {gr}</option>)}
                  </select>
                  <input type="number" step="0.01" min="0" style={{ maxWidth: 120, flex: 'none' }}
                         placeholder="final value" data-testid="grade-value"
                         value={(grade[v.id] || {}).finalValue || ''}
                         onChange={(e) => setGrade((g) => ({ ...g, [v.id]: { ...(g[v.id] || {}), finalValue: e.target.value } }))} />
                  <button className="ghost" type="submit" data-testid="grade-submit">Record grading</button>
                </form>
              )}
            </div>
          </div>
        ))}
        {!awaiting.length && <p className="dim small">Nothing waiting on a grading verdict.</p>}
      </div>

      <h2>Grading deltas{!deltas.length && <span className="secnone"> — none</span>}</h2>
      <div className="rows" data-testid="delta-list">
        {deltas.map((v) => (
          <div className="row" key={v.id} data-testid="delta-row">
            <div>
              <strong>{v.deviceRef}</strong>
              <div className="dim small">
                estimated {money(v.estimatedValue, v.currency)} → graded {money(v.finalValue, v.currency)}
                {' · '}delta <span className={Number(v.delta) < 0 ? 'error' : 'ok'}>{money(v.delta, v.currency)}</span>
              </div>
            </div>
            <div className="rowend">
              <span className={`state ${v.status}`}>{v.status}</span>
              {canWrite && (
                <>
                  <button className="ghost" data-testid="accept-revaluation"
                          title="The customer accepts the regraded value — with their say-so on the line"
                          onClick={() => act(() => acceptRevaluation(v.id), reloadTradeIns)}>
                    Accept revaluation
                  </button>
                  <button className="ghost danger" data-testid="reject-revaluation"
                          title="The customer wants the device back instead"
                          onClick={() => act(() => rejectRevaluation(v.id), reloadTradeIns)}>
                    Reject
                  </button>
                </>
              )}
            </div>
          </div>
        ))}
        {!deltas.length && <p className="dim small">No revaluations waiting on the customer.</p>}
      </div>

      <h2>Residual table{!residuals.length && <span className="secnone"> — none</span>}</h2>
      <div className="rows" data-testid="residual-list">
        {residuals.map((r) => (
          <div className="row" key={r.id} data-testid="residual-row">
            <span>{r.deviceRef} <span className="dim small">at {r.ageMonths} months</span></span>
            <div className="rowend">
              <span className="linetotal">{money(r.baseValue, r.currency)}</span>
              {canWrite && (
                <button className="ghost danger" data-testid="residual-delete"
                        onClick={() => act(() => deleteResidual(r.id),
                          () => residualTable().then(setResiduals).catch(() => {}))}>
                  Delete
                </button>
              )}
            </div>
          </div>
        ))}
        {!residuals.length && <p className="dim small">No residual rows curated yet.</p>}
      </div>
      {canWrite && (
        <form className="stack" data-testid="residual-form" onSubmit={(e) => {
          e.preventDefault();
          const { deviceRef, ageMonths, baseValue, currency } = residualDraft;
          if (!deviceRef.trim() || ageMonths === '' || baseValue === '') return;
          act(() => upsertResidual({
            deviceRef: deviceRef.trim(),
            ageMonths: Number(ageMonths),
            baseValue: Number(baseValue),
            currency: currency || 'EUR',
          }), () => {
            setResidualDraft({ deviceRef: '', ageMonths: '', baseValue: '', currency: currency || 'EUR' });
            residualTable().then(setResiduals).catch(() => {});
          });
        }}>
          <input placeholder="device ref (model)" data-testid="residual-device"
                 value={residualDraft.deviceRef}
                 onChange={(e) => setResidualDraft((s) => ({ ...s, deviceRef: e.target.value }))} />
          <input type="number" min="0" placeholder="age (months)" data-testid="residual-age"
                 style={{ maxWidth: 130, flex: 'none' }} value={residualDraft.ageMonths}
                 onChange={(e) => setResidualDraft((s) => ({ ...s, ageMonths: e.target.value }))} />
          <input type="number" step="0.01" min="0" placeholder="base value" data-testid="residual-value"
                 style={{ maxWidth: 130, flex: 'none' }} value={residualDraft.baseValue}
                 onChange={(e) => setResidualDraft((s) => ({ ...s, baseValue: e.target.value }))} />
          <input placeholder="currency" data-testid="residual-currency"
                 style={{ maxWidth: 90, flex: 'none' }} value={residualDraft.currency}
                 onChange={(e) => setResidualDraft((s) => ({ ...s, currency: e.target.value }))} />
          <button className="ghost" type="submit" data-testid="residual-save">Save residual</button>
        </form>
      )}

      <h2>Withdrawal cases{!withdrawals.length && <span className="secnone"> — none</span>}</h2>
      <div className="rows" data-testid="withdrawal-list">
        {withdrawals.map((w) => (
          <div className="row" key={w.id} data-testid="withdrawal-row">
            <div>
              <strong>Agreement {String(w.agreementRef || '').slice(0, 8)}…</strong>
              <div className="dim small">
                clock started {d(w.clockStart)}
                {w.returnGrade ? ` · returned grade ${w.returnGrade}` : ''}
                {w.deduction != null ? ` · deduction ${money(w.deduction)}` : ''}
              </div>
            </div>
            <div className="rowend">
              {w.refundAmount != null && <span className="linetotal">{money(w.refundAmount)} refunded</span>}
              <span className={`state ${w.status}`}>{w.status}</span>
            </div>
          </div>
        ))}
        {!withdrawals.length && <p className="dim small">No withdrawal cases.</p>}
      </div>
    </>
  );
}
