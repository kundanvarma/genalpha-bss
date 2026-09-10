import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { acceptTradeInRevaluation, deviceUpgradeEligibility, myDeviceAgreements, myTradeIns,
  openDeviceWithdrawal, rejectTradeInRevaluation } from '../api.js';
import { t } from '../i18n.js';

// TMF-ish machine words → customer words.
const MODEL_LABEL = {
  OPERATOR_BOOK: 'Monthly instalments — on your bill',
  BNPL: 'Pay later — with the pay-later provider',
  THIRD_PARTY_LOAN: 'Bank financing',
};
const TRADE_LABEL = {
  quoted: 'Estimate ready — accept to send your phone in',
  accepted: 'Accepted — mail your phone in',
  'in-transit': 'On its way to us',
  graded: 'Checked by our partner',
  revalued: 'Value revised — your call',
  settled: 'Settled',
  'rejected-returned': 'Revision declined — phone on its way back to you',
};

const WITHDRAWAL_DAYS = 14;

/** Days left in the withdrawal window; clock starts at delivery (or signing). */
function withdrawalDaysLeft(a) {
  const start = a.deliveredAt || a.payoutReceivedAt || null;
  // no delivery recorded yet: the honest fallback is the signing date, which
  // is not in the view — treat "recently active with nothing paid" as open
  // and let the backend be the judge (it knows the real clock).
  const from = start ? new Date(start) : null;
  if (!from) return a.installmentsPaid === 0 && a.status === 'active' ? WITHDRAWAL_DAYS : 0;
  const elapsed = (Date.now() - from.getTime()) / 86400000;
  return Math.max(0, Math.ceil(WITHDRAWAL_DAYS - elapsed));
}

function PaidShareBar({ pct }) {
  const width = Math.min(100, Math.max(0, Number(pct)));
  return (
    <div className="usage-meter-track" data-testid="paid-share-bar" style={{ marginTop: 6 }}>
      <div className="usage-meter-fill" style={{ width: `${width}%` }} />
    </div>
  );
}

function AgreementCard({ agreement, onChanged }) {
  const [eligibility, setEligibility] = useState(null);
  const [withdrawal, setWithdrawal] = useState(null); // result | error text
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (agreement.status === 'active') {
      deviceUpgradeEligibility(agreement.id).then(setEligibility).catch(() => {});
    }
  }, [agreement.id, agreement.status]);

  async function withdraw() {
    if (!window.confirm(t('Cancel this device purchase? Inside the 14-day withdrawal window you get your money back, including standard shipping — send the phone back in the state you received it.'))) return;
    setBusy(true);
    try {
      const w = await openDeviceWithdrawal(agreement.id);
      setWithdrawal(w);
      onChanged();
    } catch (e) {
      setWithdrawal(e.message);
    }
    setBusy(false);
  }

  const daysLeft = agreement.status === 'active' ? withdrawalDaysLeft(agreement) : 0;
  const financed = agreement.financingModel !== 'FULL';
  const fmt = (v) => `${Number(v).toFixed(2)} ${agreement.currency}`;
  return (
    <section className="card devcard" data-testid={`device-agreement-${agreement.id}`}
      style={{ padding: '14px 18px', marginBottom: 14 }}>
      <div className="row" style={{ borderBottom: 'none', padding: '2px 0' }}>
        <strong>{agreement.device?.id || t('Your device')}</strong>
        <span className="dim small">{MODEL_LABEL[agreement.financingModel] || agreement.financingModel}</span>
        <span className={`state ${agreement.status}`} data-testid="agreement-status">{agreement.status}</span>
      </div>
      {financed && (
        <>
          <p className="dim small" style={{ margin: '4px 0' }} data-testid="paid-share">
            {t('Paid so far:')} <b>{Number(agreement.paidSharePct).toFixed(0)}%</b>
            {' — '}{agreement.installmentsPaid} / {agreement.termMonths} × {fmt(agreement.monthlyAmount)}
            {' · '}{t('total cost')} {fmt(agreement.totalCostOfOwnership)}
            {Number(agreement.remainingPrincipal) > 0
              && <>{' · '}{t('remaining')} {fmt(agreement.remainingPrincipal)}</>}
          </p>
          <PaidShareBar pct={agreement.paidSharePct} />
        </>
      )}
      {agreement.tradeInDelta != null && (
        <p className="error small" data-testid="trade-in-delta">
          {t('Your trade-in was revalued')} ({fmt(agreement.tradeInDelta)}) — {t('decide under Trade-ins below.')}
        </p>
      )}
      {eligibility && (
        <p className="small" data-testid="upgrade-eligibility"
           style={{ color: eligibility.eligible ? 'var(--ok)' : 'var(--dim)' }}>
          {eligibility.eligible
            ? <>✓ {t('Upgrade-eligible')} — {t('trade this phone in against a new one.')}{' '}
                <Link to="/" className="promolink" data-testid="upgrade-cta">{t('Browse new phones')} →</Link></>
            : <>{t('Not yet upgrade-eligible:')} {eligibility.reason}</>}
        </p>
      )}
      {daysLeft > 0 && !withdrawal && (
        <p className="small">
          <button className="ghost danger" data-testid="withdraw-device" disabled={busy}
                  onClick={withdraw}>
            {t('Cancel purchase (withdrawal)')}
          </button>
          {' '}<span className="dim">{daysLeft} {t('day(s) left in the 14-day window')}</span>
        </p>
      )}
      {withdrawal && typeof withdrawal === 'object' && (
        <p className="small" style={{ color: 'var(--ok)' }} data-testid="withdrawal-done">
          ✓ {t('Withdrawal accepted — refund of')} {Number(withdrawal.refundAmount).toFixed(2)} {agreement.currency}{' '}
          {t('is on its way back the way you paid. Please return the phone.')}
        </p>
      )}
      {withdrawal && typeof withdrawal === 'string' && (
        <p className="error small" data-testid="withdrawal-error">{withdrawal}</p>
      )}
    </section>
  );
}

function TradeInRow({ valuation, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const value = valuation.finalValue != null ? valuation.finalValue : valuation.estimatedValue;
  const decide = async (accept) => {
    setBusy(true); setErr(null);
    try {
      await (accept ? acceptTradeInRevaluation(valuation.id) : rejectTradeInRevaluation(valuation.id));
      onChanged();
    } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  return (
    <div className="row" data-testid={`trade-in-${valuation.id}`}>
      <div>
        <strong>{valuation.deviceRef}</strong>
        <div className="dim small" data-testid="trade-in-status">
          {TRADE_LABEL[valuation.status] || valuation.status}
          {valuation.status === 'quoted' && valuation.offerExpiry
            && <> · {t('offer valid until')} {valuation.offerExpiry.slice(0, 10)}</>}
        </div>
        {valuation.status === 'revalued' && (
          <div className="small">
            {t('Our partner values it at')} <b>{Number(valuation.finalValue).toFixed(2)} {valuation.currency}</b>{' '}
            ({t('estimate was')} {Number(valuation.estimatedValue).toFixed(2)}).{' '}
            <button className="ghost" disabled={busy} data-testid="revaluation-accept"
                    onClick={() => decide(true)}>{t('Accept new value')}</button>
            {' '}
            <button className="ghost danger" disabled={busy} data-testid="revaluation-reject"
                    onClick={() => decide(false)}>{t('Send my phone back')}</button>
          </div>
        )}
        {err && <div className="error small">{err}</div>}
      </div>
      <span className="linetotal">{Number(value).toFixed(2)} {valuation.currency}</span>
    </div>
  );
}

/**
 * MY DEVICES — the financed-phone ledger: every agreement with its paid-share
 * progress, upgrade eligibility, the 14-day withdrawal door, and the
 * trade-ins in flight. Party-scoped server-side; composable deployments
 * without the device component simply show the empty note.
 */
export default function Devices() {
  const [agreements, setAgreements] = useState(null);
  const [tradeIns, setTradeIns] = useState([]);
  const [error, setError] = useState(null);

  const load = () => {
    myDeviceAgreements().then(setAgreements).catch((e) => {
      setAgreements([]);
      setError(/HTTP 5\d\d|Failed to fetch|NetworkError/.test(e.message)
        ? t('Your devices are not available right now — please try again in a few minutes.') : e.message);
    });
    myTradeIns().then(setTradeIns).catch(() => {});
  };
  useEffect(load, []);

  if (error) return <p className="error">{error}</p>;
  if (!agreements) return <p className="dim">{t('Loading your devices…')}</p>;

  return (
    <>
      <h1>{t('My devices')}</h1>
      {!agreements.length && (
        <p className="dim" data-testid="no-devices">
          {t('No device agreements yet — buy a phone on instalments or pay-later and it appears here.')}{' '}
          <Link to="/">{t('Browse phones')} →</Link>
        </p>
      )}
      {agreements.map((a) => <AgreementCard key={a.id} agreement={a} onChanged={load} />)}
      {tradeIns.length > 0 && (
        <>
          <h2>♻️ {t('Trade-ins')}</h2>
          <div className="rows" data-testid="trade-in-list">
            {tradeIns.map((v) => <TradeInRow key={v.id} valuation={v} onChanged={load} />)}
          </div>
        </>
      )}
    </>
  );
}
