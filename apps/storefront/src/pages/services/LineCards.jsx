import { diagnoseMyService, mySim, replaceMySim, resetSimPin } from '../../api.js';
import { t } from '../../i18n.js';
import { useEffect, useState } from 'react';
import RouterPanel from '../RouterPanel.jsx';

/**
 * SIM self-care for a numbered line: masked ICCID always; PUK on request;
 * PIN pushed to the card over the air. The PUK never renders until asked for.
 */
export function SimCard({ serviceId }) {
  const [sim, setSim] = useState(null);
  const [puk, setPuk] = useState(null);
  const [pin, setPin] = useState('');
  const [pinState, setPinState] = useState(null); // null | 'busy' | 'done' | error text

  useEffect(() => { mySim(serviceId).then(setSim).catch(() => {}); }, [serviceId]);
  if (!sim) return null;

  async function showPuk() {
    try { setPuk((await mySim(serviceId, true))?.puk || null); } catch { /* stays hidden */ }
  }
  async function submitPin() {
    setPinState('busy');
    try {
      await resetSimPin(serviceId, pin);
      setPinState('done'); setPin('');
    } catch (e) { setPinState(e.message); }
  }

  return (
    <div className="row" data-testid="sim-card">
      <strong>{t('My SIM')}</strong>
      <span className="dim" data-testid="sim-iccid">{sim.iccid}</span>
      {puk ? (
        <span className="dim">PUK: <strong style={{ color: 'var(--teal)' }} data-testid="sim-puk">{puk}</strong></span>
      ) : (
        <button className="ghost" data-testid="show-puk" onClick={showPuk}>{t('Show PUK')}</button>
      )}
      <span>
        <input
          data-testid="pin-input"
          style={{ width: '6.5em' }}
          placeholder={t('New PIN')}
          inputMode="numeric"
          maxLength={8}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
        />
        <button
          className="ghost"
          data-testid="reset-pin"
          disabled={pin.length < 4 || pinState === 'busy'}
          onClick={submitPin}
        >
          {t('Reset PIN')}
        </button>
        {pinState === 'done' && <span className="dim" data-testid="pin-done"> ✓ sent to your SIM</span>}
        <button className="ghost danger" data-testid="replace-sim"
          onClick={async () => {
            if (!window.confirm(t('Lost or broken SIM? Your old card stops working IMMEDIATELY and a new one is issued on the same number.'))) return;
            try {
              await replaceMySim(serviceId, 'lost');
              setPuk(null);
              setSim(await mySim(serviceId));
            } catch { /* the card stays */ }
          }}>
          {t('Replace SIM')}
        </button>
        {pinState && pinState !== 'done' && pinState !== 'busy' && <span className="error"> {pinState}</span>}
      </span>
    </div>
  );
}

export function UsageMeter({ bucket }) {
  const used = Number(bucket.usedValue);
  const allowed = bucket.allowedValue == null ? null : Number(bucket.allowedValue);
  const over = allowed != null && used > allowed;
  const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
  return (
    <div className="usage-meter" data-testid="usage-meter">
      <div className="usage-meter-head">
        <span>{bucket.name}</span>
        <span className={over ? 'error' : 'dim'}>
          {used} {allowed != null ? `/ ${allowed} ` : ''}{bucket.units}
          {over ? ' — over allowance' : ''}
        </span>
      </div>
      {allowed != null && (
        <div className="usage-meter-track">
          <div
            className={`usage-meter-fill${over ? ' over' : ''}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      )}
    </div>
  );
}

/** Triage before ticket: one button answers "why is it slow?" with the
 * three usual suspects checked server-side — outage, out of data, paused. */
export function LineDoctor({ serviceId }) {
  const [report, setReport] = useState(null);
  const [busy, setBusy] = useState(false);
  return (
    <div style={{ margin: '4px 0' }}>
      <button className="ghost" data-testid="diagnose-line" disabled={busy}
        onClick={async () => {
          setBusy(true);
          try { setReport(await diagnoseMyService(serviceId)); } catch { setReport(null); }
          setBusy(false);
        }}>
        {busy ? t('Checking your line…') : t('Having trouble? Check my line')}
      </button>
      {report && (
        <ul className="small" data-testid="diagnosis">
          {report.findings.map((f, i) => (
            <li key={i} className={f.severity === 'cause' ? 'error' : 'dim'}
                data-testid={`finding-${f.code}`}>
              {f.message}
              {f.code === 'routerOffline' && <> <RouterPanel serviceId={serviceId} compact /></>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** The slice this line rides, read straight off the TMF638 service record —
 * "Priority network until 22:00" while a boost pass runs, nothing otherwise. */
export function SliceBadge({ service }) {
  const chars = Object.fromEntries((service.serviceCharacteristic || []).map((c) => [c.name, c.value]));
  if (!chars.sliceProfile || chars.sliceProfile === 'default') return null;
  const tz = (window.BSS_STOREFRONT_CONFIG || {}).timezone;
  const until = chars.sliceUntil ? new Date(chars.sliceUntil).toLocaleString(undefined,
    { weekday: 'short', hour: '2-digit', minute: '2-digit', ...(tz ? { timeZone: tz } : {}) }) : null;
  return (
    <span className="state slice" data-testid="slice-badge" title={`Slice profile: ${chars.sliceProfile}`}
          style={{ marginLeft: 6, background: 'var(--teal-soft, #fde8d3)', color: 'var(--teal-text, #b45309)', padding: '1px 8px', borderRadius: 999, fontSize: 12 }}>
      ⚡ {t('Priority network')}{until ? ` · ${t('until')} ${until}` : ''}
    </span>
  );
}

