import { useEffect, useState } from 'react';
import { myAutoTopup, mySpendPolicy, patchSpendMeter, roamingContinue, setAutoTopup } from '../api.js';
import { fmtPrice, pricesOf } from '../money.js';
import { t } from '../i18n.js';

const money = (m) => (m ? `${Number(m.value).toFixed(2)} ${m.unit}` : '—');

/** One meter's fill against its limit — the same track the data meters use. */
function MeterBar({ meter }) {
  if (!meter.limit || !Number(meter.limit.value)) return null;
  const pct = Math.min(100, (Number(meter.accrued.value) / Number(meter.limit.value)) * 100);
  return (
    <div className="usage-meter-track" style={{ marginTop: 4 }}>
      <div className={`usage-meter-fill${pct >= 100 ? ' over' : ''}`} style={{ width: `${pct}%` }} />
    </div>
  );
}

/**
 * SPEND CAP — off by default, the customer's own wall. A raised limit or a
 * re-enable clears a stale block server-side.
 */
function SpendCap({ meter, onSaved, onError }) {
  const [limit, setLimit] = useState(meter.limit ? String(meter.limit.value) : '');
  const [busy, setBusy] = useState(false);
  const save = async (dto) => {
    setBusy(true); onError(null);
    try { onSaved(await patchSpendMeter('spend', dto)); } catch (e) { onError(e.message); }
    setBusy(false);
  };
  return (
    <div className="row" data-testid="spend-cap">
      <div>
        <strong>{t('Spend cap')}</strong>
        <div className="dim small">
          {meter.enabled
            ? <>{t('Extra charges stop at')} {money(meter.limit)} — {t('this month:')} {money(meter.accrued)}
                {meter.blocked && <span className="error"> · {t('cap reached — extras are paused')}</span>}</>
            : t('No cap — top-ups and extras are unlimited. Set one if you want a hard wall.')}
        </div>
        <MeterBar meter={meter} />
      </div>
      <span className="rowend">
        <input style={{ width: '5.5em' }} inputMode="decimal" placeholder={t('amount')}
               data-testid="spend-cap-input" value={limit}
               onChange={(e) => setLimit(e.target.value.replace(/[^0-9.]/g, ''))} />
        <button className="ghost" data-testid="spend-cap-save" disabled={busy || !limit}
                onClick={() => save({ enabled: true, limit: Number(limit) })}>
          {meter.enabled ? t('Change') : t('Set cap')}
        </button>
        {meter.enabled && (
          <button className="ghost" data-testid="spend-cap-off" disabled={busy}
                  onClick={() => save({ enabled: false })}>
            {t('Remove')}
          </button>
        )}
      </span>
    </div>
  );
}

/**
 * CONTENT SERVICES (charged over the phone bill): barring is FREE and always
 * available; the cap's lowest selectable value is a statutory floor — the
 * backend refuses lower, and we show its words when it does.
 */
function ContentControls({ meter, onSaved, onError }) {
  const [limit, setLimit] = useState(meter.limit ? String(meter.limit.value) : '');
  const [busy, setBusy] = useState(false);
  const floor = meter.lowestSelectableLimit;
  const act = async (dto) => {
    setBusy(true); onError(null);
    try { onSaved(await patchSpendMeter('content', dto)); } catch (e) { onError(e.message); }
    setBusy(false);
  };
  return (
    <div className="row" data-testid="content-controls">
      <div>
        <strong>{t('Content services')}</strong>
        <div className="dim small">
          {meter.barred
            ? t('Barred — nothing can be charged to your phone bill. Barring is free.')
            : <>{t('Purchases charged to your phone bill.')}{' '}
                {meter.limit ? <>{t('Monthly limit')} {money(meter.limit)} — {t('used')} {money(meter.accrued)}</>
                  : t('No monthly limit set.')}
                {floor && <> · {t('lowest selectable limit')} {money(floor)}</>}</>}
        </div>
        {!meter.barred && <MeterBar meter={meter} />}
      </div>
      <span className="rowend">
        {!meter.barred && (
          <>
            <input style={{ width: '5.5em' }} inputMode="decimal"
                   placeholder={floor ? String(floor.value) : t('amount')}
                   data-testid="content-limit-input" value={limit}
                   onChange={(e) => setLimit(e.target.value.replace(/[^0-9.]/g, ''))} />
            <button className="ghost" data-testid="content-limit-save" disabled={busy || !limit}
                    onClick={() => act({ limit: Number(limit) })}>
              {t('Set limit')}
            </button>
          </>
        )}
        <button className="ghost" data-testid="content-barring-toggle" disabled={busy}
                onClick={() => act({ barred: !meter.barred })}>
          {meter.barred ? t('Lift barring') : t('Bar content services')}
        </button>
      </span>
    </div>
  );
}

/**
 * ROAMING LIMIT — the default financial wall abroad: warn at 80 %, hard
 * cut-off at 100 %, and past it ONLY on the customer's explicit, audited
 * "continue anyway" (EU 2022/612).
 */
function RoamingControls({ meter, onSaved, onError }) {
  const [limit, setLimit] = useState(meter.limit ? String(meter.limit.value) : '');
  const [busy, setBusy] = useState(false);
  const cutOff = meter.blocked && !meter.continueElected;
  const act = async (fn) => {
    setBusy(true); onError(null);
    try { onSaved(await fn()); } catch (e) { onError(e.message); }
    setBusy(false);
  };
  return (
    <div className="row" data-testid="roaming-controls">
      <div>
        <strong>{t('Roaming limit')}</strong>
        <div className="dim small">
          {t('Data abroad stops at')} <b>{money(meter.limit)}</b> {t('per month')} — {t('used')}{' '}
          {money(meter.accrued)}.
          {meter.continueElected && <span> · {t('you chose to continue past the limit this month')}</span>}
          {cutOff && <span className="error"> · {t('limit reached — roaming data is paused')}</span>}
        </div>
        <MeterBar meter={meter} />
      </div>
      <span className="rowend">
        <input style={{ width: '5.5em' }} inputMode="decimal" placeholder={t('amount')}
               data-testid="roaming-limit-input" value={limit}
               onChange={(e) => setLimit(e.target.value.replace(/[^0-9.]/g, ''))} />
        <button className="ghost" data-testid="roaming-limit-save" disabled={busy || !limit}
                onClick={() => act(() => patchSpendMeter('roaming', { limit: Number(limit) }))}>
          {t('Change limit')}
        </button>
        {cutOff && (
          <button className="primary" data-testid="roaming-continue" disabled={busy}
                  onClick={() => {
                    if (!window.confirm(t('Keep roaming past your limit? Charges continue at roaming rates for the rest of the month, and your request is recorded.'))) return;
                    act(roamingContinue);
                  }}>
            {t('Continue anyway')}
          </button>
        )}
      </span>
    </div>
  );
}

/**
 * AUTO TOP-UP — strictly opt-in: a boost offering, a trigger, per-cycle
 * caps, and an EXPLICIT consent checkbox; there is no default-on path.
 */
function AutoTopupCard({ boostOfferings, prices }) {
  const [policy, setPolicy] = useState(null);
  const [offeringId, setOfferingId] = useState('');
  const [maxBoosts, setMaxBoosts] = useState('1');
  const [maxSpend, setMaxSpend] = useState('');
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);

  useEffect(() => {
    myAutoTopup().then((p) => {
      setPolicy(p);
      if (p.boostOfferingId) setOfferingId(p.boostOfferingId);
      if (p.maxBoostsPerCycle) setMaxBoosts(String(p.maxBoostsPerCycle));
      if (p.maxSpendPerCycle != null) setMaxSpend(String(p.maxSpendPerCycle));
    }).catch(() => setPolicy({ enabled: false }));
  }, []);

  if (!policy) return null;

  const save = async (dto) => {
    setBusy(true); setErr(null);
    try { setPolicy(await setAutoTopup(dto)); } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  const label = (o) => {
    const price = pricesOf(o, prices).find((p) => p.priceType === 'oneTime');
    return `${o.name}${price ? ` — ${fmtPrice(price)}` : ''}`;
  };

  return (
    <div className="row" data-testid="auto-topup">
      <div style={{ flex: 1 }}>
        <strong>{t('Auto top-up when data runs out')}</strong>
        {policy.enabled ? (
          <div className="dim small" data-testid="auto-topup-on">
            ✓ {t('On')} — {t('buys')}{' '}
            {boostOfferings.find((o) => o.id === policy.boostOfferingId)?.name || t('your boost')}{' '}
            {t('when you run dry')}, {t('at most')} {policy.maxBoostsPerCycle}×/{t('month')}
            {policy.maxSpendPerCycle != null && <> · {t('max spend')} {policy.maxSpendPerCycle}/{t('month')}</>}
          </div>
        ) : (
          <div className="dim small">{t('Never run dry mid-month — an extra data boost buys itself the moment you hit zero. Off unless you say so.')}</div>
        )}
        {!policy.enabled && (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginTop: 6 }}>
            <select value={offeringId} data-testid="auto-topup-offering"
                    onChange={(e) => setOfferingId(e.target.value)}>
              <option value="" disabled>{t('choose a boost…')}</option>
              {boostOfferings.map((o) => <option key={o.id} value={o.id}>{label(o)}</option>)}
            </select>
            <label className="small dim">{t('at most')}{' '}
              <input style={{ width: '3em' }} inputMode="numeric" value={maxBoosts}
                     data-testid="auto-topup-max"
                     onChange={(e) => setMaxBoosts(e.target.value.replace(/\D/g, ''))} />
              ×/{t('month')}
            </label>
            <label className="small dim">{t('max spend')}{' '}
              <input style={{ width: '4em' }} inputMode="decimal" value={maxSpend}
                     placeholder={t('none')} data-testid="auto-topup-spend"
                     onChange={(e) => setMaxSpend(e.target.value.replace(/[^0-9.]/g, ''))} />
              /{t('month')}
            </label>
            <label className="small" data-testid="auto-topup-consent-label">
              <input type="checkbox" checked={consent} data-testid="auto-topup-consent"
                     onChange={(e) => setConsent(e.target.checked)} />
              {' '}{t('I agree these purchases happen automatically')}
            </label>
          </div>
        )}
        {err && <div className="error small" data-testid="auto-topup-error">{err}</div>}
      </div>
      <span className="rowend">
        {policy.enabled ? (
          <button className="ghost" data-testid="auto-topup-off" disabled={busy}
                  onClick={() => save({ enabled: false })}>
            {t('Turn off')}
          </button>
        ) : (
          <button className="ghost" data-testid="auto-topup-save"
                  disabled={busy || !offeringId || !consent}
                  onClick={() => save({ enabled: true, consent: true, boostOfferingId: offeringId,
                    trigger: 'depletion',
                    ...(maxBoosts ? { maxBoostsPerCycle: Number(maxBoosts) } : {}),
                    ...(maxSpend ? { maxSpendPerCycle: Number(maxSpend) } : {}) })}>
            {t('Turn on')}
          </button>
        )}
      </span>
    </div>
  );
}

/**
 * The USAGE CONTROLS card on My page: the three spend-meter faces, auto
 * top-up consent, and the travel passes (zone boosts) in force. Fail-soft:
 * a deployment without the usage-policy component shows nothing.
 */
export default function UsageControls({ boostOfferings, prices, zoneBuckets }) {
  const [meters, setMeters] = useState(null);
  const [err, setErr] = useState(null);

  useEffect(() => {
    mySpendPolicy().then(setMeters).catch(() => setMeters([]));
  }, []);

  if (meters === null || !meters.length) return null;
  const byType = Object.fromEntries(meters.map((m) => [m.meterType, m]));
  const replace = (saved) => setMeters(meters.map((m) => (m.meterType === saved.meterType ? saved : m)));

  return (
    <section className="card" data-testid="usage-controls" style={{ padding: '14px 18px', marginBottom: 14 }}>
      <h2 style={{ marginTop: 0 }}>{t('Usage controls')}</h2>
      {err && <p className="error small" data-testid="usage-controls-error">{err}</p>}
      {byType.spend && <SpendCap meter={byType.spend} onSaved={replace} onError={setErr} />}
      {byType.content && <ContentControls meter={byType.content} onSaved={replace} onError={setErr} />}
      {byType.roaming && <RoamingControls meter={byType.roaming} onSaved={replace} onError={setErr} />}
      <AutoTopupCard boostOfferings={boostOfferings} prices={prices} />
      {(zoneBuckets || []).length > 0 && (
        <>
          <h3 style={{ margin: '12px 0 4px' }}>✈️ {t('Travel passes')}</h3>
          {zoneBuckets.map((b, i) => {
            const used = Number(b.usedValue);
            const allowed = b.allowedValue == null ? null : Number(b.allowedValue);
            const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
            return (
              <div className="usage-meter" data-testid="travel-pass" key={i}>
                <div className="usage-meter-head">
                  <span>{b.name}</span>
                  <span className="dim">
                    {used}{allowed != null ? ` / ${allowed}` : ''} {b.units}
                    {allowed == null && ` — ${t('no pass for this zone: home rates do not apply')}`}
                  </span>
                </div>
                {allowed != null && (
                  <div className="usage-meter-track">
                    <div className="usage-meter-fill" style={{ width: `${pct}%` }} />
                  </div>
                )}
              </div>
            );
          })}
        </>
      )}
    </section>
  );
}
