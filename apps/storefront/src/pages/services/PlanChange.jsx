import { Link } from 'react-router-dom';
import { changePlan, myNotifications } from '../../api.js';
import { fmtPrice, pricesOf } from '../../money.js';
import { t } from '../../i18n.js';
import { tokenClaims } from '../../auth.js';
import { useEffect, useState } from 'react';

export const categoryOf = (offering) => offering?.category?.[0]?.name || '';

export const PLAN_CATEGORIES = ['Mobile plans', 'Broadband'];

/**
 * Same number, new plan: a TMF622 modify order that completes instantly.
 * Like-for-like only — the dropdown offers plans from the SAME category as
 * the current one (a mobile plan changes to a mobile plan, broadband to
 * broadband), never devices or add-ons.
 */
export function ChangePlan({ product, services, offerings, prices, onChanged }) {
  const [open, setOpen] = useState(false);
  const [choice, setChoice] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const current = offerings[product.productOffering?.id];
  // the catalog's own word first: exchangableTo names the plans this one may become; without it, same category
  const exchangeable = (current?.productOfferingRelationship || []).filter((r) => String(r.relationshipType || '').toLowerCase() === 'exchangableto').map((r) => r.id);
  const options = !open ? [] : Object.values(offerings)
    .filter((o) => !o.isBundle && !o.requiresVerifiedIdentity && o.id !== current?.id
      && (exchangeable.length ? exchangeable.includes(o.id) : categoryOf(o) === categoryOf(current)))
    .map((o) => {
      const monthly = pricesOf(o, prices).find((p) => p.priceType === 'recurring');
      return monthly ? { id: o.id, name: o.name, label: `${o.name} — ${fmtPrice(monthly)}` } : null;
    })
    .filter(Boolean);

  async function confirm() {
    const target = options.find((o) => o.id === choice);
    if (!target) return;
    setBusy(true); setError(null);
    try {
      // the service realizing this product: match by plan name, so the SOM
      // renames the right line
      const svc = services.find((sv) => sv.name === product.name && sv.state === 'active');
      await changePlan(product.id, svc?.id, target);
      setOpen(false);
      onChanged(target.name);
    } catch (e) { setError(e.message); }
    setBusy(false);
  }

  if (!open) {
    return (
      <button className="ghost" data-testid={`change-plan-${product.id}`} onClick={() => setOpen(true)}>
        {t('Change plan')}
      </button>
    );
  }
  return (
    <span className="change-plan" data-testid="change-plan-form">
      <select value={choice} onChange={(e) => setChoice(e.target.value)} disabled={busy}>
        <option value="">{t('New plan…')}</option>
        {(options || []).map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
      <button className="primary" disabled={!choice || busy} onClick={confirm}>
        {busy ? t('Changing…') : t('Confirm')}
      </button>
      <button className="ghost" disabled={busy} onClick={() => setOpen(false)}>{t('Cancel')}</button>
      {error && <span className="error"> {error}</span>}
    </span>
  );
}

/** Every offering id a bundle can decompose into (fixed children + choice options). */
export function bundleChildIds(offering) {
  const ids = new Set();
  for (const b of offering?.bundledProductOffering || []) {
    if (b.id) ids.add(b.id);
    for (const option of b.options || []) {
      if (option.id) ids.add(option.id);
    }
  }
  return ids;
}

export function ProductRow({ product, services, offerings, prices, onChanged, nested }) {
  const payerTag = (() => {
    const payer = (product.relatedParty || []).find((x) => x.role === 'payer');
    if (!payer || payer.id === tokenClaims().sub) return null;
    return payer['@referredType'] === 'Organization'
      ? t('paid by your company') : t('paid by your household payer');
  })();
  const offering = offerings[product.productOffering?.id];
  const monthly = offering ? pricesOf(offering, prices).find((p) => p.priceType === 'recurring') : null;
  const changeable = product.status === 'active' && PLAN_CATEGORIES.includes(categoryOf(offering));
  return (
    <div className="row" style={nested ? { marginLeft: '1.6em' } : undefined}>
      <strong>{product.name}</strong>
      {monthly && <span className="dim">{fmtPrice(monthly)}</span>}
      {payerTag && <span className="dim" data-testid="paid-by" style={{ fontSize: 12 }}>💳 {payerTag}</span>}
      <span className={`state ${product.status}`}>{product.status}</span>
      {changeable && (
        <ChangePlan product={product} services={services}
          offerings={offerings} prices={prices} onChanged={onChanged} />
      )}
    </div>
  );
}

/**
 * PLAN CHANGES — migration notices ride the communication channel (the same
 * inbox as everything else); this card surfaces the ones about YOUR plan so
 * the legally required written notice is never buried. Base-migration has no
 * customer-facing endpoint by design: the notification IS the customer face.
 */
export function PlanChangeNotices() {
  const [notices, setNotices] = useState([]);
  useEffect(() => {
    myNotifications().then((ms) => setNotices(
      (ms || []).filter((m) => /migrat|plan change|changing your plan|new plan|new terms|price change/i
        .test(`${m.subject || ''} ${m.content || ''}`)),
    )).catch(() => {});
  }, []);
  if (!notices.length) return null;
  return (
    <section className="card" data-testid="plan-change-notices" style={{ padding: '14px 18px', marginBottom: 14 }}>
      <h2 style={{ marginTop: 0 }}>📋 {t('Changes to your plan')}</h2>
      {notices.slice(0, 3).map((m) => (
        <div className="row" key={m.id} data-testid="plan-change-notice">
          <div>
            <strong>{m.subject}</strong>
            <div className="dim small">{m.content}</div>
          </div>
          <span className="dim small">{m.sendTime ? new Date(m.sendTime).toLocaleDateString() : ''}</span>
        </div>
      ))}
      <p className="dim small" style={{ margin: '6px 0 0' }}>
        <Link to="/notifications">{t('All notifications')} →</Link>
      </p>
    </section>
  );
}

