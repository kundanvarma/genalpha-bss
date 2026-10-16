import { t } from '../../i18n.js';

/** Home's data meter: what is left, and how much of the allowance is used. */
export default function Meter({ label, used, allowed, units, warn }) {
  const pct = allowed ? Math.min(100, (used / allowed) * 100) : 0;
  return (
    <div className="usage-meter" data-testid="home-meter">
      <div className="usage-meter-head"><span>{label}</span><span className={warn ? 'error' : 'dim'}>{Math.max(0, allowed - used)} {units} {t('left')} · {used} / {allowed}</span></div>
      <div className="usage-meter-track"><div className={`usage-meter-fill${warn ? ' over' : ''}`} style={{ width: `${pct}%` }} /></div>
    </div>
  );
}
